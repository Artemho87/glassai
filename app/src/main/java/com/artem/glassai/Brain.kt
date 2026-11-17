package com.artem.glassai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.meta.wearable.dat.core.types.ChargingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Вся логика: помощник с диалогом, разбор фото, переводчик, заметки, диктофон, заряд. */
object Brain {

  data class Line(val who: String, val text: String)

  private val scope = MainScope()
  private var job: Job? = null
  private var guideJob: Job? = null
  private var controlsJob: Job? = null
  private var backgroundStarted = false
  private var inited = false
  @Volatile private var asking = false

  val status = MutableStateFlow("Готов")
  val busy = MutableStateFlow(false)
  val translatorOn = MutableStateFlow(false)
  val guideOn = MutableStateFlow(false)
  val recordingOn = MutableStateFlow(false)
  val lastPhoto = MutableStateFlow<Bitmap?>(null)
  val lastAnswer = MutableStateFlow("")
  /** Перевод для собеседника крупно на экране */
  val bigText = MutableStateFlow("")
  val bigTranslit = MutableStateFlow("")
  val log = MutableStateFlow<List<Line>>(emptyList())

  private val history = ArrayList<AiClient.Turn>()
  private var lastTalk = 0L
  private val guideSaid = ArrayList<String>()
  @Volatile private var lastTheir = ""

  fun init(context: Context) {
    if (inited) return
    inited = true
    Prefs.init(context)
    Storage.init(context)
    Library.init(context)
    Recorder.init(context)
    Voice.init(context)
  }

  /** Фоновые помощники: заряд очков и (если включено) тачпад. */
  fun startBackground() {
    if (backgroundStarted || !Glasses.initialized.value) return
    backgroundStarted = true
    Glasses.startDeviceMonitor(scope)
    scope.launch {
      Glasses.device.collect { d ->
        if (d != null) watchBattery(d.batteryLevel, d.chargingState == ChargingState.CHARGING)
      }
    }
    applyControls()
  }

  private fun addLog(who: String, text: String) {
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    val prev = log.value.firstOrNull()
    if (prev != null && prev.who.substringAfter(" ") == who && prev.text == text) return
    log.value = (listOf(Line("$time $who", text)) + log.value).take(400)
  }

  fun logAsText(): String = log.value.reversed().joinToString("\n") { "${it.who}: ${it.text}" }

  /** Сказать что-то в очки, если помощник сейчас не занят (уведомления, заряд). */
  fun announce(text: String) {
    addLog("🔔", text)
    if (busy.value || Recorder.recording) return
    scope.launch { runCatching { Voice.speak(text, Prefs.myLang) } }
  }

  // ================= заряд очков =================

  private val batterySamples = ArrayList<Pair<Long, Int>>()
  private var warned20 = false
  private var warned10 = false
  private var lastDrainWarn = 0L

  private fun watchBattery(level: Int, charging: Boolean) {
    if (level <= 0) return
    if (charging) {
      warned20 = false
      warned10 = false
      batterySamples.clear()
      return
    }
    val now = System.currentTimeMillis()
    batterySamples.add(now to level)
    while (batterySamples.size > 1 && now - batterySamples[0].first > 20 * 60_000) batterySamples.removeAt(0)

    if (level <= 10 && !warned10) {
      warned10 = true
      warned20 = true
      announce("В очках осталось $level процентов заряда. Пора на зарядку.")
    } else if (level <= 20 && !warned20) {
      warned20 = true
      announce("В очках осталось $level процентов заряда.")
    }
    val old = batterySamples[0]
    val minutes = (now - old.first) / 60_000
    val drop = old.second - level
    if (minutes >= 8 && drop >= 8 && now - lastDrainWarn > 20 * 60_000) {
      lastDrainWarn = now
      announce("Очки быстро разряжаются: минус $drop процентов за $minutes минут. Осталось $level.")
    }
  }

  // ================= тачпад на дужке (нужна связь с очками) =================

