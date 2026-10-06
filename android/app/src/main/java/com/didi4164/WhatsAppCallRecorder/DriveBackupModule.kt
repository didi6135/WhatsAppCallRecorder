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
import com.google.android.gms.common.api.ApiException

class DriveBackupModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  companion object {
    private const val PICKER_REQUEST = 48101
    // Independent of the durable upload queue; retained across Settings/React context remounts in this process.
    private val diagnostics = DriveBackupActionDiagnostics()
  }
  private data class Pending(val promise: Promise, val original: DriveBackupQueue.Config, val changeFolder: Boolean,
    val diagnostic: DriveBackupActionDiagnostics.Ticket)
  @Volatile private var pending: Pending? = null
  private val listener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      if (requestCode != PICKER_REQUEST) return
      val action = pending ?: return
      val result = DriveAuthorizationResultGate.evaluate<AuthorizationResult>(resultCode, data != null,
        { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data!!) }, { googleStatus(it) })
      when (result.decision) {
        DriveAuthorizationResultGate.Decision.ACCEPT -> accept(action, result.value!!)
        DriveAuthorizationResultGate.Decision.CANCEL -> cancel(action)
        DriveAuthorizationResultGate.Decision.FAIL -> fail(action, "pickerResult", result.failure!!, resultCode, result.authStatusCode)
        else -> fail(action, "pickerResult", DriveBackupFailure("AUTH_REQUIRED"), resultCode)
      }
    }
  }
  init { context.addActivityEventListener(listener) }
  override fun getName() = "DriveBackup"
  override fun invalidate() {
    context.removeActivityEventListener(listener)
    pending?.let { fail(it, "authorize", DriveBackupFailure("FOREGROUND_REQUIRED")) }
    super.invalidate()
  }
  private fun resolve(promise: Promise) {
    try {
      val status = synchronized(DriveBackupQueue) {
        diagnostics.decorate(DriveBackupQueue.status(context), DriveBackupQueue.config(context).generation)
      }
      promise.resolve(Arguments.makeNativeMap(status))
    }
    catch (_: Exception) { reject(promise, "LOCAL_QUEUE_UNAVAILABLE") }
  }
  private fun reject(promise: Promise, code: String) { promise.reject(code, DriveBackupErrors.message(code)) }
  private fun googleStatus(error: Throwable): Int? {
    var cause: Throwable? = error
    repeat(16) {
      val candidate = cause ?: return null
      if (candidate is ApiException) return candidate.statusCode.takeIf { it in 0..65535 }
      cause = candidate.cause.takeIf { it !== candidate }
    }
    return null
  }
  private fun take(action: Pending): Boolean = synchronized(this) {
    if (pending !== action) false else { pending = null; true }
  }
  private fun cancel(action: Pending) {
    if (!take(action)) return
    diagnostics.clear(action.diagnostic, DriveBackupQueue.config(context).generation)
    resolve(action.promise)
  }
  private fun fail(action: Pending, stage: String, failure: Exception, resultCode: Int? = null, authStatusCode: Int? = googleStatus(failure)) {
    if (!take(action)) return
    val code = DriveBackupActionDiagnostics.safeCode((failure as? DriveBackupFailure)?.code ?: DriveBackupManager.authorizationFailure(failure).code)
    runCatching { diagnostics.fail(action.diagnostic, DriveBackupQueue.config(context).generation, stage, code, authStatusCode, resultCode) }
    reject(action.promise, code)
  }
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
      val action = Pending(promise, original, changeFolder, diagnostics.begin(original.generation, if (changeFolder) "folder" else "connect")); pending = action
      try { Identity.getAuthorizationClient(context).authorize(DriveBackupManager.pickerRequest(original, changeFolder))
        .addOnSuccessListener { result ->
          if (pending !== action) return@addOnSuccessListener
          if (!isForeground(activity)) { fail(action, "authorize", DriveBackupFailure("FOREGROUND_REQUIRED")); return@addOnSuccessListener }
          if (result.hasResolution()) {
            try {
              if (!isForeground(activity)) throw DriveBackupFailure("FOREGROUND_REQUIRED")
              if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
              activity.startIntentSenderForResult(result.pendingIntent!!.intentSender, PICKER_REQUEST, null, 0, 0, 0)
            } catch (failure: Exception) { fail(action, "authorize", (failure as? DriveBackupFailure) ?: DriveBackupFailure("FOREGROUND_REQUIRED")) }
          } else accept(action, result)
        }.addOnFailureListener { failure ->
          if (googleStatus(failure) == 16) cancel(action) else fail(action, "authorize", failure)
        }
      } catch (failure: Exception) { fail(action, "authorize", failure) }
    }
  }
  private fun accept(action: Pending, result: AuthorizationResult) {
    DriveBackupManager.io.execute {
      try {
        if (pending !== action) return@execute
        DriveBackupManager.acceptSelection(context, result, action.original, action.changeFolder)
        if (!take(action)) return@execute
        diagnostics.clear(action.diagnostic, DriveBackupQueue.config(context).generation)
        resolve(action.promise)
      } catch (failure: Exception) {
        fail(action, "selection", (failure as? DriveBackupFailure) ?: DriveBackupFailure("UPLOAD_FAILED"))
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
