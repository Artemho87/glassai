package com.artem.glassai

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Логика двух режимов: «ИИ-помощник со зрением» и «ИИ-переводчик». */
object Brain {

  data class Line(val who: String, val text: String)

  private val scope = MainScope()
  private var job: Job? = null
  @Volatile private var asking = false

  val status = MutableStateFlow("Готов")
  val busy = MutableStateFlow(false)
  val translatorOn = MutableStateFlow(false)
  val lastPhoto = MutableStateFlow<Bitmap?>(null)
  /** Последний перевод крупно — чтобы показать экран собеседнику */
  val bigText = MutableStateFlow("")
  val log = MutableStateFlow<List<Line>>(emptyList())

  fun init(context: Context) {
    Prefs.init(context)
    Voice.init(context)
  }

  private fun addLog(who: String, text: String) {
    log.value = (listOf(Line(who, text)) + log.value).take(40)
  }

  private fun assistantPrompt(hasPhoto: Boolean): String {
    val lang = Langs.name(Prefs.myLang)
    val photoPart =
        if (hasPhoto)
            "К вопросу приложено фото — это то, что пользователь видит прямо сейчас через камеру очков " +
                "(вид от первого лица). Опирайся на него."
        else
            "Фото с очков сейчас недоступно. Если вопрос требует зрения, коротко скажи, что камера " +
                "не подключилась, иначе просто ответь."
    return "Ты голосовой помощник в умных очках Ray-Ban Meta. $photoPart " +
        "Отвечай на языке «$lang», кратко: 1–3 предложения, разговорно. " +
        "Без списков, markdown, эмодзи и ссылок — ответ будет озвучен голосом. " +
        "Цены, тексты, вывески, меню переводи и пересказывай по сути."
  }

  // ================= ИИ-помощник =================

  /** Нажал «Спросить»: слушаем вопрос, одновременно снимаем кадр, отправляем ИИ, озвучиваем ответ. */
  fun ask() {
    if (asking) return
    if (busy.value && !translatorOn.value) return
    asking = true
    stopTranslatorLoop()
    job =
        scope.launch {
          while (busy.value) delay(50) // ждём, пока переводчик отпустит микрофон
          busy.value = true
          try {
            val glassesAudio = Voice.routeToGlasses()
            Voice.beep()
            status.value = if (glassesAudio) "Слушаю (микрофон очков)…" else "Слушаю (микрофон телефона)…"

            val photoTask = async { runCatching { Glasses.capture() } }
            val question = Voice.listen(Prefs.myLang) ?: ""

            status.value = "Снимаю кадр…"
            val photoResult = photoTask.await()
            val photo = photoResult.getOrNull()
            if (photo != null) lastPhoto.value = photo
            photoResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val photoError = photoResult.exceptionOrNull()?.message

            val q = question.ifBlank { "Что сейчас передо мной? Опиши коротко самое важное." }
            addLog("Я", q)
            if (photoError != null) addLog("⚠", photoError)

            status.value = "Думаю…"
            val answer = AiClient.ask(assistantPrompt(photo != null), q, photo)
            addLog("ИИ", answer)

            status.value = "Отвечаю…"
            Voice.speak(answer, Prefs.myLang)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            val msg = e.message ?: "Неизвестная ошибка"
            addLog("⚠", msg)
            status.value = "Ошибка: $msg"
            runCatching { Voice.speak("Ошибка. Подробности на экране телефона.", "ru-RU") }
          } finally {
            Voice.releaseRoute()
            busy.value = false
            asking = false
            if (status.value.startsWith("Ошибка").not()) status.value = "Готов"
          }
        }
  }

  // ================= ИИ-переводчик =================

  /** Постоянно слушаем собеседника и шепчем перевод в очки. */
  fun startTranslator() {
    if (translatorOn.value) return
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
    val resumeTranslator = translatorOn.value
    stopTranslatorLoop()
    job =
        scope.launch {
          // ждём, пока цикл переводчика освободит микрофон
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

  fun stopAll() {
    translatorOn.value = false
    job?.cancel()
    Voice.stopSpeaking()
    Voice.releaseRoute()
    busy.value = false
    asking = false
    status.value = "Готов"
  }

  fun clearLog() {
    log.value = emptyList()
    bigText.value = ""
  }
}
