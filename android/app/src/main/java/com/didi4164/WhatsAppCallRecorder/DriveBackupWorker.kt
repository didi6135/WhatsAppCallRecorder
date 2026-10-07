package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import android.os.SystemClock
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

private class DriveBackupStopped : Exception()

class DriveBackupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
  private val client = DriveBackupClient()
  @Volatile private var activeConfig: DriveBackupQueue.Config? = null
  override fun onStopped() { client.cancel(); activeConfig?.let(DriveBackupQueue::end); super.onStopped() }
  override fun doWork(): Result {
    val context = applicationContext
    val config = DriveBackupQueue.config(context)
    if (!config.enabled || !config.connected || DriveBackupQueue.blocked(context)) return Result.success()
    activeConfig = config; DriveBackupQueue.begin(context, config)
    val deadline = SystemClock.elapsedRealtime() + 7 * 60 * 1000L
    var token: String? = null
    var job: JSONObject? = null
    try {
      ensureCurrent(config, deadline)
      token = DriveBackupManager.token(context, config)
      fun validateAccount() {
        ensureCurrent(config, deadline)
        val actual = client.account(token!!)
        if (actual.permissionId != config.accountId || !actual.email.equals(config.email, ignoreCase = true)) throw DriveBackupFailure("ACCOUNT_CHANGED", auth = true)
        ensureCurrent(config, deadline); client.folder(token!!, config.folderId)
      }
      validateAccount()
      while (true) {
        ensureCurrent(config, deadline)
        job = DriveBackupQueue.next(context, config) ?: break
        val selected = job!!
        try {
          try { upload(client, token!!, config, selected, deadline) }
          catch (failure: DriveBackupFailure) {
            if (failure.code != "AUTH_REQUIRED") throw failure
            DriveBackupManager.clearToken(context, token!!)
            token = DriveBackupManager.token(context, config); validateAccount()
            upload(client, token!!, config, selected, deadline)
          }
        } catch (failure: DriveBackupFailure) {
          val attempts = selected.optInt("attempts") + 1
          val code = if (failure.retryable && attempts >= DriveBackupPolicy.MAX_ATTEMPTS) "RETRY_LIMIT" else failure.code
          if (!DriveBackupPolicy.isolatedFileFailure(code)) throw failure
          selected.put("state", "failed").put("errorCode", code).put("attempts", attempts); persist(config, selected)
          // One removed/changed recording must not block backups of the other completed files.
        }
        job = null
      }
      val failed = DriveBackupQueue.failedCode(context, config)
      DriveBackupQueue.phase(context, config, if (failed == null) "ready" else "error", failed)
      return Result.success()
    } catch (_: DriveBackupStopped) {
      // A changed destination must never receive a status/receipt from an obsolete worker.
      return if (!isStopped && DriveBackupQueue.current(context, config)) Result.retry() else Result.success()
    } catch (failure: DriveBackupFailure) {
      val selected = job
      if (failure.auth || failure.code == "CONFIGURATION_REQUIRED" || failure.code == "ACCOUNT_CHANGED") {
        selected?.let { it.put("state", "pending"); DriveBackupQueue.update(context, config, it) }
        DriveBackupQueue.phase(context, config, if (failure.code == "CONFIGURATION_REQUIRED") "error" else "needsConsent", failure.code)
        return Result.success()
      }
      val attempts = (selected?.optInt("attempts") ?: runAttemptCount) + 1
      if (selected != null) {
        selected.put("attempts", attempts).put("state", if (failure.retryable && attempts < DriveBackupPolicy.MAX_ATTEMPTS) "pending" else "failed")
          .put("errorCode", if (failure.retryable && attempts >= DriveBackupPolicy.MAX_ATTEMPTS) "RETRY_LIMIT" else failure.code)
        DriveBackupQueue.update(context, config, selected)
      }
      DriveBackupQueue.phase(context, config, "error", if (failure.retryable && attempts >= DriveBackupPolicy.MAX_ATTEMPTS) "RETRY_LIMIT" else failure.code)
      return if (failure.retryable && attempts < DriveBackupPolicy.MAX_ATTEMPTS) Result.retry() else Result.success()
    } catch (_: Exception) {
      job?.let { it.put("state", "failed").put("errorCode", "UPLOAD_FAILED"); runCatching { DriveBackupQueue.update(context, config, it) } }
      runCatching { DriveBackupQueue.phase(context, config, "error", "UPLOAD_FAILED") }
      return Result.success()
    } finally { DriveBackupQueue.end(config); activeConfig = null }
  }
  private fun ensureCurrent(config: DriveBackupQueue.Config, deadline: Long) {
    if (isStopped || !DriveBackupQueue.current(applicationContext, config) || SystemClock.elapsedRealtime() >= deadline) throw DriveBackupStopped()
  }
  private fun persist(config: DriveBackupQueue.Config, job: JSONObject) {
    if (!DriveBackupQueue.update(applicationContext, config, job)) throw DriveBackupStopped()
  }
  private fun digest(file: File, config: DriveBackupQueue.Config, deadline: Long): String {
    val md5 = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
      val bytes = ByteArray(64 * 1024)
      while (true) {
        ensureCurrent(config, deadline)
        val count = input.read(bytes); if (count < 0) break
        md5.update(bytes, 0, count)
      }
    }
    return md5.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
  }
  private fun checkLocal(file: File, job: JSONObject) {
    if (!file.isFile) throw DriveBackupFailure("LOCAL_FILE_MISSING")
    if (file.length() != job.optLong("size") || job.has("modifiedAt") && file.lastModified() != job.optLong("modifiedAt")) throw DriveBackupFailure("LOCAL_FILE_CHANGED")
  }
  private fun upload(client: DriveBackupClient, token: String, config: DriveBackupQueue.Config, job: JSONObject, deadline: Long) {
    val id = job.optString("id")
    if (!DriveBackupPolicy.validRecordingId(id)) throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    val file = RecordingStore.file(applicationContext, id, ".wav")
    checkLocal(file, job); ensureCurrent(config, deadline)
    if (!job.has("md5")) {
      val modified = file.lastModified(); val md5 = digest(file, config, deadline)
      if (file.lastModified() != modified || file.length() != job.optLong("size")) throw DriveBackupFailure("LOCAL_FILE_CHANGED")
      job.put("md5", md5).put("modifiedAt", modified); persist(config, job)
    }
    if (!job.has("remoteId")) {
      ensureCurrent(config, deadline)
      val remoteId = client.generateId(token)
      job.put("remoteId", remoteId); persist(config, job) // Durable BEFORE any remote file create.
    }
    ensureCurrent(config, deadline)
    if (client.verifiedRemote(token, job, config.folderId)) { markUploaded(config, job, file, deadline); return }
    ensureCurrent(config, deadline); client.folder(token, config.folderId)
    var uri = job.optString("session").takeIf { it.isNotEmpty() }?.let(DriveBackupSecrets::decrypt)
    var offset = 0L
    if (uri != null) {
      try {
        ensureCurrent(config, deadline)
        val reply = client.session(token, uri, job.getLong("size"))
        if (reply.complete) { verifyAndMark(client, token, config, job, file, deadline); return }
        offset = reply.offset
      } catch (failure: DriveBackupFailure) {
        if (failure.code != "SESSION_EXPIRED") throw failure
        job.remove("session"); job.put("offset", 0); persist(config, job)
        ensureCurrent(config, deadline)
        if (client.verifiedRemote(token, job, config.folderId)) { markUploaded(config, job, file, deadline); return }
        uri = null // Restart with the SAME durable remote ID, never a new create identity.
      }
    }
    if (uri == null) {
      ensureCurrent(config, deadline); checkLocal(file, job)
      try { uri = client.initiate(token, job, config.folderId) }
      catch (failure: DriveBackupFailure) {
        if (failure.code != "REMOTE_ALREADY_EXISTS") throw failure
        ensureCurrent(config, deadline)
        if (client.verifiedRemote(token, job, config.folderId)) { markUploaded(config, job, file, deadline); return }
        throw DriveBackupFailure("NETWORK", retryable = true)
      }
      job.put("session", DriveBackupSecrets.encrypt(uri)).put("offset", 0); persist(config, job)
    }
    job.put("state", "uploading").put("offset", offset); job.remove("errorCode"); persist(config, job)
    DriveBackupQueue.phase(applicationContext, config, "uploading")
    val size = job.getLong("size")
    RandomAccessFile(file, "r").use { input ->
      var noProgress = 0
      while (offset < size) {
        ensureCurrent(config, deadline); checkLocal(file, job)
        val bytes = ByteArray(minOf(DriveBackupPolicy.CHUNK_BYTES.toLong(), size - offset).toInt())
        input.seek(offset); input.readFully(bytes)
        val reply = try { client.session(token, uri!!, size, bytes, offset) }
          catch (failure: DriveBackupFailure) {
            if (failure.code != "SESSION_EXPIRED") throw failure
            job.remove("session"); job.put("offset", 0); persist(config, job)
            throw DriveBackupFailure("NETWORK", retryable = true)
          }
        if (reply.complete) { verifyAndMark(client, token, config, job, file, deadline); return }
        if (reply.offset <= offset) { noProgress++; if (noProgress > 2) throw DriveBackupFailure("NETWORK", retryable = true) } else noProgress = 0
        offset = reply.offset; job.put("offset", offset); persist(config, job)
      }
    }
    verifyAndMark(client, token, config, job, file, deadline)
  }
  private fun verifyAndMark(client: DriveBackupClient, token: String, config: DriveBackupQueue.Config, job: JSONObject, file: File, deadline: Long) {
    ensureCurrent(config, deadline)
    if (!client.verifiedRemote(token, job, config.folderId)) throw DriveBackupFailure("NETWORK", retryable = true)
    markUploaded(config, job, file, deadline)
  }
  private fun markUploaded(config: DriveBackupQueue.Config, job: JSONObject, file: File, deadline: Long) {
    checkLocal(file, job); ensureCurrent(config, deadline)
    if (digest(file, config, deadline) != job.optString("md5")) throw DriveBackupFailure("LOCAL_FILE_CHANGED")
    job.put("state", "uploaded").put("offset", job.getLong("size")).put("uploadedAt", System.currentTimeMillis())
    job.remove("session"); job.remove("errorCode")
    if (!DriveBackupQueue.completeUpload(applicationContext, config, job) {
      DriveBackupNotifications.uploaded(applicationContext, config.destination, job.getString("id"))
    }) throw DriveBackupStopped()
  }
}
