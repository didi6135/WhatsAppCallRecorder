package com.didi4164.WhatsAppCallRecorder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/** Offline protocol integration tests: real JSON/client/transport, synthetic data only. */
class DriveBackupClientTest {
  private val recording = "1791280000000-abcdef12"
  private val md5 = "0123456789abcdef0123456789abcdef"
  private val folder = "folder_A"
  private val remote = "remote_A"
  private val session = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=test"
  private data class Reply(val code: Int, val body: String = "{}", val location: String? = null, val range: String? = null)
  private class Connection(url: URL, private val reply: Reply) : HttpURLConnection(url) {
    val written = ByteArrayOutputStream()
    val sentHeaders = mutableMapOf<String, String>()
    var closed = false
    override fun connect() { }
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode() = reply.code
    override fun getInputStream() = ByteArrayInputStream(reply.body.toByteArray())
    override fun getErrorStream() = ByteArrayInputStream(reply.body.toByteArray())
    override fun getOutputStream() = written
    override fun setRequestProperty(key: String, value: String) { sentHeaders[key] = value }
    override fun getHeaderField(name: String?): String? = when (name) { "Location" -> reply.location; "Range" -> reply.range; else -> null }
  }
  private class Server(vararg replies: Reply) {
    val pending = ArrayDeque(replies.toList())
    val calls = mutableListOf<Connection>()
    val client = DriveBackupClient(DriveHttpTransport { url ->
      check(pending.isNotEmpty()) { "Unexpected synthetic request" }
      Connection(url, pending.removeFirst()).also { calls.add(it) }
    })
  }
  private fun job() = JSONObject().put("id", recording).put("remoteId", remote).put("size", 64L).put("md5", md5)
  private fun receipt() = JSONObject().put("id", remote).put("size", "64").put("md5Checksum", md5)
    .put("mimeType", "audio/wav").put("trashed", false).put("parents", JSONArray().put(folder))
    .put("appProperties", JSONObject().put("codakiRecorderId", recording))
  private fun folderBody() = JSONObject().put("id", folder).put("name", "Synthetic backups")
    .put("mimeType", "application/vnd.google-apps.folder").put("trashed", false)
    .put("capabilities", JSONObject().put("canAddChildren", true))
  private fun failure(code: String, action: () -> Unit): DriveBackupFailure {
    try { action(); fail("Expected $code") }
    catch (error: DriveBackupFailure) { assertEquals(code, error.code); return error }
    throw AssertionError("Expected typed Drive failure")
  }