  fun applyControls() {
    if (Prefs.controls && Prefs.cameraFeatures && Glasses.initialized.value) {
      if (controlsJob?.isActive == true) return
      controlsJob =
          Glasses.startControls(
              scope,
              onEvent = { onGlassesEvent(it) },
              onLog = { addLog("👓", it) },
              onGiveUp = { Prefs.controls = false },
          )
    } else {
      controlsJob?.cancel()
      controlsJob = null
    }
  }

  private var lastEventAt = 0L

  private fun onGlassesEvent(name: String) {
    val now = System.currentTimeMillis()
    addLog("👆", "Жест на дужке: $name")
    if (now - lastEventAt < 700) return
    lastEventAt = now
    when (name) {
      "tap" ->
          when {
            Recorder.recording -> stopRecording()
            busy.value || translatorOn.value -> stopAll()
            else -> ask()
          }
      "nav_RIGHT", "nav_UP" -> if (guideOn.value) stopGuide() else startGuide()
      "nav_LEFT", "nav_DOWN" -> noteVoice()
    }
  }

  // ================= ИИ-помощник с диалогом =================

  private fun assistantPrompt(hasPhoto: Boolean): String {
    val lang = Langs.name(Prefs.myLang)
    val photoPart =
        if (hasPhoto)
            "К вопросу приложено фото, снятое камерой умных очков (вид от первого лица). " +
                "Отвечай про него, если вопрос про увиденное."
        else
            "Фото к этому вопросу НЕ приложено, ты ничего не видишь — никогда не притворяйся, что видишь. " +
                "Если вопрос про то, что перед пользователем, одной фразой подскажи: сфотографируй очками " +
                "и спроси «что на фото». На остальные вопросы отвечай как обычно."
    return "Ты голосовой помощник в умных очках Ray-Ban Meta. $photoPart " +
        "Отвечай на языке «$lang», кратко: 1–3 предложения, разговорно. " +
        "Без списков, markdown, эмодзи и ссылок — ответ будет озвучен голосом. " +
        "Тексты, вывески, меню и цены на фото переводи и пересказывай по сути. Помни предыдущие реплики."
  }

  private val visualWords =
      listOf("посмотри", "глянь", "видишь", "что это", "что тут", "что здесь", "передо мной", "прочитай",
          "прочти", "переведи", "сколько стоит", "какой это", "какая это", "что написано", "на фото",
          "на снимке", "фото", "снимок", "в кадре")
  private val stopWords = listOf("стоп", "хватит", "спасибо", "всё", "все", "отбой", "пока")

  private fun has(text: String, vararg words: String) = words.any { text.contains(it) }

