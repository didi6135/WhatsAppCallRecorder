package com.didi4164.WhatsAppCallRecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Completion-only, local notifications. No account, folder, call name or remote URI is exposed. */
object DriveBackupNotifications {
  private const val CHANNEL = "wa_reco_drive_backups"
  private const val NOTIFICATION_ID = 4170

  fun uploaded(context: Context, destination: String, recordingId: String) {
    // Notifications are optional. Never request permissions or open Settings from a background worker.
    try {
      if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED) return
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
      if (!manager.areNotificationsEnabled()) return
      if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
          AppText.choose("גיבוי ל־Google Drive", "Google Drive backups"), NotificationManager.IMPORTANCE_DEFAULT)
          .apply {
            description = AppText.choose("התראה לאחר שהקלטה עלתה בהצלחה ל־Google Drive",
              "A notification when a recording successfully uploads to Google Drive")
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
          })
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
      }
      val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, MainActivity::class.java)
      launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      val content = PendingIntent.getActivity(context, NOTIFICATION_ID, launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val title = AppText.choose("ההקלטה גובתה בהצלחה", "Recording backed up")
      val text = AppText.choose("ההקלטה עלתה ל־Google Drive. אפשר להמשיך כרגיל.",
        "Your recording was uploaded to Google Drive.")
      val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
      builder.setSmallIcon(R.drawable.ic_backup_complete).setContentTitle(title).setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(content)
        .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(true)
      if (Build.VERSION.SDK_INT < 26) builder.setDefaults(Notification.DEFAULT_ALL)
      if (Build.VERSION.SDK_INT >= 29) builder.setAllowSystemGeneratedContextualActions(false)
      // Stable opaque tag separates completed uploads without disclosing destination/filename on the lock screen.
      val identity = MessageDigest.getInstance("SHA-256").digest("$destination/$recordingId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
      manager.notify("drive-upload-$identity", NOTIFICATION_ID, builder.build())
    } catch (_: Exception) { /* Permission/channel/process races never invalidate a durable successful backup. */ }
  }
}