  @Test fun actualTokenAccountRequiresBothEmailAndStablePermissionId() {
    val server = Server(Reply(200, "{\"user\":{\"emailAddress\":\"synthetic@example.test\",\"permissionId\":\"user_A\"}}"),
      Reply(200, "{\"user\":{\"emailAddress\":\"synthetic@example.test\"}}"))
    assertEquals("user_A", server.client.account("synthetic-token").permissionId)
    failure("ACCOUNT_CHANGED") { server.client.account("synthetic-token") }
    assertTrue(server.calls.all { it.closed })
  }
  @Test fun folderMustBeSelectedWritableNonTrashedFolder() {
    val cases = listOf(folderBody().put("mimeType", "audio/wav"), folderBody().put("trashed", true),
      folderBody().put("id", "another_folder"), folderBody().put("capabilities", JSONObject().put("canAddChildren", false)))
    cases.forEach { value -> failure("FOLDER_UNAVAILABLE") { Server(Reply(200, value.toString())).client.folder("synthetic-token", folder) } }
    assertEquals(folder, Server(Reply(200, folderBody().toString())).client.folder("synthetic-token", folder).id)
  }
  @Test fun missingFolderCapabilitiesFailsWithActionableError() {
    val item = folderBody(); item.remove("capabilities")
    failure("FOLDER_UNAVAILABLE") { Server(Reply(200, item.toString())).client.folder("synthetic-token", folder) }
  }
  @Test fun committedUploadReconcilesExactReceiptBeforeRetryingCreate() {
    val server = Server(Reply(200, receipt().toString()))
    assertTrue(server.client.verifiedRemote("synthetic-token", job(), folder))
    assertEquals(1, server.calls.size)
    assertEquals("GET", server.calls.single().requestMethod)
    assertTrue(server.calls.single().url.path.endsWith(remote))
  }
  @Test fun http200CannotValidateWrongBytesHashParentOrRecording() {
    val cases = listOf(receipt().put("size", "63"), receipt().put("md5Checksum", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
      receipt().put("parents", JSONArray().put("another_folder")), receipt().put("id", "another_file"),
      receipt().put("trashed", true), receipt().put("mimeType", "text/plain"),
      receipt().put("appProperties", JSONObject().put("codakiRecorderId", "1791280000001-abcdef12")))
    cases.forEach { item -> failure("REMOTE_MISMATCH") { Server(Reply(200, item.toString())).client.verifiedRemote("synthetic-token", job(), folder) } }
    assertFalse(Server(Reply(404)).client.verifiedRemote("synthetic-token", job(), folder))
  }
  @Test fun serverAcknowledgementOwnsResumeOffsetAndCannotExceedSentChunk() {
    val server = Server(Reply(308), Reply(308, range = "bytes=0-15"), Reply(308, range = "bytes=0-60"))
    assertEquals(0L, server.client.session("synthetic-token", session, 64).offset)
    assertEquals(16L, server.client.session("synthetic-token", session, 64).offset)
    failure("REMOTE_MISMATCH") { server.client.session("synthetic-token", session, 64, ByteArray(32), 16) }
    assertEquals("bytes */64", server.calls[0].sentHeaders["Content-Range"])
    assertEquals("bytes 16-47/64", server.calls[2].sentHeaders["Content-Range"])
    assertFalse(server.calls[2].instanceFollowRedirects)
  }
  @Test fun expiredSessionPreservesSameIdentityForReconciliation() {
    val server = Server(Reply(410), Reply(200, receipt().toString()))
    failure("SESSION_EXPIRED") { server.client.session("synthetic-token", session, 64) }
    assertTrue(server.client.verifiedRemote("synthetic-token", job(), folder))
    assertEquals("GET", server.calls.last().requestMethod)
  }
  @Test fun unauthorizedAndRateLimitHaveDistinctRecoveryPaths() {
    val server = Server(Reply(401), Reply(403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"),
      Reply(403, "{\"error\":{\"errors\":[{\"reason\":\"storageQuotaExceeded\"}]}}"))
    assertTrue(failure("AUTH_REQUIRED") { server.client.account("synthetic-token") }.auth)
    assertTrue(failure("RATE_LIMIT") { server.client.account("synthetic-token") }.retryable)
    assertFalse(failure("STORAGE_FULL") { server.client.account("synthetic-token") }.retryable)
  }
  @Test fun untrustedSessionLocationCannotReceiveCredentials() {
    val server = Server(Reply(200, location = "https://untrusted.example.test/upload"))
    failure("REMOTE_MISMATCH") { server.client.initiate("synthetic-token", job(), folder) }
    assertEquals(1, server.calls.size)
    val metadata = JSONObject(server.calls.single().written.toString("UTF-8"))
    assertEquals(remote, metadata.getString("id"))
    assertEquals(folder, metadata.getJSONArray("parents").getString(0))
  }
  @Test fun createUsesFrozenUnicodeNameAndLegacyJobKeepsItsOriginalName() {
    val name = RecordingNames.exportFileName(recording, "דוד Smith 👩‍💻")
    val server = Server(Reply(200, location = session), Reply(200, location = session))
    server.client.initiate("synthetic-token", job().put("fileName", name), folder)
    server.client.initiate("synthetic-token", job(), folder)
    assertEquals(name, JSONObject(server.calls[0].written.toString("UTF-8")).getString("name"))
    assertEquals("recording-$recording.wav", JSONObject(server.calls[1].written.toString("UTF-8")).getString("name"))
    assertEquals(recording, JSONObject(server.calls[0].written.toString("UTF-8")).getJSONObject("appProperties").getString("codakiRecorderId"))
  }
  @Test fun invalidFrozenNameFailsBeforeDispatchingARequest() {
    val server = Server()
    failure("REMOTE_MISMATCH") { server.client.initiate("synthetic-token", job().put("fileName", "../wrong.wav"), folder) }
    assertTrue(server.calls.isEmpty())
  }
}