  /**
   * «Спросить»: слушаем вопрос → ответ голосом → (диалог) слушаем следующий.
   * @param fixedPhoto фото, о котором идёт разговор (из галереи или «Поделиться»)
   */
  fun ask(fixedPhoto: Bitmap? = null) {
    if (Recorder.recording) {
      addLog("⚠", "Идёт запись диктофона. Сначала останови её.")
      return
    }
    if (asking) return
    if (busy.value && !translatorOn.value) return
    asking = true
    stopTranslatorLoop()
    Voice.stopSpeaking()
    job =
        scope.launch {
          while (busy.value) delay(50) // ждём, пока переводчик отпустит микрофон
          busy.value = true
          var after: (() -> Unit)? = null
          try {
            if (fixedPhoto != null || System.currentTimeMillis() - lastTalk > 10 * 60_000) history.clear()
            if (fixedPhoto != null) lastPhoto.value = fixedPhoto
            var round = 0
            while (isActive) {
              var photo: Bitmap? = fixedPhoto
              var photoError: String? = null
              val question: String

              if (round == 0 && fixedPhoto == null && Prefs.cameraFeatures) {
                // Прямая камера очков: сначала камера, потом микрофон
                val cameraReady = CompletableDeferred<Unit>()
                val photoTask = async {
                  runCatching {
                    Glasses.capture(
                        onStep = { status.value = "Камера: $it…" },
                        onStreaming = { cameraReady.complete(Unit) },
                    )
                  }
                }
                withTimeoutOrNull(8_000) { cameraReady.await() }
                question = listenQuestion()
                status.value = "Снимаю кадр…"
                val r = photoTask.await()
                val err = r.exceptionOrNull()
                if (err is CancellationException) throw err
                photo = r.getOrNull()
                photoError = err?.message
              } else {
                question = listenQuestion()
                if (round > 0) {
                  if (question.isBlank()) break
                  val l = question.lowercase().trim().trimEnd('.', '!')
                  if (stopWords.any { l == it }) break
                }
              }
              val lower = question.lowercase()
              val visual = question.isBlank() || visualWords.any { lower.contains(it) }

              // Нет фото — берём последний снимок, сделанный очками штатно
              if (photo == null && visual) {
                if (Prefs.cameraFeatures && round > 0) {
                  val r = runCatching { Glasses.capture(onStep = { status.value = "Камера: $it…" }) }
                  val err = r.exceptionOrNull()
                  if (err is CancellationException) throw err
                  photo = r.getOrNull()
                  if (err != null) photoError = err.message
                } else if (!Prefs.cameraFeatures) {
                  val explicit = has(lower, "фото", "снимок", "снимке", "последн")
                  val found =
                      withContext(Dispatchers.IO) {
                        runCatching { Storage.latestImage(if (explicit) null else 5) }
                      }
                  val item = found.getOrNull()
                  if (item != null) {
                    val bmp = loadBitmap(item.first)
                    if (bmp != null) {
                      photo = bmp
                      addLog("🖼", "Беру снимок с очков от ${Storage.timeOf(item.second)}")
                    }
                  } else if (explicit) {
                    photoError = found.exceptionOrNull()?.message ?: "В галерее не нашёл снимков с очков"
                  }
                }
              }
              if (photo != null) lastPhoto.value = photo
              if (photoError != null) addLog("⚠", "Фото: $photoError")

              // ---- голосовые команды ----
              if (has(lower, "запиши встречу", "запиши разговор", "включи диктофон", "начни запись")) {
                after = { startRecording() }
                break
              }
              if (lower.startsWith("запиши") || lower.startsWith("заметка") || lower.startsWith("запомни")) {
                val body = question.substringAfter(" ", "").trim()
                if (body.isBlank()) after = { noteVoice() } else saveNote(body)
                break
              }
              if (has(lower, "сфотографируй", "сделай фото", "сделай снимок")) {
                if (Prefs.cameraFeatures) doFullPhoto()
                else Voice.speak("Скажи «Hey Meta, сфотографируй», а потом спроси меня «что на фото».", Prefs.myLang)
                break
              }
              if (has(lower, "включи переводчик")) {
                after = { startTranslator() }
                break
              }
              if (has(lower, "новый разговор", "забудь разговор")) {
                history.clear()
                Voice.speak("Начинаем с чистого листа", Prefs.myLang)
                break
              }

              if (question.isBlank() && photo == null) {
                Voice.speak("Не расслышал вопрос", Prefs.myLang)
                break
              }
              val wantsSave = lower.startsWith("сохрани") && photo != null
              val q =
                  when {
                    wantsSave -> "Опиши в 1–2 предложениях, что на фото, чтобы я это запомнил."
                    question.isBlank() ->
                        "Что на этом фото? Опиши коротко самое важное, тексты и цены переведи."
                    else -> question
                  }
              addLog("Я", q)
              status.value = "Думаю…"
              val answer = AiClient.ask(assistantPrompt(photo != null), q, photo, history = history.toList())
              addLog("ИИ", answer)
              lastAnswer.value = answer
              history.add(AiClient.Turn(q, answer))
              while (history.size > 8) history.removeAt(0)
              lastTalk = System.currentTimeMillis()

              val savedPhoto = photo
              if (wantsSave && savedPhoto != null) {
                saveFind(savedPhoto, answer)
                addLog("⭐", "Сохранено в находки")
              }

              status.value = "Отвечаю…"
              Voice.speak(answer, Prefs.myLang)

              if (!Prefs.dialog) break
              round++
              if (round >= 10) break
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", e.message ?: "Неизвестная ошибка")
            runCatching { Voice.speak("Ошибка. Подробности в журнале.", "ru-RU") }
          } finally {
            Voice.releaseRoute()
            busy.value = false
            asking = false
            status.value = "Готов"
          }
          after?.invoke()
        }
  }

  /** Сигнал, слушаем фразу через микрофон очков (или телефона), потом отпускаем гарнитуру. */
  private suspend fun listenQuestion(silenceMs: Long = 1500): String {
    val glassesAudio = Voice.routeToGlasses()
    Voice.beep()
    status.value = if (glassesAudio) "Слушаю (микрофон очков)…" else "Слушаю (микрофон телефона)…"
    val heard = Voice.listen(Prefs.myLang, silenceMs) ?: ""
    Voice.releaseRoute()
    return heard
  }

  // ================= разбор фото, снятых очками =================

  private suspend fun loadBitmap(uri: Uri): Bitmap? =
      withContext(Dispatchers.IO) { Storage.readBytes(uri)?.let { Glasses.decodeBytes(it, 1600) } }

  /** Последний снимок из галереи → разговор о нём. */
  fun analyzeLatest() {
    if (busy.value || Recorder.recording || asking) return
    scope.launch {
      val found = withContext(Dispatchers.IO) { runCatching { Storage.latestImage() } }
      val item = found.getOrNull()
      if (item == null) {
        val msg =
            found.exceptionOrNull()?.message
                ?: "В галерее нет снимков с очков. Проверь, что Meta AI сохраняет фото в галерею телефона"
        addLog("⚠", msg)
        runCatching { Voice.speak("Не нашёл снимок. Подробности в журнале.", Prefs.myLang) }
        return@launch
      }
      val bmp = loadBitmap(item.first)
      if (bmp == null) {
        addLog("⚠", "Не удалось открыть снимок")
        return@launch
      }
      addLog("🖼", "Снимок от ${Storage.timeOf(item.second)}. Задай вопрос или промолчи — опишу")
      ask(bmp)
    }
  }

  /** Фото, выбранное вручную или присланное через «Поделиться». */
  fun analyzeUri(uri: Uri) {
    if (Recorder.recording) return
    scope.launch {
      val bmp = loadBitmap(uri)
      if (bmp == null) {
        addLog("⚠", "Не удалось открыть фото")
        return@launch
      }
      stopAll()
      addLog("🖼", "Фото открыто. Задай вопрос или промолчи — опишу")
      ask(bmp)
    }
  }

  /** Сохранить последнее фото с ответом ИИ в «Сохранённое». */
  fun saveAnalysis() {
    val p = lastPhoto.value
    val a = lastAnswer.value
    if (p == null || a.isBlank()) {
      addLog("⚠", "Сначала разбери фото")
      return
    }
    scope.launch {
      saveFind(p, a)
      addLog("⭐", "Сохранено в находки")
      runCatching { Voice.speak("Сохранил", Prefs.myLang) }
    }
  }

  // ================= заметки, находки =================

  private suspend fun saveNote(text: String) {
    val uri = withContext(Dispatchers.IO) { Storage.appendText("Заметки", "[${Storage.humanTime()}] $text") }
    Library.add("Заметка", text, uri)
    addLog("📝", text)
    Voice.speak("Записал", Prefs.myLang)
  }

  private suspend fun saveFind(photo: Bitmap, description: String) {
    val name = "Находка_${Storage.stamp()}"
    val uri = withContext(Dispatchers.IO) { Storage.saveBitmap(photo, name) }
    withContext(Dispatchers.IO) {
      Storage.appendText("Находки", "[${Storage.humanTime()}] $description (фото: $name.jpg)")
    }
    Library.add("Находка", description, uri)
  }

  /** Голосовая заметка: говоришь — текст сохраняется. */
  fun noteVoice() {
    if (Recorder.recording || busy.value) return
    busy.value = true
    Voice.stopSpeaking()
    job =
        scope.launch {
          try {
            val text = listenQuestion(silenceMs = 2500)
            if (text.isBlank()) Voice.speak("Не расслышал", Prefs.myLang) else saveNote(text)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", e.message ?: "Ошибка заметки")
          } finally {
            Voice.releaseRoute()
            busy.value = false
            status.value = "Готов"
          }
        }
  }

  // ================= прямая камера очков (выключено по умолчанию) =================

  private suspend fun doFullPhoto() {
    val bytes = Glasses.captureFull { status.value = "Фото: $it…" }
    val uri = withContext(Dispatchers.IO) { Storage.saveImageBytes(bytes, "Фото_${Storage.stamp()}") }
    lastPhoto.value = withContext(Dispatchers.Default) { Glasses.decodeBytes(bytes, 1600) }
    val info = "Фото в полном качестве, ${bytes.size / 1024} КБ"
    Library.add("Фото", info, uri)
    addLog("📷", "$info — сохранено в галерею, папка GlassAI")
    Voice.speak("Фото сохранено", Prefs.myLang)
  }

  fun fullPhoto() = simpleJob("Фото") { doFullPhoto() }

  fun testCamera() =
      simpleJob("Камера") {
        val bmp = Glasses.capture(onStep = { status.value = "Камера: $it…" }, force = true)
        lastPhoto.value = bmp
        addLog("📷", "Кадр получен: ${bmp.width}×${bmp.height}. Камера работает ✅")
      }

  private fun simpleJob(title: String, block: suspend () -> Unit) {
    if (busy.value || Recorder.recording) return
    busy.value = true
    job =
        scope.launch {
          try {
            block()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", "$title: ${e.message}")
          } finally {
            busy.value = false
            status.value = "Готов"
          }
        }
  }

  private fun guidePrompt(): String {
    val told = if (guideSaid.isEmpty()) "ничего" else guideSaid.joinToString(" | ")
    return "Ты гид в умных очках. Тебе приходит кадр с камеры очков: это то, что пользователь видит сейчас. " +
        "Если в кадре есть что-то интересное или полезное — достопримечательность, необычное место или " +
        "предмет, вывеска, меню, цены, текст на чужом языке — расскажи об этом в 1–2 предложениях на языке " +
        "«${Langs.name(Prefs.myLang)}», разговорно, без markdown. Иностранный текст переведи. " +
        "Если ничего примечательного нет или ты об этом уже говорил, ответь одним словом: ПРОПУСК. " +
        "Уже рассказано: $told"
  }

  fun startGuide() {
    if (guideOn.value || !Prefs.cameraFeatures) return
    if (Recorder.recording) return
    guideOn.value = true
    guideSaid.clear()
    addLog("🧭", "Режим гида включён: смотрю вокруг раз в ${Prefs.guideSec} с")
    guideJob =
        scope.launch {
          var fails = 0
          try {
            while (isActive && guideOn.value) {
              if (!busy.value && !Recorder.recording) {
                try {
                  val bmp = Glasses.capture()
                  fails = 0
                  val ans = AiClient.ask(guidePrompt(), "Что интересного в кадре?", bmp, 200)
                  if (!ans.uppercase().contains("ПРОПУСК") && ans.isNotBlank()) {
                    lastPhoto.value = bmp
                    addLog("🧭", ans)
                    guideSaid.add(ans.take(120))
                    while (guideSaid.size > 6) guideSaid.removeAt(0)
                    saveFind(bmp, ans)
                    if (!busy.value) Voice.speak(ans, Prefs.myLang)
                  }
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  fails++
                  addLog("⚠", "Гид: ${e.message}")
                  if (fails >= 3 || Glasses.needsUpdate.value) {
                    addLog("🧭", "Гид выключен: камера не отвечает")
                    break
                  }
                }
              }
              delay(Prefs.guideSec.coerceIn(8, 120) * 1000L)
            }
          } finally {
            guideOn.value = false
          }
        }
  }

  fun stopGuide() {
    if (!guideOn.value) return
    guideOn.value = false
    guideJob?.cancel()
    addLog("🧭", "Режим гида выключен")
  }

  // ================= диктофон встреч =================

  fun startRecording() {
    if (Recorder.recording) return
    stopAll()
    scope.launch {
      try {
        val glassesMic = Prefs.recGlassesMic && Voice.routeToGlasses()
        Recorder.start(glassesMic)
        recordingOn.value = true
        status.value = "Идёт запись (${if (glassesMic) "микрофон очков" else "микрофон телефона"})…"
        addLog("⏺", "Диктофон включён")
      } catch (e: Exception) {
        Voice.releaseRoute()
        addLog("⚠", "Диктофон не запустился: ${e.message}")
      }
    }
  }

  fun stopRecording() {
    if (!Recorder.recording) return
    val files = Recorder.stop()
    val name = "Встреча_${Recorder.sessionName}"
    Voice.releaseRoute()
    recordingOn.value = false
    busy.value = true
    job =
        scope.launch {
          try {
            if (files.isEmpty()) {
              addLog("⚠", "Запись получилась пустой")
              return@launch
            }
            withContext(Dispatchers.IO) {
              files.forEachIndexed { i, f ->
                Storage.saveAudio(f, if (files.size == 1) name else "${name}_часть${i + 1}")
              }
            }
            addLog("⏺", "Запись сохранена: Recordings/GlassAI, частей: ${files.size}")

            val transcript = StringBuilder()
            files.forEachIndexed { i, f ->
              status.value = "Расшифровываю запись: часть ${i + 1} из ${files.size}…"
              val bytes = withContext(Dispatchers.IO) { f.readBytes() }
              val part =
                  AiClient.ask(
                      "Ты расшифровываешь аудиозапись разговора. Выведи дословный текст на языке оригинала. " +
                          "Разных говорящих помечай как «Голос 1:», «Голос 2:» с новой строки. " +
                          "Без комментариев и вступлений. Если речи нет — выведи: (тишина).",
                      "Расшифруй эту запись.",
                      maxTokens = 3000,
                      audio = bytes,
                      audioFormat = "aac",
                  )
              transcript.append(part).append("\n\n")
            }
            status.value = "Готовлю итог встречи…"
            val summary =
                AiClient.ask(
                    "Ты составляешь итог встречи по расшифровке. Пиши на языке «${Langs.name(Prefs.myLang)}». " +
                        "Формат: 1) Суть в 2–3 предложениях. 2) Договорённости. 3) Задачи: кто и что делает. " +
                        "4) Открытые вопросы. Только то, что есть в расшифровке, ничего не выдумывай.",
                    transcript.toString(),
                    maxTokens = 900,
                )
            val text = "ИТОГ ВСТРЕЧИ (${Storage.humanTime()})\n\n$summary\n\n———\nРАСШИФРОВКА\n\n$transcript"
            val uri = withContext(Dispatchers.IO) { Storage.saveText(name, text) }
            Library.add("Встреча", summary, uri)
            addLog("⏺", "Итог встречи сохранён: Documents/GlassAI/$name.txt")
            Voice.speak("Запись расшифрована, итог сохранён", Prefs.myLang)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", "Расшифровка: ${e.message}. Звук сохранён, можно повторить позже.")
          } finally {
            busy.value = false
            status.value = "Готов"
          }
        }
  }

  // ================= ИИ-переводчик =================

  private fun theirLangs(): List<String> =
      listOf(Prefs.theirLang, Prefs.theirLang2)
          .filter { it.isNotBlank() && it != Prefs.myLang }
          .distinct()
          .ifEmpty { listOf("en-US") }

  /** Сопоставляет код языка от ИИ («th», «th-TH») с языком из списка. */
  private fun matchLang(code: String, options: List<String>): String? {
    val p = code.lowercase().trim().take(2)
    if (p.length < 2) return null
    return options.firstOrNull { it.lowercase().startsWith(p) }
  }

  private fun currentTheir(): String {
    val theirs = theirLangs()
    return matchLang(lastTheir, theirs) ?: theirs.first()
  }

  /** Говорит перевод собеседнику через громкий динамик телефона, потом возвращает звук в очки. */
  private suspend fun speakToThem(text: String, lang: String) {
    val wasGlasses = Voice.route == Voice.Route.GLASSES
    Voice.releaseRoute()
    Voice.routeToPhoneSpeaker()
    Voice.speak(text, lang)
    Voice.releaseRoute()
    if (wasGlasses) Voice.routeToGlasses()
  }

  /**
   * Переводчик: слушает разговор, сам понимает, кто говорит.
   * Собеседник → перевод мне в очки. Я (если включено «в обе стороны») → перевод вслух через телефон
   * и крупно на экране.
   */
  fun startTranslator() {
    if (translatorOn.value || Recorder.recording) return
    job?.cancel()
    translatorOn.value = true
    busy.value = true
    job =
        scope.launch {
          val mine = Prefs.myLang
          val theirs = theirLangs()
          lastTheir = currentTheir()
          val convo = ArrayList<String>()
          try {
            for (l in theirs + mine) {
              if (!Voice.hasVoice(l)) {
                addLog("⚠", "Нет голоса для языка «${Langs.name(l)}»: Настройки телефона → Синтез речи → " +
                    "Google → Установить голосовые данные → ${Langs.name(l)}")
              }
            }
            val glassesAudio = Voice.routeToGlasses()
            addLog(
                "ℹ",
                "Переводчик: ${theirs.joinToString("/") { Langs.name(it) }} ⇄ ${Langs.name(mine)}" +
                    (if (Prefs.twoWay) ", в обе стороны" else ", только собеседник") +
                    (if (Prefs.engine == "ai") ", слушает ИИ" else ", распознавание Google") +
                    if (glassesAudio) " (микрофон очков)" else " (микрофон телефона)",
            )
            var misses = 0
            var warnedLang = false
            while (isActive && translatorOn.value) {
              status.value =
                  "Слушаю… ${theirs.joinToString("/") { Langs.name(it) }}" +
                      if (Prefs.twoWay) " и ${Langs.name(mine)}" else ""
              var tr: AiClient.Tr? = null
              try {
                if (Prefs.engine == "ai") {
                  val wav = AudioCapture.recordPhrase(silenceMs = Prefs.pauseMs.toLong())
                  if (wav != null) {
                    status.value = "Перевожу…"
                    tr = AiClient.smartTranslate(null, wav, mine, theirs, lastTheir, null, convo)
                  }
                } else {
                  val others = (theirs + (if (Prefs.twoWay) listOf(mine) else emptyList())).filter { it != lastTheir }
                  val heard = Voice.listenEx(lastTheir, others, Prefs.pauseMs.toLong())
                  if (heard != null) {
                    status.value = "Перевожу…"
                    tr = AiClient.smartTranslate(heard.text, null, mine, theirs, lastTheir, heard.lang, convo)
                  } else if ((Voice.lastError == 12 || Voice.lastError == 13) && !warnedLang) {
                    warnedLang = true
                    addLog("⚠", "Распознавание Google не поддерживает выбранные языки на этом телефоне. " +
                        "В переводчике переключи «Как слушать» на «ИИ»")
                  }
                }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                addLog("⚠", e.message ?: "Ошибка перевода")
                delay(1500)
              }
              val t = tr
              if (t == null || t.translation.isBlank()) {
                misses++
                delay(if (misses > 5) 500 else 100)
                continue
              }
              misses = 0
              val fromMe = matchLang(t.lang, listOf(mine)) != null && matchLang(t.lang, theirs) == null
              if (fromMe) {
                if (!Prefs.twoWay) continue
                addLog("Я", t.text)
                addLog("←", t.translation + if (t.translit.isNotBlank()) "  [${t.translit}]" else "")
                bigText.value = t.translation
                bigTranslit.value = t.translit
                convo.add("Я: ${t.text} → ${t.translation}")
                status.value = "Говорю перевод через телефон…"
                speakToThem(t.translation, lastTheir)
              } else {
                val detected = matchLang(t.lang, theirs)
                if (detected != null) lastTheir = detected
                addLog("Он", t.text)
                addLog("→", t.translation)
                convo.add("Собеседник: ${t.text} → ${t.translation}")
                Voice.speak(t.translation, mine)
              }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", e.message ?: "Ошибка переводчика")
          } finally {
            Voice.releaseRoute()
            translatorOn.value = false
            busy.value = false
            status.value = "Готов"
            if (convo.size >= 2) saveConversation(convo)
          }
        }
  }

  private fun saveConversation(convo: List<String>) {
    runCatching {
      val name = "Разговор_${Storage.stamp()}"
      val uri = Storage.saveText(name, "РАЗГОВОР (${Storage.humanTime()})\n\n" + convo.joinToString("\n"))
      Library.add("Разговор", convo.take(3).joinToString(" · ").take(220), uri)
      addLog("💬", "Разговор сохранён: Documents/GlassAI/$name.txt")
    }
  }

  private suspend fun sayToThem(mine: String) {
    addLog("Я", mine)
    status.value = "Перевожу…"
    val target = currentTheir()
    val t = AiClient.smartTranslate(mine, null, Prefs.myLang, listOf(target), target, Prefs.myLang, emptyList())
    addLog("←", t.translation + if (t.translit.isNotBlank()) "  [${t.translit}]" else "")
    bigText.value = t.translation
    bigTranslit.value = t.translit
    status.value = "Говорю перевод через телефон…"
    speakToThem(t.translation, target)
  }

  /** «Мой ответ» голосом: перевод звучит громко из телефона и крупно на экране. */
  fun replyMine() {
    if (Recorder.recording) return
    val resumeTranslator = translatorOn.value
    stopTranslatorLoop()
    job =
        scope.launch {
          while (busy.value) delay(50)
          busy.value = true
          try {
            Voice.routeToGlasses()
            Voice.beep()
            status.value = "Говори свой ответ (${Langs.name(Prefs.myLang)})…"
            val mine = Voice.listen(Prefs.myLang, 1500)
            Voice.releaseRoute()
            if (mine == null) status.value = "Не расслышал" else sayToThem(mine)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", e.message ?: "Ошибка")
          } finally {
            Voice.releaseRoute()
            busy.value = false
            status.value = "Готов"
          }
          if (resumeTranslator) startTranslator()
        }
  }

  /** Напечатанный ответ: перевод звучит громко из телефона и крупно на экране. */
  fun replyTyped(text: String) {
    if (text.isBlank() || Recorder.recording) return
    val resumeTranslator = translatorOn.value
    stopTranslatorLoop()
    job =
        scope.launch {
          while (busy.value) delay(50)
          busy.value = true
          try {
            sayToThem(text.trim())
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            addLog("⚠", e.message ?: "Ошибка")
          } finally {
            Voice.releaseRoute()
            busy.value = false
            status.value = "Готов"
          }
          if (resumeTranslator) startTranslator()
        }
  }

  private fun stopTranslatorLoop() {
    if (translatorOn.value) {
      translatorOn.value = false
      job?.cancel()
    }
  }

  /** Останавливает помощника и переводчик. Диктофон останавливается своей кнопкой. */
  fun stopAll() {
    translatorOn.value = false
    job?.cancel()
    stopGuide()
    Voice.stopSpeaking()
    if (!Recorder.recording) Voice.releaseRoute()
    busy.value = false
    asking = false
    if (!Recorder.recording) status.value = "Готов"
  }

  fun clearLog() {
    log.value = emptyList()
    bigText.value = ""
    bigTranslit.value = ""
  }
}
