package com.artem.glassai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Запись одной фразы для режима «ИИ слушает сам»: ждём начала речи, заканчиваем после паузы.
 * Пишем с того микрофона, который сейчас выбран (очки, если они подключены как гарнитура).
 */
object AudioCapture {
  private const val RATE = 16000
  private const val FRAME = 320 // 20 мс

  /** Возвращает WAV (16 кГц, моно) или null, если никто не говорил. */
  suspend fun recordPhrase(waitMs: Long = 8000, silenceMs: Long = 1000, maxMs: Long = 20000): ByteArray? =
      withContext(Dispatchers.IO) {
        val minBuf =
            AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec =
            try {
              AudioRecord(
                  MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                  RATE,
                  AudioFormat.CHANNEL_IN_MONO,
                  AudioFormat.ENCODING_PCM_16BIT,
                  max(minBuf, RATE),
              )
            } catch (e: SecurityException) {
              throw IllegalStateException("Нет доступа к микрофону")
            }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
          rec.release()
          throw IllegalStateException("Микрофон занят другим приложением")
        }
        val frame = ShortArray(FRAME)
        val pre = ArrayDeque<ShortArray>()
        val out = ByteArrayOutputStream()
        var noiseSum = 0.0
        var noiseFrames = 0
        var noise = 0.0
        var speaking = false
        var loudRun = 0
        var silentMs = 0L
        var elapsed = 0L
        var voiced = 0
        try {
          rec.startRecording()
          while (isActive) {
            var n = 0
            while (n < FRAME) {
              val r = rec.read(frame, n, FRAME - n)
              if (r <= 0) throw IllegalStateException("Микрофон перестал отвечать")
              n += r
            }
            elapsed += 20
            val level = rms(frame)
            if (noiseFrames < 10) {
              noiseSum += level
              noiseFrames++
              noise = noiseSum / noiseFrames
              pre.addLast(frame.copyOf())
              continue
            }
            val threshold = max(noise * 2.5, 300.0)
            val loud = level > threshold
            if (!speaking) {
              pre.addLast(frame.copyOf())
              if (pre.size > 15) pre.removeFirst()
              if (loud) {
                loudRun++
              } else {
                loudRun = 0
                noise = noise * 0.95 + level * 0.05
              }
              if (loudRun >= 3) {
                speaking = true
                pre.forEach { writeFrame(out, it) }
                pre.clear()
              } else if (elapsed > waitMs) {
                break
              }
            } else {
              writeFrame(out, frame)
              if (loud) {
                silentMs = 0
                voiced++
              } else {
                silentMs += 20
              }
              if (silentMs >= silenceMs || out.size() >= maxMs * 32) break
            }
          }
        } finally {
          runCatching { rec.stop() }
          rec.release()
        }
        if (!speaking || voiced < 8) null else wav(out.toByteArray())
      }

  private fun rms(f: ShortArray): Double {
    var s = 0.0
    for (v in f) s += v.toDouble() * v
    return sqrt(s / f.size)
  }

  private fun writeFrame(out: ByteArrayOutputStream, f: ShortArray) {
    val b = ByteArray(f.size * 2)
    for (i in f.indices) {
      b[i * 2] = (f[i].toInt() and 0xFF).toByte()
      b[i * 2 + 1] = ((f[i].toInt() shr 8) and 0xFF).toByte()
    }
    out.write(b)
  }

  private fun wav(pcm: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(pcm.size + 44)
    fun int(v: Int) {
      out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }
    fun short(v: Int) {
      out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }
    out.write("RIFF".toByteArray())
    int(36 + pcm.size)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    int(16)
    short(1)
    short(1)
    int(RATE)
    int(RATE * 2)
    short(2)
    short(16)
    out.write("data".toByteArray())
    int(pcm.size)
    out.write(pcm)
    return out.toByteArray()
  }
}
