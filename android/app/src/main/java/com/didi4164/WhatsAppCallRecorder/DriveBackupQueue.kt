package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One process owns this atomic journal. No access/refresh tokens are stored here. */
object DriveBackupQueue {
  data class Config(val generation: Long, val enabled: Boolean, val email: String, val accountId: String,
      val folderId: String, val folderName: String) {
    val destination: String get() = "$accountId:$folderId"
    val connected: Boolean get() = email.isNotEmpty() && accountId.isNotEmpty() && DriveBackupPolicy.validDriveId(folderId)
  }
  private var cached: JSONObject? = null
  private var unavailable = false
  private var activeGeneration: Long? = null
  private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "drive-backup-v1.json"))
  private fun state(context: Context): JSONObject {
    cached?.let { return it }
    val disk = file(context)
    val loaded = if (disk.baseFile.exists() || File(disk.baseFile.path + ".bak").exists()) {
      try {
        disk.openRead().use { input ->
          require(input.available() <= 8 * 1024 * 1024)
          JSONObject(input.bufferedReader().readText()).also(::validate)
        }
      } catch (_: Exception) {
        unavailable = true
        fresh().put("phase", "error").put("errorCode", "LOCAL_QUEUE_UNAVAILABLE")
      }
    } else fresh()
    cached = loaded
    return loaded
  }
  private fun fresh() = JSONObject().put("version", 1).put("generation", 1L).put("enabled", false)
    .put("phase", "disconnected").put("jobs", JSONArray())
  private fun validate(value: JSONObject) {
    require(value.optInt("version") == 1 && value.optLong("generation") > 0)
    val cfg = config(value)
    require(!cfg.enabled || cfg.connected)
    if (value.has("managedFolders")) {
      val managed = value.getJSONArray("managedFolders")
      require(managed.length() <= 512)
      val accounts = mutableSetOf<String>(); val remoteIds = mutableSetOf<String>()
      for (i in 0 until managed.length()) {
        val entry = managed.getJSONObject(i)
        require(entry.get("confirmed") is Boolean)
        val intent = managed(entry)
        require(accounts.add(intent.accountId) && remoteIds.add(intent.folderId))
      }
    }
    val jobs = value.getJSONArray("jobs")
    val identities = mutableSetOf<String>()
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      val id = job.getString("id"); val destination = job.getString("destination")
      val separator = destination.lastIndexOf(':')
      require(DriveBackupPolicy.validRecordingId(id) && separator in 1..256 &&
        DriveBackupPolicy.validDriveId(destination.substring(separator + 1)) && identities.add("$destination/$id"))
      val size = job.getLong("size")
      require(size > 44 && job.optLong("offset") in 0..size && job.optInt("attempts") >= 0)
      require(job.getString("state") in setOf("pending", "uploading", "uploaded", "failed"))
      if (job.has("fileName")) require(job.get("fileName") is String && RecordingNames.validExportFileName(id, job.getString("fileName")))
      if (job.has("remoteId")) require(DriveBackupPolicy.validDriveId(job.getString("remoteId")))
      if (job.has("md5")) require(DriveBackupPolicy.validMd5(job.getString("md5")))
      if (job.has("session")) require(job.getString("session").length in 1..8192 && job.has("remoteId") && job.has("md5"))
      if (job.optString("state") == "uploaded") require(job.has("remoteId") && job.has("md5") && job.optLong("offset") == size)
    }
  }
  private fun save(context: Context, value: JSONObject) {
    state(context)
    check(!unavailable) { "LOCAL_QUEUE_UNAVAILABLE" }
    val disk = file(context)
    val bytes = value.toString().toByteArray(Charsets.UTF_8)
    if (bytes.size > 8 * 1024 * 1024) throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    val stream = try { disk.startWrite() } catch (_: Exception) { throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE") }
    try {
      stream.write(bytes)
      // Framework AtomicFile can log sync/rename failures instead of throwing. Never trust its return alone.
      stream.fd.sync()
      disk.finishWrite(stream)
      val committed = disk.baseFile
      check(committed.length() == bytes.size.toLong() && committed.readBytes().contentEquals(bytes))
      val parent = committed.parentFile!!
      check(parent.isDirectory)
      val directory = Os.open(parent.absolutePath, OsConstants.O_RDONLY, 0)
      try { Os.fsync(directory) } finally { Os.close(directory) }
      cached = value
    } catch (_: Exception) {
      runCatching { disk.failWrite(stream) }
      throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    }
  }
  private fun copy(context: Context) = JSONObject(state(context).toString())
  private fun config(value: JSONObject) = Config(value.optLong("generation", 1), value.optBoolean("enabled"),
    value.optString("email"), value.optString("accountId"), value.optString("folderId"), value.optString("folderName"))
  @Synchronized fun config(context: Context): Config = config(state(context))
  @Synchronized fun current(context: Context, expected: Config): Boolean {
    val actual = config(context)
    return DriveBackupPolicy.isCurrent(expected.generation, actual.generation, actual.enabled, expected.destination, actual.destination)
  }
  @Synchronized fun begin(context: Context, expected: Config) { if (current(context, expected)) activeGeneration = expected.generation }
  @Synchronized fun end(expected: Config) { if (activeGeneration == expected.generation) activeGeneration = null }
  @Synchronized fun blocked(context: Context): Boolean = state(context).optString("errorCode") in setOf(
    "AUTH_REQUIRED", "ACCOUNT_CHANGED", "CONFIGURATION_REQUIRED", "FOLDER_UNAVAILABLE", "STORAGE_FULL", "LOCAL_QUEUE_UNAVAILABLE")
  @Synchronized fun failedCode(context: Context, expected: Config): String? {
    val jobs = state(context).getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.optString("destination") == expected.destination && job.optString("state") == "failed") return job.optString("errorCode", "UPLOAD_FAILED")
    }
    return null
  }
  private fun managed(entry: JSONObject) = DriveManagedFolder(entry.getString("accountId"), entry.getString("folderId"),
    entry.getString("marker"), entry.getBoolean("confirmed"))
  private fun managedEntries(value: JSONObject): JSONArray = if (value.has("managedFolders")) value.getJSONArray("managedFolders") else JSONArray()
  @Synchronized fun managed(context: Context, accountId: String): DriveManagedFolder? {
    val value = state(context)
    if (unavailable) throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    val entries = managedEntries(value)
    for (i in 0 until entries.length()) {
      val candidate = managed(entries.getJSONObject(i))
      if (candidate.accountId == accountId) return candidate
    }
    return null
  }
  /** Reservation changes no current destination/generation. Its ID survives disconnects and uncertain creates. */
  @Synchronized fun reserveManaged(context: Context, expectedGeneration: Long, accountId: String, folderId: String, marker: String): DriveManagedFolder {
    if (config(context).generation != expectedGeneration) throw DriveBackupFailure("ACCOUNT_CHANGED")
    val intent = DriveManagedFolder(accountId, folderId, marker, false)
    managed(context, accountId)?.let { return it }
    val next = copy(context); val entries = managedEntries(next)
    if (entries.length() >= 512) throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    entries.put(JSONObject().put("accountId", accountId).put("folderId", folderId).put("marker", marker).put("confirmed", false))
    next.put("managedFolders", entries); save(context, next)
    return intent
  }
  private fun selection(value: JSONObject, email: String, accountId: String, folderId: String, folderName: String): JSONObject {
    require(email.length in 3..320 && accountId.length in 1..256 && DriveBackupPolicy.validDriveId(folderId))
    return value.put("generation", config(value).generation + 1).put("enabled", false)
      .put("email", email).put("accountId", accountId).put("folderId", folderId).put("folderName", folderName.take(240))
      .put("phase", "paused").also { it.remove("errorCode") }
  }
  @Synchronized fun select(context: Context, email: String, accountId: String, folderId: String, folderName: String, expectedGeneration: Long? = null) {
    if (expectedGeneration != null && config(context).generation != expectedGeneration) throw DriveBackupFailure("ACCOUNT_CHANGED")
    save(context, selection(copy(context), email, accountId, folderId, folderName))
  }
  /** Exact reservation confirmation and paused selection are one generation-conditional durable commit. */
  @Synchronized fun selectManaged(context: Context, expectedGeneration: Long, email: String, intent: DriveManagedFolder, folderName: String) {
    if (config(context).generation != expectedGeneration) throw DriveBackupFailure("ACCOUNT_CHANGED")
    if (managed(context, intent.accountId) != intent) throw DriveBackupFailure("REMOTE_MISMATCH")
    val next = copy(context); val entries = managedEntries(next)
    for (i in 0 until entries.length()) {
      val entry = entries.getJSONObject(i)
      if (entry.getString("accountId") == intent.accountId) entry.put("confirmed", true)
    }
    save(context, selection(next, email, intent.accountId, intent.folderId, folderName))
  }
  @Synchronized fun setEnabled(context: Context, enabled: Boolean, expectedGeneration: Long? = null, beforeCommit: () -> Unit = {}) {
    val cfg = config(context)
    if (expectedGeneration != null && cfg.generation != expectedGeneration) throw DriveBackupFailure("ACCOUNT_CHANGED")
    beforeCommit()
    require(!enabled || cfg.connected)
    val next = copy(context).put("enabled", enabled).put("generation", cfg.generation + 1)
      .put("phase", if (!cfg.connected) "disconnected" else if (enabled) "ready" else "paused")
    next.remove("errorCode"); save(context, next)
  }
  @Synchronized fun disconnect(context: Context, expectedGeneration: Long? = null, beforeCommit: () -> Unit = {}) {
    if (expectedGeneration != null && config(context).generation != expectedGeneration) throw DriveBackupFailure("ACCOUNT_CHANGED")
    beforeCommit()
    val next = copy(context).put("enabled", false).put("generation", config(context).generation + 1).put("phase", "disconnected")
    arrayOf("email", "accountId", "folderId", "folderName", "errorCode").forEach { next.remove(it) }
    // Keep upload receipts/remote IDs for a later reconnection to the same destination.
    save(context, next)
  }
  @Synchronized fun enqueue(context: Context, id: String, size: Long, fileName: String? = null) {
    val cfg = config(context)
    if (!cfg.enabled || !cfg.connected || !DriveBackupPolicy.validRecordingId(id) || size <= 44) return
    val next = copy(context); val jobs = next.getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.optString("id") == id && job.optString("destination") == cfg.destination) return
    }
    val job = JSONObject().put("id", id).put("destination", cfg.destination).put("size", size)
      .put("state", "pending").put("attempts", 0)
    // Freeze the intended remote name before generating an ID or starting a resumable session.
    if (fileName != null && RecordingNames.validExportFileName(id, fileName)) job.put("fileName", fileName)
    jobs.put(job)
    save(context, next)
  }
  @Synchronized fun next(context: Context, expected: Config): JSONObject? {
    if (!current(context, expected)) return null
    val jobs = state(context).getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.optString("destination") == expected.destination && job.optString("state") in listOf("pending", "uploading")) {
        return JSONObject(job.toString())
      }
    }
    return null
  }
  @Synchronized fun update(context: Context, expected: Config, job: JSONObject): Boolean {
    if (!current(context, expected)) return false
    val next = copy(context); val jobs = next.getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val old = jobs.getJSONObject(i)
      if (old.optString("id") == job.optString("id") && old.optString("destination") == expected.destination) {
        // A resumed legacy session retains its old name, and new jobs retain their frozen name.
        require(old.has("fileName") == job.has("fileName") && old.optString("fileName") == job.optString("fileName"))
        jobs.put(i, JSONObject(job.toString())); save(context, next); return true
      }
    }
    return false
  }
  @Synchronized fun phase(context: Context, expected: Config, phase: String, code: String? = null) {
    if (!current(context, expected)) return
    val next = copy(context).put("phase", phase)
    if (code == null) next.remove("errorCode") else next.put("errorCode", code)
    save(context, next)
  }
  @Synchronized fun retry(context: Context) {
    val next = copy(context); val cfg = config(next); val jobs = next.getJSONArray("jobs")
    next.put("generation", cfg.generation + 1)
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.optString("destination") == cfg.destination && job.optString("state") == "failed") {
        job.put("state", "pending").put("attempts", 0); job.remove("errorCode")
      }
    }
    if (cfg.enabled) next.put("phase", "ready")
    next.remove("errorCode"); save(context, next)
  }
  @Synchronized fun status(context: Context): Map<String, Any?> {
    val value = state(context); val cfg = config(value); val jobs = value.getJSONArray("jobs")
    var pending = 0; var uploaded = 0; var failed = 0; var active: JSONObject? = null
    val items = mutableListOf<Map<String, Any?>>()
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.optString("destination") != cfg.destination) continue
      val status = job.optString("state").let { if (it == "uploading" && activeGeneration != cfg.generation) "pending" else it }
      when (status) { "uploaded" -> uploaded++; "failed" -> failed++; else -> pending++ }
      if (status == "uploading") active = job
      if (items.size < 1000) items.add(mapOf("id" to job.optString("id"), "state" to status,
        "errorCode" to job.optString("errorCode").takeIf { it.isNotEmpty() }))
    }
    val phase = if (unavailable) "error" else if (!cfg.connected) "disconnected" else if (!cfg.enabled) "paused"
      else value.optString("phase", "ready").let { if (it == "uploading" && activeGeneration != cfg.generation) "ready" else it }
    val code = value.optString("errorCode").takeIf { it.isNotEmpty() }
    return mapOf("enabled" to cfg.enabled, "connected" to cfg.connected, "accountEmail" to cfg.email.takeIf { it.isNotEmpty() },
      "folderId" to cfg.folderId.takeIf { it.isNotEmpty() }, "folderName" to cfg.folderName.takeIf { it.isNotEmpty() },
      "folderSource" to if (!cfg.connected) null else if (managed(context, cfg.accountId)?.let { it.confirmed && it.folderId == cfg.folderId } == true) "managed" else "selected",
      "phase" to phase, "queuedCount" to pending, "uploadedCount" to uploaded, "failedCount" to failed,
      "uploadingId" to active?.takeIf { phase == "uploading" }?.optString("id"),
      "uploadedBytes" to (active?.takeIf { phase == "uploading" }?.optLong("offset") ?: 0).toDouble(),
      "totalBytes" to (active?.takeIf { phase == "uploading" }?.optLong("size") ?: 0).toDouble(), "errorCode" to code,
      "errorMessage" to code?.let { DriveBackupErrors.message(it) }, "items" to items)
  }
}
