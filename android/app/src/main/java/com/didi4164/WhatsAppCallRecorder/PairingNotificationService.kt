package com.didi4164.WhatsAppCallRecorder

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the two-minute, explicitly requested Settings flow alive. This service never records audio. */
class PairingNotificationService : Service() {
  private var sessionId: String? = null
  private var latestStartId = 0

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val incomingId = intent?.getStringExtra(PairingNotificationController.EXTRA_SESSION)
    if (!PairingNotificationController.acceptsSession(incomingId)) {
      // An old queued intent must not replace the owner of a newer discovery session.
      if (PairingNotificationController.acceptsSession(sessionId)) {
        latestStartId = startId
        return START_NOT_STICKY
      }
      if (sessionId == null) {
        startForeground(PairingNotificationController.NOTIFICATION_ID,
          PairingNotificationController.foregroundNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        stopForeground(STOP_FOREGROUND_REMOVE)
      }
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    latestStartId = startId
    sessionId = incomingId
    startForeground(PairingNotificationController.NOTIFICATION_ID,
      PairingNotificationController.foregroundNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
    PairingNotificationController.serviceStarted(this, intent?.action, sessionId,
      intent?.getStringExtra(PairingNotificationController.EXTRA_CODE))
    return START_NOT_STICKY
  }

  internal fun finishFor(id: String?) {
    if (id != sessionId) return
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelfResult(latestStartId)
  }

  override fun onTimeout(startId: Int) {
    PairingNotificationController.serviceTimedOut(this, sessionId)
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    PairingNotificationController.serviceTimedOut(this, sessionId)
  }

  override fun onDestroy() {
    PairingNotificationController.serviceDestroyed(this, sessionId)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
