package com.artem.glassai

import android.content.Context
import android.graphics.Bitmap
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

/** Вся логика: помощник с диалогом, переводчик, гид, заметки, диктофон, тачпад, заряд. */
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
  /** Последний перевод крупно — чтобы показать экран собеседнику */
  val bigText = MutableStateFlow("")
  val log = MutableStateFlow<List<Line>>(emptyList())

  private val history = ArrayList<AiClient.Turn>()
  private var lastTalk = 0L
  private val guideSaid = ArrayList<String>()

  fun init(context: Context) {
    if (inited) return
    inited = true
    Prefs.init(context)
    Storage.init(context)
    Library.init(context)
    Recorder.init(context)
    Voice.init(context)
  }

  /** Фоновые помощники: заряд очков и тачпад. Запускается, когда очки доступны. */
  fun startBackground() {
    if (backgroundStarted || !Glasses.initialized.value) return
    backgroundStarted = true
    Glasses.startDeviceMonitor(scope)
    scope.launch { Glasses.device.collect { d -> if (d != null) watchBattery(d.batteryLevel, d.chargingState == ChargingState.CHARGING) } }
    applyControls()
  }

  private fun addLog(who: String, text: String) {
    log.value = (listOf(Line(who, text)) + log.value).take(60)
  }

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

  // ================= тачпад на дужке =================

  fun applyControls() {
    if (Prefs.controls && Glasses.initialized.value) {
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
            "К последнему вопросу приложено фото — это то, что пользователь видит прямо сейчас через " +
                "камеру очков (вид от первого лица). Опирайся на него, если вопрос про увиденное."
        else
            "Фото к этому вопросу не приложено. Если вопрос требует зрения, а в разговоре уже было " +
                "описание увиденного — опирайся на него; иначе просто ответь на вопрос."
    return "Ты голосовой помощник в умных очках Ray-Ban Meta. $photoPart " +
        "Отвечай на языке «$lang», кратко: 1–3 предложения, разговорно. " +
        "Без списков, markdown, эмодзи и ссылок — ответ будет озвучен голосом. " +
        "Цены, тексты, вывески, меню переводи и пересказывай по сути. Помни предыдущие реплики разговора."
  }

  private val visualWords =
      listOf("посмотри", "глянь", "видишь", "что это", "что тут", "что здесь", "передо мной", "прочитай",
          "прочти", "переведи", "сколько стоит", "какой это", "какая это", "что написано", "на фото", "в кадре")
  private val stopWords = listOf("стоп", "хватит", "спасибо", "всё", "все", "отбой", "пока")

  private fun has(text: String, vararg words: String) = words.any { text.contains(it) }

  /** «Спросить»: слушаем вопрос + кадр с очков → ответ голосом → (диалог) слушаем следующий вопрос. */
  fun ask() {
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
            if (System.currentTimeMillis() - lastTalk > 10 * 60_000) history.clear()
            var round = 0
            while (isActive) {
              var photo: Bitmap? = null
              var photoError: String? = null
              val question: String

              if (round == 0) {
                // Сначала запускаем камеру, микрофон очков включаем, когда видео пошло
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
                r.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                photo = r.getOrNull()
                photoError = r.exceptionOrNull()?.message
              } else {
                question = listenQuestion()
                if (question.isBlank()) break
                val lower = question.lowercase().trim().trimEnd('.', '!')
                if (stopWords.any { lower == it }) break
                if (visualWords.any { lower.contains(it) }) {
                  val r = runCatching { Glasses.capture(onStep = { status.value = "Камера: $it…" }) }
                  r.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                  photo = r.getOrNull()
                  photoError = r.exceptionOrNull()?.message
                }
              }
              if (photo != null) lastPhoto.value = photo
              if (photoError != null) addLog("⚠", "Камера: $photoError")

              val lower = question.lowercase()
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
                doFullPhoto()
                break
              }
              if (has(lower, "включи гид", "режим гида", "запусти гид")) {
                after = { startGuide() }
                break
              }
              if (has(lower, "выключи гид", "останови гид")) {
                stopGuide()
                Voice.speak("Гид выключен", Prefs.myLang)
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

              val wantsSave = lower.startsWith("сохрани") && photo != null
              val q =
                  if (wantsSave) "Опиши в 1–2 предложениях, что на фото, чтобы я это запомнил."
                  else question.ifBlank { "Что сейчас передо мной? Опиши коротко самое важное." }
              addLog("Я", q)
              status.value = "Думаю…"
              val answer = AiClient.ask(assistantPrompt(photo != null), q, photo, history = history.toList())
              addLog("ИИ", answer)
              history.add(AiClient.Turn(q, answer))
              while (history.size > 8) history.removeAt(0)
              lastTalk = System.currentTimeMillis()

              if (wantsSave && photo != null) {
                saveFind(photo, answer)
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
            val msg = e.message ?: "Неизвестная ошибка"
            addLog("⚠", msg)
            runCatching { Voice.speak("Ошибка. Подробности на экране телефона.", "ru-RU") }
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

  // ================= заметки, находки, фото =================

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

  private suspend fun doFullPhoto() {
    val bytes = Glasses.captureFull { status.value = "Фото: $it…" }
    val uri = withContext(Dispatchers.IO) { Storage.saveImageBytes(bytes, "Фото_${Storage.stamp()}") }
    lastPhoto.value = withContext(Dispatchers.Default) { Glasses.decodeBytes(bytes, 1600) }
    val info = "Фото в полном качестве, ${bytes.size / 1024} КБ"
    Library.add("Фото", info, uri)
    addLog("📷", "$info — сохранено в галерею, папка GlassAI")
    Voice.speak("Фото сохранено", Prefs.myLang)
  }

  /** Фото в полном качестве в галерею. */
  fun fullPhoto() = simpleJob("Фото") { doFullPhoto() }

  /** Проверка камеры без микрофона и ИИ. */
  fun testCamera() =
      simpleJob("Камера") {
        val bmp = Glasses.capture(onStep = { status.value = "Камера: $it…" })
        lastPhoto.value = bmp
        addLog("📷", "Кадр получен: ${bmp.width}×${bmp.height}. Камера работает ✅")
      }

  /** Сохранить то, что сейчас вижу, с описанием от ИИ. */
  fun saveView() =
      simpleJob("Сохранение") {
        val bmp = Glasses.capture(onStep = { status.value = "Камера: $it…" })
        lastPhoto.value = bmp
        status.value = "Описываю…"
        val text =
            AiClient.ask(
                "Опиши на языке «${Langs.name(Prefs.myLang)}» в 1–2 предложениях, что на фото: место, " +
                    "предмет, текст вывесок и цены, если есть. Без вступлений.",
                "Что на фото?",
                bmp,
                200,
            )
        saveFind(bmp, text)
        addLog("⭐", text)
        Voice.speak("Сохранил. $text", Prefs.myLang)
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

  // ================= режим гида =================

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
    if (guideOn.value) return
    if (Recorder.recording) {
      addLog("⚠", "Идёт запись диктофона. Гид недоступен.")
      return
    }
    guideOn.value = true
    guideSaid.clear()
    addLog("🧭", "Режим гида включён: смотрю вокруг раз в ${Prefs.guideSec} с")
    guideJob =
        scope.launch {
          var fails = 0
          try {
            if (!busy.value) Voice.speak("Режим гида включён", Prefs.myLang)
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
                  if (fails >= 4) {
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
            // сначала сохраняем звук — он не пропадёт, даже если расшифровка не удастся
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
                      audioAac = bytes,
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

  /** Постоянно слушаем собеседника и шепчем перевод в очки. */
  fun startTranslator() {
    if (translatorOn.value || Recorder.recording) return
    job?.cancel()
    translatorOn.value = true
    busy.value = true
    job =
        scope.launch {
          try {
            val glassesAudio = Voice.routeToGlasses()
            addLog(
                "ℹ",
                "Переводчик: ${Langs.name(Prefs.theirLang)} → ${Langs.name(Prefs.myLang)}" +
                    if (glassesAudio) " (микрофон очков)" else " (микрофон телефона)",
            )
            var misses = 0
            while (isActive && translatorOn.value) {
              status.value = "Слушаю собеседника (${Langs.name(Prefs.theirLang)})…"
              val heard = Voice.listen(Prefs.theirLang, silenceMs = 1200)
              if (heard == null) {
                misses++
                delay(if (misses > 5) 800 else 250)
                continue
              }
              misses = 0
              addLog("Он", heard)
              status.value = "Перевожу…"
              try {
                val tr = AiClient.translate(heard, Prefs.theirLang, Prefs.myLang)
                addLog("→", tr)
                Voice.speak(tr, Prefs.myLang)
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                addLog("⚠", e.message ?: "Ошибка перевода")
                delay(1500)
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
          }
        }
  }

  /** «Мой ответ»: говорю по-своему, перевод звучит громко из телефона и крупно на экране. */
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
            val mine = Voice.listen(Prefs.myLang)
            if (mine == null) {
              status.value = "Не расслышал"
            } else {
              addLog("Я", mine)
              status.value = "Перевожу…"
              val tr = AiClient.translate(mine, Prefs.myLang, Prefs.theirLang)
              addLog("←", tr)
              bigText.value = tr
              Voice.releaseRoute()
              Voice.routeToPhoneSpeaker()
              status.value = "Говорю перевод через телефон…"
              Voice.speak(tr, Prefs.theirLang)
            }
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

  /** Останавливает помощника, переводчик и гида. Диктофон останавливается своей кнопкой. */
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
  }
}
