package com.artem.glassai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Микрофон и голос.
 * - Слушаем через микрофон очков (Bluetooth-гарнитура), если не вышло — через микрофон телефона.
 * - Говорим в динамики очков, а «мой ответ» в переводчике — громко через динамик телефона.
 */
object Voice {
  private lateinit var ctx: Context
  private var tts: TextToSpeech? = null
  @Volatile private var ttsReady = false
  private val main = Handler(Looper.getMainLooper())

  private val am: AudioManager
    get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  enum class Route { GLASSES, PHONE_SPEAKER, DEFAULT }

  @Volatile var route = Route.DEFAULT
    private set

  fun init(context: Context) {
    ctx = context.applicationContext
    if (tts == null) {
      tts = TextToSpeech(ctx) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }
  }

  // ---------- маршрут звука ----------

  /** Включает микрофон и динамик очков как гарнитуру (как при звонке). */
  suspend fun routeToGlasses(): Boolean {
    val sco =
        runCatching {
              am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
              }
            }
            .getOrNull() ?: return false
    return try {
      am.mode = AudioManager.MODE_IN_COMMUNICATION
      val ok = am.setCommunicationDevice(sco)
      val routed =
          ok &&
              withTimeoutOrNull(3_000) {
                while (am.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) delay(100)
                true
              } == true
      if (routed) route = Route.GLASSES else releaseRoute()
      routed
    } catch (e: Exception) {
      releaseRoute()
      false
    }
  }

  /** Громкий динамик телефона — чтобы собеседник услышал перевод. */
  fun routeToPhoneSpeaker(): Boolean {
    val spk =
        runCatching {
              am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
              }
            }
            .getOrNull() ?: return false
    return try {
      am.mode = AudioManager.MODE_IN_COMMUNICATION
      val ok = am.setCommunicationDevice(spk)
      route = if (ok) Route.PHONE_SPEAKER else Route.DEFAULT
      ok
    } catch (e: Exception) {
      false
    }
  }

  fun releaseRoute() {
    runCatching { am.clearCommunicationDevice() }
    runCatching { am.mode = AudioManager.MODE_NORMAL }
    route = Route.DEFAULT
  }

  // ---------- сигнал ----------

  fun beep() {
    runCatching {
      val stream =
          if (route == Route.DEFAULT) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
      val tg = ToneGenerator(stream, 80)
      tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
      main.postDelayed({ tg.release() }, 400)
    }
  }

  // ---------- распознавание речи ----------

  /** Слушает одну фразу. Возвращает текст или null (тишина/ошибка). */
  suspend fun listen(langCode: String, silenceMs: Long = 1500): String? =
      withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
          throw IllegalStateException("На телефоне нет распознавания речи: установи приложение Google")
        }
        suspendCancellableCoroutine<String?> { cont ->
          val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
          var done = false
          fun finish(text: String?) {
            if (done) return
            done = true
            main.post { runCatching { sr.destroy() } }
            if (cont.isActive) cont.resume(text)
          }
          sr.setRecognitionListener(
              object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) = finish(null)

                override fun onResults(results: Bundle?) {
                  val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  finish(list?.firstOrNull()?.takeIf { it.isNotBlank() })
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
              },
          )
          val intent =
              Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    silenceMs,
                )
              }
          cont.invokeOnCancellation {
            main.post {
              runCatching { sr.cancel() }
              runCatching { sr.destroy() }
            }
          }
          sr.startListening(intent)
        }
      }

  // ---------- озвучка ----------

  private val speakLock = kotlinx.coroutines.sync.Mutex()

  suspend fun speak(text: String, langCode: String) {
    if (text.isBlank()) return
    speakLock.lock()
    try {
      speakNow(text, langCode)
    } finally {
      speakLock.unlock()
    }
  }

  private suspend fun speakNow(text: String, langCode: String) {
    // ждём, пока движок озвучки проснётся (первый запуск)
    withTimeoutOrNull(3_000) { while (!ttsReady) delay(100) }
    val engine = tts ?: return
    if (!ttsReady) return

    val usage =
        if (route == Route.DEFAULT) AudioAttributes.USAGE_ASSISTANT
        else AudioAttributes.USAGE_VOICE_COMMUNICATION
    engine.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    engine.setLanguage(Locale.forLanguageTag(langCode))

    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine<Unit> { cont ->
        val id = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) {}

              override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
              }

              @Deprecated("Deprecated in Java")
              override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
              }

              override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (cont.isActive) cont.resume(Unit)
              }

              override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
              }
            },
        )
        cont.invokeOnCancellation { engine.stop() }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
      }
    }
  }

  fun stopSpeaking() {
    runCatching { tts?.stop() }
  }
}
