package com.didi4164.WhatsAppCallRecorder

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.FileDescriptor
import java.io.PrintWriter

/** Call structure is always separate from optional, explicitly enabled display-name collection. */
class WhatsAppCallNotificationService : NotificationListenerService() {
  @Volatile private var connected = false

  companion object {
    @Volatile private var liveListener: WhatsAppCallNotificationService? = null

    fun nameForAcceptedStart(
      context: Context,
      expectedUid: Int? = null,
      acceptedSignals: Collection<AutoCallPolicy.NotificationSignal>? = null,
    ): CallNameIdentity? {
      // No remembered label survives an ended call, listener disconnect, or opt-out.
      if (!CallNamePreferences.isEnabled(context) || !AutoRecordingController.hasNotificationAccess(context)) return null
      val listener = liveListener?.takeIf { it.connected } ?: return null
      return runCatching { listener.queryLiveName(expectedUid, acceptedSignals) }.getOrNull()
    }
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    connected = true
    liveListener = this
    reconcile()
  }

  override fun onListenerDisconnected() {
    connected = false
    if (liveListener === this) liveListener = null
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

  private fun queryLiveName(
    expectedUid: Int?,
    acceptedSignals: Collection<AutoCallPolicy.NotificationSignal>?,
  ): CallNameIdentity? {
    if (!connected) return null
    val live = activeNotifications.orEmpty()
    val signals = live.mapNotNull { runCatching { structuralSignal(it) }.getOrNull() }
    val sample = UsbAudioBridge.callAudioSnapshot()
    val audio = sample?.let {
      AutoCallPolicy.AudioObservation(it.mode, it.ownerUid, it.known, it.receivedAtMs,
        it.telecom?.let { proof -> AutoCallPolicy.TelecomObservation(
          proof.known, proof.packageCode, proof.userId, proof.liveCalls, proof.activeCalls,
          proof.foregroundMatched, proof.selfManaged, proof.voip, proof.receivedAtMs,
        ) })
    }
    val signal = CallNotificationIdentity.selectLiveCall(SystemClock.elapsedRealtime(), signals, audio,
      expectedUid, acceptedSignals) ?: return null
    val notification = live.singleOrNull { it.packageName == signal.packageName && it.uid == signal.uid &&
      it.key == signal.key }?.notification ?: return null
    // These fields are not read during reconciliation, for messages, or while opt-in is disabled.
    if (!connected || liveListener !== this || !CallNamePreferences.isEnabled(this)) return null
    val name = CallNotificationIdentity.readDisplayName(CallNamePreferences.isEnabled(this), signal) {
      val extras = notification.extras ?: Bundle.EMPTY
      @Suppress("DEPRECATION")
      val people = extras.getParcelableArrayList<Parcelable>("android.people.list").orEmpty()
      if (people.size > 1) return@readDisplayName CallNotificationIdentity.NameFields(null, listOf(null, null), null)
      @Suppress("DEPRECATION")
      val callPerson = extras.getParcelable<Parcelable>("android.callPerson")
        ?: extras.getBundle("android.callPersonCompat")
      CallNotificationIdentity.NameFields(personName(callPerson), people.map(::personName),
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    } ?: return null
    return CallNameIdentity(name, signal.packageName)
  }

  private fun personName(person: Parcelable?): String? = when {
    // AndroidX CallStyle uses android.callPersonCompat / Bundle before Android 28.
    person is Bundle -> person.getCharSequence("name")?.toString()
    Build.VERSION.SDK_INT >= 28 && person is android.app.Person -> person.name?.toString()
    else -> null
  }

  protected override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
    AutoRecordingController.writeDiagnostics(writer)
  }

  override fun onDestroy() {
    connected = false
    if (liveListener === this) liveListener = null
    AutoRecordingController.onListenerSnapshot(applicationContext, false, emptyList())
    super.onDestroy()
  }
}
