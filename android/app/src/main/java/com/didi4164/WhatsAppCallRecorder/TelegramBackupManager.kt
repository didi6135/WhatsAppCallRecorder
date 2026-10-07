package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TelegramBackupManager {
  private const val WORK = "wa-reco-telegram-finalized-recordings"
  internal val io = Executors.newSingleThreadExecutor { action -> Thread(action, "TelegramBackupControl") }
  private val scans = Executors.newSingleThreadExecutor { action -> Thread(action, "TelegramBackupReconcile") }
  data class Inspection(val size: Long, val modifiedAt: Long, val sha256: String, val plan: TelegramWavParts.Plan)
  fun nonce() = "wa_" + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
  fun attempt() = nonce().removePrefix("wa_")
  private fun foreground(check: () -> Boolean) { if (!check()) throw TelegramBackupFailure("FOREGROUND_REQUIRED") }
  private fun sameGeneration(context: Context, generation: Long) {
    if (TelegramBackupStore.config(context).generation != generation) throw TelegramBackupFailure("CANCELED")
  }
  fun initialize(context: Context) {
    val app = context.applicationContext
    scans.execute { reconcile(app) }
  }
  /** Called after a committed local save; never holds RecordingStore's lock during queue/network work. */
  fun enqueueCompleted(context: Context, id: String) {
    val app = context.applicationContext
    scans.execute {
      val expected = runCatching { TelegramBackupStore.config(app) }.getOrNull() ?: return@execute
      try { enqueue(app, id); schedule(app) }
      catch (failure: Exception) { rememberFailure(app, expected.generation, failure) }
    }
  }
  private fun rememberFailure(context: Context, generation: Long, failure: Exception) {
    val code = (failure as? TelegramBackupFailure)?.code ?: "LOCAL_QUEUE_UNAVAILABLE"
    if (code == "CANCELED") return
    runCatching { TelegramBackupStore.change(context) {
      if (TelegramBackupLedger.config(it).generation == generation) TelegramBackupLedger.error(it, code, System.currentTimeMillis())
    } }
  }
  private fun reconcile(context: Context, replace: Boolean = false) {
    val expected = runCatching { TelegramBackupStore.config(context) }.getOrNull() ?: return
    try { if (expected.enabled) { scan(context); schedule(context, replace) } }
    catch (failure: Exception) { rememberFailure(context, expected.generation, failure) }
  }
  private fun scan(context: Context) {
    val expected = TelegramBackupStore.config(context)
    if (!expected.enabled || !expected.connected) return
    // Reading the store is independent of the queue lock and does not capture audio.
    for (recording in RecordingStore.list(context)) {
      if (!TelegramBackupStore.current(context, expected)) return
      try { enqueue(context, recording.getString("id")) }
      catch (failure: TelegramBackupFailure) {
        if (failure.code == "CANCELED") return
        rememberFailure(context, expected.generation, failure)
      }
    }
  }
  private fun enqueue(context: Context, id: String) {
    val cfg = TelegramBackupStore.config(context)
    if (!cfg.enabled || !cfg.connected) return
    if (TelegramBackupStore.contains(context, id, cfg)) return
    // No provider requests, hashing or file I/O while the queue lock is held.
    val file = RecordingStore.file(context, id, ".wav")
    val inspection = inspect(file) { TelegramBackupStore.current(context, cfg) }
    val exportFileName = RecordingStore.exportFileName(context, id)
    TelegramBackupStore.change(context) { state ->
      val current = TelegramBackupLedger.config(state)
      if (current.generation == cfg.generation && current.destination == cfg.destination)
        TelegramBackupLedger.enqueue(state, id, inspection.size, inspection.modifiedAt, inspection.sha256, inspection.plan, exportFileName)
    }
  }
  internal fun inspect(file: File, current: () -> Boolean): Inspection {
    if (!file.isFile) throw TelegramBackupFailure("LOCAL_FILE_MISSING")
    val size = file.length(); val modified = file.lastModified()
    try {
      RandomAccessFile(file, "r").use { input ->
        val plan = TelegramWavParts.read(input)
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); input.seek(0)
        while (true) {
          if (!current()) throw TelegramBackupFailure("CANCELED")
          val count = input.read(buffer); if (count < 0) break
          digest.update(buffer, 0, count)
        }
        if (!file.isFile || file.length() != size || file.lastModified() != modified || input.length() != size)
          throw TelegramBackupFailure("LOCAL_FILE_CHANGED")
        return Inspection(size, modified, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, plan)
      }
    } catch (failure: TelegramBackupFailure) { throw failure }
    catch (_: Exception) { throw TelegramBackupFailure(if (file.isFile) "LOCAL_FILE_CHANGED" else "LOCAL_FILE_MISSING") }
  }
  fun connect(context: Context, token: String, isForeground: () -> Boolean) {
    foreground(isForeground)
    if (!TelegramBotProtocol.validToken(token)) throw TelegramBackupFailure("TOKEN_INVALID")
    TelegramBackupStore.change(context) { TelegramBackupLedger.setEnabled(it, false) }; cancel(context)
    val original = TelegramBackupStore.snapshot(context); val cfg = TelegramBackupLedger.config(original)
    TelegramBotHttp(60000).use { client ->
      val bot = TelegramBotProtocol.bot(client.request(token, "getMe", JSONObject()))
      TelegramBotProtocol.requireNoWebhook(client.request(token, "getWebhookInfo", JSONObject()))
      // No offset here: retained app starts may be observed but unrelated messages are never drained.
      val rows = client.request(token, "getUpdates", JSONObject().put("limit", 100).put("timeout", 0))
      TelegramBotProtocol.requireOwnedUpdates(rows, TelegramBackupLedger.ownedNonces(original, bot.id))
      foreground(isForeground); sameGeneration(context, cfg.generation)
      val challenge = nonce(); val expires = System.currentTimeMillis() + 600000
      TelegramBackupStore.change(context) {
        if (TelegramBackupLedger.config(it).generation != cfg.generation) throw TelegramBackupFailure("CANCELED")
        TelegramBackupLedger.prepareConnection(it, token, bot.id, bot.username, challenge, expires)
      }
    }
  }
  fun verifyConnection(context: Context, isForeground: () -> Boolean) {
    foreground(isForeground)
    val snapshot = TelegramBackupStore.snapshot(context); val cfg = TelegramBackupLedger.config(snapshot)
    if (cfg.connected) { acknowledge(context, isForeground); return }
    if (cfg.nonce.isEmpty()) throw TelegramBackupFailure("CONNECTION_NOT_CONFIRMED")
    if (System.currentTimeMillis() >= cfg.expiresAt) throw TelegramBackupFailure("CONNECTION_EXPIRED")
    TelegramBotHttp(60000).use { client ->
      val bot = TelegramBotProtocol.bot(client.request(cfg.token, "getMe", JSONObject()))
      if (bot.id != cfg.botId || bot.username != cfg.botUsername) throw TelegramBackupFailure("TOKEN_INVALID")
      TelegramBotProtocol.requireNoWebhook(client.request(cfg.token, "getWebhookInfo", JSONObject()))
      val rows = client.request(cfg.token, "getUpdates", JSONObject().put("limit", 100).put("timeout", 0))
      val own = TelegramBackupLedger.ownedNonces(snapshot, bot.id)
      val chat = TelegramBotProtocol.matchPrivateChat(rows, cfg.nonce, own)
      val offset = TelegramBotProtocol.ownedOffset(rows, own)
      foreground(isForeground); sameGeneration(context, cfg.generation)
      TelegramBackupStore.change(context) {
        if (TelegramBackupLedger.config(it).generation != cfg.generation) throw TelegramBackupFailure("CANCELED")
        TelegramBackupLedger.completeConnection(it, chat, System.currentTimeMillis())
        it.put("pendingAck", offset) // The exact validated own stream is committed before acknowledgment.
      }
    }
    acknowledge(context, isForeground)
  }
  private fun acknowledge(context: Context, isForeground: () -> Boolean) {
    val snapshot = TelegramBackupStore.snapshot(context); val cfg = TelegramBackupLedger.config(snapshot)
    val offset = snapshot.optLong("pendingAck")
    if (!cfg.connected || offset <= 0) return
    foreground(isForeground)
    TelegramBotHttp(30000).use { client ->
      // Only update IDs covered by the durable own-nonce proof are acknowledged.
      // Any newer returned updates are neither acknowledged nor persisted.
      client.request(cfg.token, "getUpdates", JSONObject().put("offset", offset).put("limit", 100).put("timeout", 0))
    }
    foreground(isForeground); sameGeneration(context, cfg.generation)
    TelegramBackupStore.change(context) { if (TelegramBackupLedger.config(it).generation == cfg.generation) it.remove("pendingAck") }
  }
  fun setEnabled(context: Context, enabled: Boolean, expectedGeneration: Long? = null, isForeground: () -> Boolean) {
    if (enabled) { foreground(isForeground); acknowledge(context, isForeground); foreground(isForeground) }
    TelegramBackupStore.change(context) {
      if (expectedGeneration != null && TelegramBackupLedger.config(it).generation != expectedGeneration) throw TelegramBackupFailure("CANCELED")
      if (enabled) foreground(isForeground) // Recheck within the store lock, immediately before enabling.
      if (expectedGeneration == null) TelegramBackupLedger.setEnabled(it, enabled)
      else TelegramBackupLedger.setEnabled(it, enabled, expectedGeneration)
    }; cancel(context)
    if (enabled) scans.execute { reconcile(context, true) }
  }
  fun retry(context: Context, confirmPossibleDuplicates: Boolean, isForeground: () -> Boolean) {
    foreground(isForeground)
    TelegramBackupStore.change(context) { TelegramBackupLedger.retry(it, confirmPossibleDuplicates) }; cancel(context)
    if (TelegramBackupStore.config(context).enabled) scans.execute { reconcile(context, true) }
  }
  fun disconnect(context: Context) {
    TelegramBackupStore.change(context) { TelegramBackupLedger.disconnect(it) }; cancel(context)
  }
  fun cancel(context: Context) {
    TelegramBotHttp.cancelAll(); WorkManager.getInstance(context).cancelUniqueWork(WORK)
  }
  internal fun schedule(context: Context, replace: Boolean = false) {
    if (!TelegramBackupStore.hasPending(context)) return
    val request = OneTimeWorkRequestBuilder<TelegramBackupWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK).build()
    WorkManager.getInstance(context).enqueueUniqueWork(WORK,
      if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }
}
