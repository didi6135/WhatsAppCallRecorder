package com.didi4164.WhatsAppCallRecorder

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.FileDescriptor
import java.io.PrintWriter

/** Reads only allowlisted call structure. Names, messages, persons, and action labels are never read. */
class WhatsAppCallNotificationService : NotificationListenerService() {
  private var connected = false

  override fun onListenerConnected() {
    super.onListenerConnected()
    connected = true
    reconcile()
  }

  override fun onListenerDisconnected() {
    connected = false
    AutoRecordingController.onListenerSnapshot(applicationContext, false, emptyList())
    super.onListenerDisconnected()
    requestRebindIfAllowed()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn != null && AutoCallPolicy.allowsPackage(sbn.packageName)) reconcile()
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    if (sbn != null && AutoCallPolicy.allowsPackage(sbn.packageName)) reconcile()
  }

  private fun reconcile() {
    if (!connected) return
    try {
      val signals = activeNotifications.orEmpty().mapNotNull { sbn -> runCatching { structuralSignal(sbn) }.getOrNull() }
      AutoRecordingController.onListenerSnapshot(applicationContext, true, signals)
    } catch (_: Exception) {
      // A failed reconciliation cannot leave an earlier notification authorizing capture.
      connected = false
      AutoRecordingController.onListenerSnapshot(applicationContext, false, emptyList())
      requestRebindIfAllowed()
    }
  }

  private fun requestRebindIfAllowed() {
    if (AutoRecordingController.hasNotificationAccess(applicationContext))
      runCatching { requestRebind(ComponentName(this, WhatsAppCallNotificationService::class.java)) }
  }

  private fun structuralSignal(sbn: StatusBarNotification): AutoCallPolicy.NotificationSignal? {
    if (!AutoCallPolicy.allowsPackage(sbn.packageName)) return null
    val notification = sbn.notification
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    val extras = notification.extras
    return AutoCallPolicy.NotificationSignal(
      packageName = sbn.packageName,
      uid = sbn.uid,
      key = sbn.key,
      category = notification.category,
      ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
      callType = extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) ?: 0,
      hasAnswer = NotificationActionPresence.hasPendingIntent(extras, Notification.EXTRA_ANSWER_INTENT),
      hasHangup = NotificationActionPresence.hasPendingIntent(extras, Notification.EXTRA_HANG_UP_INTENT),
      showChronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true,
      chronometerCountDown = extras?.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false) == true,
      actionCount = notification.actions?.size ?: 0,
      hasFullScreenIntent = notification.fullScreenIntent != null,
    )
  }

  protected override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
    AutoRecordingController.writeDiagnostics(writer)
  }

  override fun onDestroy() {
    connected = false
    AutoRecordingController.onListenerSnapshot(applicationContext, false, emptyList())
    super.onDestroy()
  }
}
