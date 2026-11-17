package com.artem.glassai

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Чтение уведомлений вслух в очки. Работает только если включено в приложении и разрешено в Android. */
class NotifReader : NotificationListenerService() {
  private val lastSpoken = HashMap<String, Long>()

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return
    runCatching {
      Brain.init(this)
      if (!Prefs.readNotifs) return
      if (sbn.packageName == packageName) return
      if (sbn.isOngoing) return
      val n = sbn.notification ?: return
      if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
      val allowed =
          n.category == Notification.CATEGORY_MESSAGE ||
              n.category == Notification.CATEGORY_EMAIL ||
              n.category == Notification.CATEGORY_CALL ||
              n.category == Notification.CATEGORY_EVENT ||
              n.category == Notification.CATEGORY_REMINDER
      if (!allowed) return

      val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
      if (title.isBlank() && text.isBlank()) return

      val now = System.currentTimeMillis()
      val key = sbn.packageName + "|" + title + "|" + text
      val prev = lastSpoken[key]
      if (prev != null && now - prev < 60_000) return
      if (lastSpoken.size > 200) lastSpoken.clear()
      lastSpoken[key] = now

      val app =
          runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
              }
              .getOrDefault("")
      Brain.announce("$app. $title. ${text.take(220)}")
    }
  }
}
