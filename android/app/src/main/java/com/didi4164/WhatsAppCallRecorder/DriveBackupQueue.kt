package com.didi4164.WhatsAppCallRecorder

import android.content.Context
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
    val stream = disk.startWrite()
    try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); disk.finishWrite(stream); cached = value }
    catch (failure: Exception) { disk.failWrite(stream); throw failure }
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
  @Synchronized fun select(context: Context, email: String, accountId: String, folderId: String, folderName: String) {
    require(email.length in 3..320 && accountId.length in 1..256 && DriveBackupPolicy.validDriveId(folderId))
    val next = copy(context).put("generation", config(context).generation + 1).put("enabled", false)
      .put("email", email).put("accountId", accountId).put("folderId", folderId).put("folderName", folderName.take(240))
      .put("phase", "paused")
    next.remove("errorCode"); save(context, next)
  }
  @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
    val cfg = config(context)
    require(!enabled || cfg.connected)
    val next = copy(context).put("enabled", enabled).put("generation", cfg.generation + 1)
      .put("phase", if (!cfg.connected) "disconnected" else if (enabled) "ready" else "paused")
    next.remove("errorCode"); save(context, next)
  }
  @Synchronized fun disconnect(context: Context) {
    val next = copy(context).put("enabled", false).put("generation", config(context).generation + 1).put("phase", "disconnected")
    arrayOf("email", "accountId", "folderId", "folderName", "errorCode").forEach { next.remove(it) }
    // Keep upload receipts/remote IDs for a later reconnection to the same destination.
    save(context, next)
  }
  @Synchronized fun enqueue(context: Context, id: String, size: Long) {
    val cfg = config(context)
    if (!cfg.enabled || !cfg.connected || !DriveBackupPolicy.validRecordingId(id) || size <= 44) return
    val next = copy(context); val jobs = next.getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.optString("id") == id && job.optString("destination") == cfg.destination) return
    }
    jobs.put(JSONObject().put("id", id).put("destination", cfg.destination).put("size", size)
      .put("state", "pending").put("attempts", 0))
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
      "phase" to phase, "queuedCount" to pending, "uploadedCount" to uploaded, "failedCount" to failed,
      "uploadingId" to active?.takeIf { phase == "uploading" }?.optString("id"),
      "uploadedBytes" to (active?.takeIf { phase == "uploading" }?.optLong("offset") ?: 0).toDouble(),
      "totalBytes" to (active?.takeIf { phase == "uploading" }?.optLong("size") ?: 0).toDouble(), "errorCode" to code,
      "errorMessage" to code?.let { DriveBackupErrors.message(it) }, "items" to items)
  }
}
