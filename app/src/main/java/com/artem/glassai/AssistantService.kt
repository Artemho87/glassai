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
    const val ACTION_EXIT = "com.artem.glassai.EXIT"

    fun start(context: Context) {
      context.startForegroundService(Intent(context, AssistantService::class.java))
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    Brain.init(this)
    Glasses.init(this)
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
      ACTION_ASK -> Brain.ask()
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
        .setContentText("Спросить — вопрос + кадр с очков. Переводчик — вкл/выкл.")
        .setContentIntent(open)
        .setOngoing(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .addAction(Notification.Action.Builder(icon, "🎤 Спросить", action(ACTION_ASK, 1)).build())
        .addAction(Notification.Action.Builder(icon, "🌐 Переводчик", action(ACTION_TRANSLATE, 2)).build())
        .addAction(Notification.Action.Builder(icon, "■ Стоп", action(ACTION_STOP, 3)).build())
        .build()
  }
}
