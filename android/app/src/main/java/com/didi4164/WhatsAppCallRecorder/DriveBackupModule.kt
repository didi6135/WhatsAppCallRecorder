package com.didi4164.WhatsAppCallRecorder

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity

class DriveBackupModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object { private const val PICKER_REQUEST = 48101 }
  private data class Pending(val promise: Promise, val original: DriveBackupQueue.Config, val changeFolder: Boolean)
  @Volatile private var pending: Pending? = null
  private val listener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      if (requestCode != PICKER_REQUEST) return
      val action = pending ?: return
      if (resultCode != Activity.RESULT_OK || data == null) { pending = null; resolve(action.promise); return }
      try { accept(action, Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)) }
      catch (failure: Exception) { pending = null; reject(action.promise, DriveBackupManager.authorizationFailure(failure).code) }
    }
  }
  init { context.addActivityEventListener(listener) }
  override fun getName() = "DriveBackup"
  override fun invalidate() {
    context.removeActivityEventListener(listener)
    pending?.promise?.let { reject(it, "FOREGROUND_REQUIRED") }; pending = null
    super.invalidate()
  }
  private fun resolve(promise: Promise) {
    try { promise.resolve(Arguments.makeNativeMap(DriveBackupQueue.status(context))) }
    catch (_: Exception) { reject(promise, "LOCAL_QUEUE_UNAVAILABLE") }
  }
  private fun reject(promise: Promise, code: String) { promise.reject(code, DriveBackupErrors.message(code)) }
  private fun isForeground(activity: Activity?): Boolean = activity != null && currentActivity === activity &&
    !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
  @ReactMethod fun getStatus(promise: Promise) { resolve(promise) }
  @ReactMethod fun connect(promise: Promise) { choose(promise, false) }
  @ReactMethod fun chooseFolder(promise: Promise) { choose(promise, true) }
  private fun choose(promise: Promise, changeFolder: Boolean) {
    val activity = currentActivity
    if (activity == null || activity.isFinishing) { reject(promise, "FOREGROUND_REQUIRED"); return }
    activity.runOnUiThread {
      if (!isForeground(activity)) { reject(promise, "FOREGROUND_REQUIRED"); return@runOnUiThread }
      if (RecordingService.isBusy()) { reject(promise, "RECORDING_BUSY"); return@runOnUiThread }
      if (pending != null) { reject(promise, "CONNECTION_BUSY"); return@runOnUiThread }
      val original = DriveBackupQueue.config(context)
      if (changeFolder && !original.connected) { reject(promise, "NOT_CONNECTED"); return@runOnUiThread }
      val action = Pending(promise, original, changeFolder); pending = action
      try { Identity.getAuthorizationClient(context).authorize(DriveBackupManager.pickerRequest(original, changeFolder))
        .addOnSuccessListener { result ->
          if (pending !== action) return@addOnSuccessListener
          if (!isForeground(activity)) { pending = null; reject(promise, "FOREGROUND_REQUIRED"); return@addOnSuccessListener }
          if (result.hasResolution()) {
            try {
              if (!isForeground(activity)) throw DriveBackupFailure("FOREGROUND_REQUIRED")
              if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
              activity.startIntentSenderForResult(result.pendingIntent!!.intentSender, PICKER_REQUEST, null, 0, 0, 0)
            } catch (failure: Exception) { pending = null; reject(promise, (failure as? DriveBackupFailure)?.code ?: "FOREGROUND_REQUIRED") }
          } else accept(action, result)
        }.addOnFailureListener { failure ->
          if (pending === action) { pending = null; reject(promise, DriveBackupManager.authorizationFailure(failure).code) }
        }
      } catch (failure: Exception) { pending = null; reject(promise, DriveBackupManager.authorizationFailure(failure).code) }
    }
  }
  private fun accept(action: Pending, result: AuthorizationResult) {
    DriveBackupManager.io.execute {
      try {
        if (pending !== action) return@execute
        DriveBackupManager.acceptSelection(context, result, action.original, action.changeFolder)
        pending = null; resolve(action.promise)
      } catch (failure: Exception) {
        pending = null; reject(action.promise, (failure as? DriveBackupFailure)?.code ?: "UPLOAD_FAILED")
      }
    }
  }
  private fun run(promise: Promise, foreground: Boolean = false, action: () -> Unit) {
    if (pending != null) { reject(promise, "CONNECTION_BUSY"); return }
    val activity = currentActivity
    if (foreground && !isForeground(activity)) { reject(promise, "FOREGROUND_REQUIRED"); return }
    DriveBackupManager.io.execute {
      try {
        if (foreground && !isForeground(activity)) throw DriveBackupFailure("FOREGROUND_REQUIRED")
        if (pending != null) throw DriveBackupFailure("CONNECTION_BUSY")
        action(); resolve(promise)
      }
      catch (failure: Exception) { reject(promise, (failure as? DriveBackupFailure)?.code ?: if (failure.message == "LOCAL_QUEUE_UNAVAILABLE") "LOCAL_QUEUE_UNAVAILABLE" else "UPLOAD_FAILED") }
    }
  }
  @ReactMethod fun setEnabled(enabled: Boolean, promise: Promise) = run(promise, foreground = enabled) { DriveBackupManager.setEnabled(context, enabled) }
  @ReactMethod fun retryPending(promise: Promise) = run(promise) { DriveBackupManager.retry(context) }
  @ReactMethod fun disconnect(promise: Promise) = run(promise, foreground = true) { DriveBackupManager.disconnect(context) }
}
