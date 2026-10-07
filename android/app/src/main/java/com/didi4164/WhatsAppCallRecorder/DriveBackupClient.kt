package com.didi4164.WhatsAppCallRecorder

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** REST calls are only made by the user-authorized manager/worker; tokens remain native memory. */
class DriveBackupClient(private val transport: DriveHttpTransport = DriveHttpTransport()) {
  fun cancel() = transport.cancel()
  data class AccountInfo(val email: String, val permissionId: String)
  data class Folder(val id: String, val name: String)
  data class SessionReply(val complete: Boolean, val offset: Long)
  private val fields = "id,size,md5Checksum,mimeType,parents,appProperties,trashed"
  private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
  private fun request(method: String, url: String, token: String, body: ByteArray? = null,
      headers: Map<String, String> = emptyMap()): DriveHttpTransport.Response = try {
    transport.request(method, url, token, body, headers)
  } catch (_: IOException) { throw DriveBackupFailure("NETWORK", retryable = true) }
  private fun json(response: DriveHttpTransport.Response): JSONObject = try {
    JSONObject(String(response.body, Charsets.UTF_8))
  } catch (_: Exception) { throw DriveBackupFailure("REMOTE_MISMATCH") }
  private fun requireSuccess(response: DriveHttpTransport.Response) {
    if (response.status in 200..299) return
    val reason = runCatching { json(response).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason") }.getOrDefault("")
    when {
      response.status == 401 -> throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
      reason == "accessNotConfigured" || reason == "serviceDisabled" -> throw DriveBackupFailure("CONFIGURATION_REQUIRED")
      reason == "insufficientPermissions" -> throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
      reason == "storageQuotaExceeded" -> throw DriveBackupFailure("STORAGE_FULL")
      DriveBackupPolicy.retryable(response.status, reason) -> throw DriveBackupFailure(if (response.status == 403 || response.status == 429) "RATE_LIMIT" else "NETWORK", retryable = true)
      response.status == 403 || response.status == 404 -> throw DriveBackupFailure("FOLDER_UNAVAILABLE")
      else -> throw DriveBackupFailure("HTTP_ERROR")
    }
  }
  fun account(token: String): AccountInfo {
    val response = request("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,permissionId)", token)
    requireSuccess(response); val user = json(response).getJSONObject("user")
    val email = user.optString("emailAddress"); val id = user.optString("permissionId")
    if (email.length !in 3..320 || id.length !in 1..256 || email.contains('\n') || email.contains('\r')) throw DriveBackupFailure("ACCOUNT_CHANGED")
    return AccountInfo(email, id)
  }
  fun folder(token: String, id: String): Folder {
    if (!DriveBackupPolicy.validDriveId(id)) throw DriveBackupFailure("FOLDER_UNAVAILABLE")
    val response = request("GET", "https://www.googleapis.com/drive/v3/files/${enc(id)}?supportsAllDrives=true&fields=id,name,mimeType,trashed,capabilities(canAddChildren)", token)
    requireSuccess(response); val item = json(response)
    if (item.optString("id") != id || item.optString("mimeType") != "application/vnd.google-apps.folder" ||
        item.optBoolean("trashed") || item.optJSONObject("capabilities")?.optBoolean("canAddChildren") != true) throw DriveBackupFailure("FOLDER_UNAVAILABLE")
    return Folder(id, item.optString("name").take(240))
  }
  fun generateId(token: String): String {
    val response = request("GET", "https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files", token)
    requireSuccess(response); val id = json(response).getJSONArray("ids").optString(0)
    if (!DriveBackupPolicy.validDriveId(id)) throw DriveBackupFailure("REMOTE_MISMATCH")
    return id
  }
  /** A 404 only means this exact reservation is not accessible; caller decides whether a create is permitted. */
  fun managedFolder(token: String, intent: DriveManagedFolder): Folder? {
    val response = request("GET", "https://www.googleapis.com/drive/v3/files/${enc(intent.folderId)}?fields=${enc("id,name,mimeType,trashed,ownedByMe,driveId,capabilities(canAddChildren),appProperties")}", token)
    if (response.status == 404) return null
    requireSuccess(response)
    val item = json(response)
    if (item.optString("id") != intent.folderId || item.optString("mimeType") != "application/vnd.google-apps.folder" ||
        item.optBoolean("trashed") || item.optBoolean("ownedByMe") != true || item.optString("driveId").isNotEmpty() ||
        item.optJSONObject("capabilities")?.optBoolean("canAddChildren") != true ||
        item.optJSONObject("appProperties")?.optString(DriveManagedFolder.PROPERTY) != intent.marker) {
      throw DriveBackupFailure("REMOTE_MISMATCH")
    }
    return Folder(intent.folderId, item.optString("name").take(240))
  }
  /** ID/marker must already be durable. Both successful creates and 409 conflicts require a subsequent exact GET. */
  fun createManagedFolder(token: String, intent: DriveManagedFolder) {
    if (intent.confirmed) throw DriveBackupFailure("FOLDER_UNAVAILABLE")
    val metadata = JSONObject().put("id", intent.folderId).put("name", "wa-reco")
      .put("mimeType", "application/vnd.google-apps.folder")
      .put("appProperties", JSONObject().put(DriveManagedFolder.PROPERTY, intent.marker))
    val response = request("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token,
      metadata.toString().toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/json; charset=UTF-8"))
    if (response.status != 409) requireSuccess(response)
  }
  /** Reconcile a possibly committed create before ever starting/restarting an upload. */
  fun verifiedRemote(token: String, job: JSONObject, folderId: String): Boolean {
    val id = job.optString("remoteId")
    if (!DriveBackupPolicy.validDriveId(id)) throw DriveBackupFailure("REMOTE_MISMATCH")
    val response = request("GET", "https://www.googleapis.com/drive/v3/files/${enc(id)}?supportsAllDrives=true&fields=${enc(fields)}", token)
    if (response.status == 404) return false
    requireSuccess(response); val item = json(response)
    val parents = item.optJSONArray("parents") ?: JSONArray()
    if (item.optBoolean("trashed") || item.optString("mimeType") != "audio/wav" || parents.length() != 1 ||
        !DriveBackupPolicy.verified(id, item.optString("id"), job.optLong("size"), item.optString("size").toLongOrNull() ?: -1,
          job.optString("md5"), item.optString("md5Checksum"), folderId, parents.optString(0), job.optString("id"),
          item.optJSONObject("appProperties")?.optString("codakiRecorderId"))) throw DriveBackupFailure("REMOTE_MISMATCH")
    return true
  }
  fun initiate(token: String, job: JSONObject, folderId: String): String {
    val id = job.optString("remoteId")
    if (!DriveBackupPolicy.validDriveId(id) || !DriveBackupPolicy.validMd5(job.optString("md5"))) throw DriveBackupFailure("REMOTE_MISMATCH")
    val fileName = try { RecordingNames.driveFileName(job.getString("id"), job.optString("fileName").takeIf { job.has("fileName") }) }
      catch (_: Exception) { throw DriveBackupFailure("REMOTE_MISMATCH") }
    val metadata = JSONObject().put("id", id).put("name", fileName)
      .put("mimeType", "audio/wav").put("parents", JSONArray().put(folderId))
      .put("appProperties", JSONObject().put("codakiRecorderId", job.getString("id")).put("codakiSourceMd5", job.getString("md5")))
    val response = request("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=${enc(fields)}", token,
      metadata.toString().toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/json; charset=UTF-8",
        "X-Upload-Content-Type" to "audio/wav", "X-Upload-Content-Length" to job.getLong("size").toString()))
    if (response.status == 409) throw DriveBackupFailure("REMOTE_ALREADY_EXISTS", retryable = true)
    requireSuccess(response)
    return try { DriveBackupPolicy.trustedUploadUrl(response.location) } catch (_: Exception) { throw DriveBackupFailure("REMOTE_MISMATCH") }
  }
  fun session(token: String, uri: String, size: Long, data: ByteArray? = null, offset: Long = 0): SessionReply {
    val safe = try { DriveBackupPolicy.trustedUploadUrl(uri) } catch (_: Exception) { throw DriveBackupFailure("REMOTE_MISMATCH") }
    val bytes = data ?: ByteArray(0)
    val range = if (data == null) "bytes */$size" else "bytes $offset-${offset + bytes.size - 1}/$size"
    val response = request("PUT", safe, token, bytes, mapOf("Content-Type" to "audio/wav", "Content-Range" to range))
    if (response.status == 404 || response.status == 410) throw DriveBackupFailure("SESSION_EXPIRED")
    if (response.status == 308) {
      val acknowledged = try { DriveBackupPolicy.acknowledgedOffset(response.range, size) }
        catch (_: Exception) { throw DriveBackupFailure("REMOTE_MISMATCH") }
      if (data != null && acknowledged > offset + bytes.size) throw DriveBackupFailure("REMOTE_MISMATCH")
      return SessionReply(false, acknowledged)
    }
    requireSuccess(response)
    return SessionReply(true, size)
  }
}
