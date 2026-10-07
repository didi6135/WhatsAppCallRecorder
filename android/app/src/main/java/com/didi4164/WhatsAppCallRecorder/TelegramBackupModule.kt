package com.didi4164.WhatsAppCallRecorder

import android.app.Activity
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.util.concurrent.atomic.AtomicBoolean

class TelegramBackupModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val pending = AtomicBoolean(false)
  @Volatile private var invalidated = false
  override fun getName() = "TelegramBackup"
  override fun invalidate() { invalidated = true; super.invalidate() }
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
  @ReactMethod fun connect(token: String, promise: Promise) = run(promise) { visible ->
    if (RecordingService.isBusy()) throw TelegramBackupFailure("RECORDING_BUSY")
    TelegramBackupManager.connect(context, token, visible)
  }
  @ReactMethod fun verifyConnection(promise: Promise) = run(promise) { visible -> TelegramBackupManager.verifyConnection(context, visible) }
  @ReactMethod fun setEnabled(enabled: Boolean, promise: Promise) {
    // Opt-out cuts an in-progress upload/control action immediately, including while React is pausing.
    if (!enabled) {
      try { TelegramBackupManager.setEnabled(context, false) { true }; resolve(promise) }
      catch (failure: Exception) { reject(promise, failure) }
    } else run(promise) { visible -> TelegramBackupManager.setEnabled(context, true, visible) }
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
