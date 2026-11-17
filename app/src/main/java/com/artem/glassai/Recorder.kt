package com.artem.glassai

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File

/**
 * Диктофон встреч. Пишет звук кусками по 8 минут (так их можно отправлять ИИ на расшифровку).
 * Формат AAC, 16 кГц, моно — час записи занимает около 14 МБ.
 */
object Recorder {
  private const val SEGMENT_MS = 8 * 60 * 1000
  private lateinit var ctx: Context
  private var rec: MediaRecorder? = null
  private var wake: PowerManager.WakeLock? = null
  private val main = Handler(Looper.getMainLooper())
  private var session = ""
  private var fromGlasses = false

  val segments = mutableListOf<File>()
  @Volatile var recording = false
    private set
  var startedAt = 0L
    private set

  fun init(context: Context) {
    ctx = context.applicationContext
  }

  fun start(useGlassesMic: Boolean) {
    if (recording) return
    session = Storage.stamp()
    fromGlasses = useGlassesMic
    segments.clear()
    File(ctx.cacheDir, "rec").apply { mkdirs() }.listFiles()?.forEach { it.delete() }
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassAI::Recorder").apply { acquire(4 * 60 * 60 * 1000L) }
    recording = true
    startedAt = System.currentTimeMillis()
    try {
      startSegment()
    } catch (e: Exception) {
      recording = false
      releaseWake()
      throw e
    }
  }

  private fun startSegment() {
    val f = File(File(ctx.cacheDir, "rec"), "${session}_${segments.size + 1}.aac")
    val r = MediaRecorder(ctx)
    r.setAudioSource(
        if (fromGlasses) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
    )
    r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    r.setAudioSamplingRate(16000)
    r.setAudioChannels(1)
    r.setAudioEncodingBitRate(32000)
    r.setMaxDuration(SEGMENT_MS)
    r.setOutputFile(f.absolutePath)
    r.setOnInfoListener { _, what, _ ->
      if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
        main.post {
          if (recording) {
            closeSegment()
            runCatching { startSegment() }
          }
        }
      }
    }
    r.prepare()
    r.start()
    rec = r
    segments.add(f)
  }

  private fun closeSegment() {
    val r = rec ?: return
    rec = null
    runCatching { r.stop() }
    runCatching { r.release() }
  }

  /** Останавливает запись и возвращает куски. */
  fun stop(): List<File> {
    if (!recording) return emptyList()
    recording = false
    closeSegment()
    releaseWake()
    return segments.filter { it.exists() && it.length() > 2000 }
  }

  private fun releaseWake() {
    runCatching { wake?.let { if (it.isHeld) it.release() } }
    wake = null
  }

  val sessionName: String
    get() = session
}
