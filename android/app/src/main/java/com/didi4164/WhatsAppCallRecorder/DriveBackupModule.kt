package com.didi4164.WhatsAppCallRecorder

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.common.LifecycleState
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import java.util.concurrent.atomic.AtomicBoolean

class DriveBackupModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context), LifecycleEventListener {
  companion object {
    // Independent of the durable upload queue; retained across Settings/React context remounts in this process.
    private val diagnostics = DriveBackupActionDiagnostics()
  }
  private enum class Mode { PICK_EXISTING, CHANGE_EXISTING, DEFAULT_FOLDER }
  private data class Pending(val promise: Promise, val original: DriveBackupQueue.Config, val mode: Mode,
    val diagnostic: DriveBackupActionDiagnostics.Ticket, val activity: Activity, val attempt: DriveConnectionAttempt,
    val client: DriveBackupClient = DriveBackupClient(), val processing: AtomicBoolean = AtomicBoolean(false))
  @Volatile private var pending: Pending? = null
  @Volatile private var foregroundWait: ForegroundMutationGate? = null
  @Volatile private var invalidated = false
  private val main = Handler(Looper.getMainLooper())
  private val listener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      val action = pending ?: return
      if (!action.attempt.matches(requestCode)) return
      if (activity !== action.activity) { fail(action, "pickerResult", DriveBackupFailure("FOREGROUND_REQUIRED")); return }
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
  init { context.addActivityEventListener(listener); context.addLifecycleEventListener(this) }
  override fun getName() = "DriveBackup"
  override fun invalidate() {
    invalidated = true
    foregroundWait?.cancel()
    context.removeActivityEventListener(listener)
    context.removeLifecycleEventListener(this)
    pending?.let { fail(it, "authorize", DriveBackupFailure("FOREGROUND_REQUIRED")) }
    super.invalidate()
  }
  override fun onHostResume() { }
  override fun onHostPause() {
    foregroundWait?.cancel()
    // Google's consent UI legitimately pauses the app before result processing begins.
    pending?.takeIf { it.processing.get() }?.let { fail(it, "selection", DriveBackupFailure("FOREGROUND_REQUIRED")) }
  }
  override fun onHostDestroy() {
    foregroundWait?.cancel()
    pending?.let { fail(it, "authorize", DriveBackupFailure("FOREGROUND_REQUIRED")) }
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
    if (pending !== action) false else { pending = null; action.attempt.cancel(); action.client.cancel(); true }
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
  /** Result callbacks may precede window focus restoration; resumed lifecycle is the post-consent write gate. */
  private fun checkActive(action: Pending) {
    if (pending !== action || !action.attempt.isActive() || currentActivity !== action.activity ||
        action.activity.isFinishing || action.activity.isDestroyed || context.lifecycleState != LifecycleState.RESUMED) {
      throw DriveBackupFailure("FOREGROUND_REQUIRED")
    }
    if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
    if (DriveBackupQueue.config(context).generation != action.original.generation) throw DriveBackupFailure("ACCOUNT_CHANGED")
  }
  @ReactMethod fun getStatus(promise: Promise) { resolve(promise) }
  @ReactMethod fun connect(promise: Promise) { choose(promise, Mode.PICK_EXISTING) }
  @ReactMethod fun chooseFolder(promise: Promise) { choose(promise, Mode.CHANGE_EXISTING) }
  @ReactMethod fun connectDefaultFolder(promise: Promise) { choose(promise, Mode.DEFAULT_FOLDER) }
  private fun choose(promise: Promise, mode: Mode) {
    val activity = currentActivity
    if (activity == null || activity.isFinishing) { reject(promise, "FOREGROUND_REQUIRED"); return }
    activity.runOnUiThread {
      if (!isForeground(activity)) { reject(promise, "FOREGROUND_REQUIRED"); return@runOnUiThread }
      if (RecordingService.isBusy()) { reject(promise, "RECORDING_BUSY"); return@runOnUiThread }
      if (pending != null || foregroundWait != null) { reject(promise, "CONNECTION_BUSY"); return@runOnUiThread }
      val original = DriveBackupQueue.config(context)
      val changeFolder = mode == Mode.CHANGE_EXISTING
      if (changeFolder && !original.connected) { reject(promise, "NOT_CONNECTED"); return@runOnUiThread }
      val attempt = try { DriveConnectionAttempt() } catch (_: Exception) { reject(promise, "CONNECTION_BUSY"); return@runOnUiThread }
      val action = Pending(promise, original, mode, diagnostics.begin(original.generation, if (changeFolder) "folder" else "connect"), activity, attempt)
      pending = action
      val request = if (mode == Mode.DEFAULT_FOLDER) DriveBackupManager.defaultFolderRequest() else DriveBackupManager.pickerRequest(original, changeFolder)
      try { Identity.getAuthorizationClient(context).authorize(request)
        .addOnSuccessListener { result ->
          if (pending !== action) return@addOnSuccessListener
          if (!isForeground(activity)) { fail(action, "authorize", DriveBackupFailure("FOREGROUND_REQUIRED")); return@addOnSuccessListener }
          if (result.hasResolution()) {
            try {
              if (!isForeground(activity)) throw DriveBackupFailure("FOREGROUND_REQUIRED")
              if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
              if (DriveBackupQueue.config(context).generation != original.generation) throw DriveBackupFailure("ACCOUNT_CHANGED")
              activity.startIntentSenderForResult(result.pendingIntent!!.intentSender, action.attempt.requestCode, null, 0, 0, 0)
            } catch (failure: Exception) { fail(action, "authorize", (failure as? DriveBackupFailure) ?: DriveBackupFailure("FOREGROUND_REQUIRED")) }
          } else accept(action, result)
        }.addOnFailureListener { failure ->
          if (googleStatus(failure) == 16) cancel(action) else fail(action, "authorize", failure)
        }
      } catch (failure: Exception) { fail(action, "authorize", failure) }
    }
  }
  private fun accept(action: Pending, result: AuthorizationResult) {
    if (pending !== action || !action.processing.compareAndSet(false, true)) return
    // The next UI message runs after Activity.onResume following the Google result callback.
    action.activity.window.decorView.post {
      DriveBackupManager.io.execute {
        try {
          checkActive(action)
          if (action.mode == Mode.DEFAULT_FOLDER) {
            DriveBackupManager.prepareDefaultFolder(context, result, action.original, action.client, { checkActive(action) },
              { reserve -> action.attempt.mutate { checkActive(action); reserve() } },
              { selection -> complete(action, selection) })
          } else {
            val selection = DriveBackupManager.prepareSelection(result, action.original, action.mode == Mode.CHANGE_EXISTING,
              action.client, { checkActive(action) })
            complete(action, selection)
          }
        } catch (failure: Exception) {
          fail(action, "selection", (failure as? DriveBackupFailure) ?: DriveBackupFailure("UPLOAD_FAILED"))
        }
      }
    }
  }
  private fun complete(action: Pending, selection: DriveBackupManager.Selection) {
    synchronized(this) {
      if (pending !== action) throw DriveBackupFailure("FOREGROUND_REQUIRED")
      action.attempt.complete { checkActive(action); DriveBackupManager.commitSelection(context, action.original, selection) }
      pending = null
    }
    // The durable generation already invalidates old workers; cancellation failure cannot strand the Promise.
    runCatching { DriveBackupManager.cancel(context) }
    runCatching { diagnostics.clear(action.diagnostic, DriveBackupQueue.config(context).generation) }
    resolve(action.promise)
  }
  private fun run(promise: Promise, foreground: Boolean = false, action: (Long?, () -> Unit) -> Unit) {
    if (pending != null || foregroundWait != null) { reject(promise, "CONNECTION_BUSY"); return }
    val activity = currentActivity
    if (foreground) {
      if (activity == null) { reject(promise, "FOREGROUND_REQUIRED"); return }
      // Bind the dialog approval before waiting; never apply it to a replacement destination.
      val generation = try { DriveBackupQueue.config(context).generation }
        catch (_: Exception) { reject(promise, "LOCAL_QUEUE_UNAVAILABLE"); return }
      activity.runOnUiThread {
        if (pending != null || foregroundWait != null) { reject(promise, "CONNECTION_BUSY"); return@runOnUiThread }
        lateinit var wait: ForegroundMutationGate
        wait = ForegroundMutationGate(object : ForegroundMutationGate.Scheduler {
          override fun now() = SystemClock.elapsedRealtime()
          override fun post(task: Runnable, delayMs: Long) { main.postDelayed(task, delayMs) }
        }, {
          ForegroundMutationGate.State(!invalidated && foregroundWait === wait && currentActivity === activity &&
            !activity.isFinishing && !activity.isDestroyed && context.hasActiveReactInstance(),
            context.lifecycleState == LifecycleState.RESUMED, activity.hasWindowFocus(),
            DriveBackupQueue.config(context).generation == generation, pending == null, !RecordingService.isBusy())
        }, {
          DriveBackupManager.io.execute {
            try {
              val guard = { wait.rejectionNow()?.let { throw DriveBackupFailure(it) }; Unit }
              wait.beginExecution()?.let { throw DriveBackupFailure(it) }
              action(generation, guard); resolve(promise)
            } catch (failure: Exception) {
              reject(promise, (failure as? DriveBackupFailure)?.code ?: "UPLOAD_FAILED")
            } finally { synchronized(this) { if (foregroundWait === wait) foregroundWait = null } }
          }
        }, { code ->
          synchronized(this) { if (foregroundWait === wait) foregroundWait = null }
          reject(promise, code)
        })
        foregroundWait = wait
        wait.start()
      }
      return
    }
    DriveBackupManager.io.execute {
      try {
        if (pending != null || foregroundWait != null) throw DriveBackupFailure("CONNECTION_BUSY")
        action(null, {}); resolve(promise)
      }
      catch (failure: Exception) { reject(promise, (failure as? DriveBackupFailure)?.code ?: if (failure.message == "LOCAL_QUEUE_UNAVAILABLE") "LOCAL_QUEUE_UNAVAILABLE" else "UPLOAD_FAILED") }
    }
  }
  @ReactMethod fun setEnabled(enabled: Boolean, promise: Promise) = run(promise, foreground = enabled) { generation, guard -> DriveBackupManager.setEnabled(context, enabled, generation, guard) }
  @ReactMethod fun retryPending(promise: Promise) = run(promise) { _, _ -> DriveBackupManager.retry(context) }
  @ReactMethod fun disconnect(promise: Promise) = run(promise, foreground = true) { generation, guard -> DriveBackupManager.disconnect(context, generation, guard) }
}
