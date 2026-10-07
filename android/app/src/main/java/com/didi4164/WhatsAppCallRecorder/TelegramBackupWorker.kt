package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import android.os.SystemClock
import androidx.work.Worker
import androidx.work.WorkerParameters

/** No token prompt, no audio capture, no implicit consent; only finalized-file queue entries. */
class TelegramBackupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
  @Volatile private var activeClient: TelegramBotHttp? = null
  @Volatile private var activeLease: TelegramBackupLedger.Lease? = null
  override fun onStopped() {
    activeClient?.cancel()
    activeLease?.let { lease -> runCatching { TelegramBackupStore.change(applicationContext) {
      TelegramBackupLedger.failed(it, lease, TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0), System.currentTimeMillis())
    } } }
    super.onStopped()
  }
  override fun doWork(): Result {
    val context = applicationContext
    val config = try { TelegramBackupStore.config(context) } catch (_: Exception) { return Result.success() }
    if (!config.enabled || !config.connected) return Result.success()
    // Hashing/upload work yields while a call capture is active.
    if (RecordingService.isBusy()) return Result.retry()
    val deadline = SystemClock.elapsedRealtime() + 7 * 60 * 1000L
    fun current() = !isStopped && SystemClock.elapsedRealtime() < deadline && TelegramBackupStore.current(context, config)
    fun yielded(): Result {
      // The time budget revokes I/O for this attempt, while a still-authorized
      // pending queue needs another WorkManager wakeup. Disable/rebind/stop does not.
      val pending = TelegramBackupStore.hasPending(context)
      return if (TelegramWorkerWakeupPolicy.shouldRetryAfterYield(isStopped,
          TelegramBackupStore.current(context, config), pending)) Result.retry() else Result.success()
    }
    try {
      repeat(2) {
        if (!current()) return yielded()
        if (RecordingService.isBusy()) return Result.retry()
        val selection = TelegramBackupStore.next(context)
          ?: return if (TelegramBackupStore.hasPending(context)) Result.retry() else Result.success()
        val file = RecordingStore.file(context, selection.id, ".wav")
        val inspection = try {
          TelegramBackupManager.inspect(file) { current() && !RecordingService.isBusy() }.also {
            if (it.size != selection.size || it.modifiedAt != selection.modifiedAt || it.sha256 != selection.sha256
                || it.plan.parts != selection.count || it.plan.part(selection.index).bytes != selection.partBytes)
              throw TelegramBackupFailure("LOCAL_FILE_CHANGED")
          }
        } catch (failure: TelegramBackupFailure) {
          if (failure.code == "CANCELED") return yielded()
          TelegramBackupStore.change(context) { TelegramBackupLedger.failedBeforeDispatch(it, selection, failure.code, System.currentTimeMillis()) }
          return@repeat
        }
        // Register the cancellable transport BEFORE claiming. A disable/rebind
        // after claim therefore cannot escape cancelAll by constructing a new client.
        val client = TelegramBotHttp(120000); activeClient = client
        try {
          if (!current()) return yielded()
          if (RecordingService.isBusy()) return Result.retry()
          val lease = TelegramBackupStore.change(context) {
            TelegramBackupLedger.claim(it, selection, TelegramBackupManager.attempt(), System.currentTimeMillis())
          } ?: return Result.success()
          activeLease = lease // Durable dispatch intent already committed and fsynced.
          try {
            val receipt = client.sendDocument(selection.config.token, selection.config.botId, selection.config.chatId,
              selection.filename, file, inspection.plan, selection.index) {
                current() && file.isFile && file.length() == selection.size && file.lastModified() == selection.modifiedAt
              }
            if (!file.isFile || file.length() != selection.size || file.lastModified() != selection.modifiedAt)
              throw TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0)
            // A late exact positive receipt is kept under the original binding,
            // even after disable/disconnect; it cannot authorize another send.
            TelegramBackupStore.change(context) { TelegramBackupLedger.delivered(it, lease, receipt) }
          } catch (failure: Exception) {
            val safe = TelegramBackupFailure.afterDispatch(failure)
            TelegramBackupStore.change(context) { TelegramBackupLedger.failed(it, lease, safe, System.currentTimeMillis()) }
            if (safe.retryable) return Result.retry()
          } finally { activeLease = null }
        } finally { client.close(); activeClient = null }
      }
      if (TelegramBackupStore.hasPending(context)) TelegramBackupManager.schedule(context)
      return Result.success()
    } catch (_: Exception) {
      activeLease?.let { lease -> runCatching { TelegramBackupStore.change(context) {
        TelegramBackupLedger.failed(it, lease, TelegramBackupFailure("UNKNOWN_OUTCOME", false, true, 0), System.currentTimeMillis())
      } } }
      return Result.success()
    } finally { activeClient?.close(); activeClient = null; activeLease = null }
  }
}
