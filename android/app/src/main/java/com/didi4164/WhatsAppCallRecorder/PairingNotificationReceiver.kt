package com.didi4164.WhatsAppCallRecorder

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest exported=false; only our session-specific notification PendingIntent can reply. */
class PairingNotificationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotificationController.EXTRA_CODE)?.toString()
    PairingNotificationController.receive(context, intent, code)
  }
}
