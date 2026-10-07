package com.didi4164.WhatsAppCallRecorder

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.common.LifecycleState
import java.util.concurrent.atomic.AtomicBoolean

class TelegramBackupModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context), LifecycleEventListener {
  private val pending = AtomicBoolean(false)
  @Volatile private var invalidated = false
  @Volatile private var foregroundWait: ForegroundMutationGate? = null
  private val main = Handler(Looper.getMainLooper())
  init { context.addLifecycleEventListener(this) }
  override fun getName() = "TelegramBackup"
  override fun invalidate() {
    invalidated = true; foregroundWait?.cancel(); context.removeLifecycleEventListener(this); super.invalidate()
  }
  override fun onHostResume() { }
  override fun onHostPause() { foregroundWait?.cancel() }
  override fun onHostDestroy() { foregroundWait?.cancel() }
  private fun foreground(activity: Activity?): Boolean = !invalidated && activity != null && currentActivity === activity &&
    !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
  private fun reject(promise: Promise, failure: Exception) {
    val code = (failure as? TelegramBackupFailure)?.code ?: "LOCAL_QUEUE_UNAVAILABLE"
    promise.reject(code, code) // UI owns bilingual copy; no raw exception/provider text crosses the bridge.
  }
  private fun resolve(promise: Promise) {
    try { promise.resolve(Arguments.makeNativeMap(TelegramBackupStore.status(context))) }
    catch (failure: Exception) { reject(promise, failure) }
  }
  @ReactMethod fun getStatus(promise: Promise) { resolve(promise) }
  private fun run(promise: Promise, action: (isForeground: () -> Boolean) -> Unit) {
    val activity = currentActivity
    if (!foreground(activity)) { reject(promise, TelegramBackupFailure("FOREGROUND_REQUIRED")); return }
    if (!pending.compareAndSet(false, true)) { reject(promise, TelegramBackupFailure("CONNECTION_BUSY")); return }
    activity!!.runOnUiThread {
      if (!foreground(activity)) { pending.set(false); reject(promise, TelegramBackupFailure("FOREGROUND_REQUIRED")); return@runOnUiThread }
      TelegramBackupManager.io.execute {
        try { if (!foreground(activity)) throw TelegramBackupFailure("FOREGROUND_REQUIRED"); action { foreground(activity) }; resolve(promise) }
        catch (failure: Exception) { reject(promise, failure) }
        finally { pending.set(false) }
      }
    }
  }
  /** Alert's positive callback may precede focus returning to the same resumed Activity. */
  private fun enableAfterConfirmation(promise: Promise) {
    val activity = currentActivity
    if (activity == null) { reject(promise, TelegramBackupFailure("FOREGROUND_REQUIRED")); return }
    if (!pending.compareAndSet(false, true)) { reject(promise, TelegramBackupFailure("CONNECTION_BUSY")); return }
    val generation = try { TelegramBackupStore.config(context).generation }
      catch (failure: Exception) { pending.set(false); reject(promise, failure); return }
    activity.runOnUiThread {
      lateinit var wait: ForegroundMutationGate
      wait = ForegroundMutationGate(object : ForegroundMutationGate.Scheduler {
        override fun now() = SystemClock.elapsedRealtime()
        override fun post(task: Runnable, delayMs: Long) { main.postDelayed(task, delayMs) }
      }, {
        ForegroundMutationGate.State(!invalidated && foregroundWait === wait && currentActivity === activity &&
          !activity.isFinishing && !activity.isDestroyed && context.hasActiveReactInstance(),
          context.lifecycleState == LifecycleState.RESUMED, activity.hasWindowFocus(),
          TelegramBackupStore.config(context).generation == generation, pending.get(), !RecordingService.isBusy())
      }, {
        TelegramBackupManager.io.execute {
          try {
            wait.beginExecution()?.let { throw TelegramBackupFailure(if (it == "ACCOUNT_CHANGED") "CANCELED" else it) }
            TelegramBackupManager.setEnabled(context, true, generation) { wait.rejectionNow() == null }
            resolve(promise)
          } catch (failure: Exception) { reject(promise, failure) }
          finally { if (foregroundWait === wait) foregroundWait = null; pending.set(false) }
        }
      }, { code ->
        if (foregroundWait === wait) foregroundWait = null
        pending.set(false); reject(promise, TelegramBackupFailure(if (code == "ACCOUNT_CHANGED") "CANCELED" else code))
      })
      foregroundWait = wait; wait.start()
    }
  }
  @ReactMethod fun connect(token: String, promise: Promise) = run(promise) { visible ->
    if (RecordingService.isBusy()) throw TelegramBackupFailure("RECORDING_BUSY")
    TelegramBackupManager.connect(context, token, visible)
  }
  @ReactMethod fun verifyConnection(promise: Promise) = run(promise) { visible -> TelegramBackupManager.verifyConnection(context, visible) }
  @ReactMethod fun setEnabled(enabled: Boolean, promise: Promise) {
    // Opt-out cuts an in-progress upload/control action immediately, including while React is pausing.
    if (!enabled) {
      foregroundWait?.cancel()
      try { TelegramBackupManager.setEnabled(context, false) { true }; resolve(promise) }
      catch (failure: Exception) { reject(promise, failure) }
    } else enableAfterConfirmation(promise)
  }
  @ReactMethod fun retryPending(confirmPossibleDuplicates: Boolean, promise: Promise) = run(promise) { visible ->
    TelegramBackupManager.retry(context, confirmPossibleDuplicates, visible)
  }
  @ReactMethod fun disconnect(promise: Promise) {
    val activity = currentActivity
    if (!foreground(activity)) { reject(promise, TelegramBackupFailure("FOREGROUND_REQUIRED")); return }
    activity!!.runOnUiThread {
      try {
        if (!foreground(activity)) throw TelegramBackupFailure("FOREGROUND_REQUIRED")
        TelegramBackupManager.disconnect(context); resolve(promise)
      } catch (failure: Exception) { reject(promise, failure) }
    }
  }
}
