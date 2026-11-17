package com.artem.glassai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.launch

/**
 * Постоянное уведомление с кнопками «Спросить», «Переводчик», «Стоп».
 * Работает с экрана блокировки и когда приложение свёрнуто.
 */
class AssistantService : Service() {

  companion object {
    private const val CHANNEL = "glassai"
    private const val NOTIF_ID = 7
    const val ACTION_ASK = "com.artem.glassai.ASK"
    const val ACTION_TRANSLATE = "com.artem.glassai.TRANSLATE"
    const val ACTION_REPLY = "com.artem.glassai.REPLY"
    const val ACTION_STOP = "com.artem.glassai.STOP"
    const val ACTION_PHOTO = "com.artem.glassai.PHOTO"
    const val ACTION_EXIT = "com.artem.glassai.EXIT"

    fun start(context: Context) {
      context.startForegroundService(Intent(context, AssistantService::class.java))
    }
  }

  private val scope = kotlinx.coroutines.MainScope()
  private var wake: android.os.PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    runCatching { wake?.let { if (it.isHeld) it.release() } }
    super.onDestroy()
  }

  /** Пока помощник что-то делает, не даём телефону уснуть (работа с заблокированным экраном). */
  private fun watchActivity() {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    val lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GlassAI::Active")
    lock.setReferenceCounted(false)
    wake = lock
    scope.launch {
      kotlinx.coroutines.flow.combine(
              Brain.busy,
              Brain.guideOn,
              Brain.translatorOn,
              Glasses.controlsActive,
          ) { a, b, c, d -> a || b || c || d }
          .collect { active ->
            runCatching { if (active) lock.acquire(3 * 60 * 60 * 1000L) else if (lock.isHeld) lock.release() }
          }
    }
  }

  override fun onCreate() {
    super.onCreate()
    Brain.init(this)
    Glasses.init(this)
    Brain.startBackground()
    watchActivity()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Очки ИИ", NotificationManager.IMPORTANCE_LOW).apply {
          setShowBadge(false)
        },
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      startForeground(
          NOTIF_ID,
          buildNotification(),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
              ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
    } catch (e: Exception) {
      stopSelf()
      return START_NOT_STICKY
    }
    when (intent?.action) {
      ACTION_ASK ->
          when {
            Brain.recordingOn.value -> Brain.stopRecording()
            Brain.busy.value && !Brain.translatorOn.value -> Brain.stopAll()
            else -> Brain.ask()
          }
      ACTION_PHOTO -> Brain.analyzeLatest()
      ACTION_TRANSLATE -> if (Brain.translatorOn.value) Brain.stopAll() else Brain.startTranslator()
      ACTION_REPLY -> Brain.replyMine()
      ACTION_STOP -> Brain.stopAll()
      ACTION_EXIT -> {
        Brain.stopAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
    }
    return START_STICKY
  }

  private fun action(act: String, req: Int): PendingIntent =
      PendingIntent.getService(
          this,
          req,
          Intent(this, AssistantService::class.java).setAction(act),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  private fun buildNotification(): Notification {
    val open =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif)
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle("Очки ИИ готовы")
        .setContentText("Спросить (ещё раз — стоп) · Фото — разбор последнего снимка · Переводчик — вкл/выкл")
        .setContentIntent(open)
        .setOngoing(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(Notification.Action.Builder(icon, "🎤 Спросить", action(ACTION_ASK, 1)).build())
        .addAction(Notification.Action.Builder(icon, "🖼 Фото", action(ACTION_PHOTO, 4)).build())
        .addAction(Notification.Action.Builder(icon, "🌐 Переводчик", action(ACTION_TRANSLATE, 2)).build())

        .build()
  }
}
