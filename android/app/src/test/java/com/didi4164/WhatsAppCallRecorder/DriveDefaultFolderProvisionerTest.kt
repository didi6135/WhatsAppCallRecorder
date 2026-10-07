package com.didi4164.WhatsAppCallRecorder

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

/** Executes production provisioning/client/transport against synthetic provider replies; no live OAuth. */
class DriveDefaultFolderProvisionerTest {
  private val marker = "0123456789abcdef0123456789abcdef"
  private val intent = DriveManagedFolder("account_A", "folder_A", marker, false)
  private data class Reply(val code: Int, val body: String = "{}", val lost: Boolean = false)
  private class Connection(url: URL, private val reply: Reply) : HttpURLConnection(url) {
    val written = ByteArrayOutputStream()
    override fun connect() { }
    override fun disconnect() { }
    override fun usingProxy() = false
    override fun getResponseCode(): Int { if (reply.lost) throw IOException("Synthetic lost acknowledgement"); return reply.code }
    override fun getInputStream() = ByteArrayInputStream(reply.body.toByteArray())
    override fun getErrorStream() = ByteArrayInputStream(reply.body.toByteArray())
    override fun getOutputStream() = written
  }
  private class Server(vararg replies: Reply) {
    val pending = ArrayDeque(replies.toList())
    val calls = mutableListOf<Connection>()
    val client = DriveBackupClient(DriveHttpTransport { url ->
      check(pending.isNotEmpty()) { "Unexpected synthetic request: ${url.path}" }
      Connection(url, pending.removeFirst()).also { calls.add(it) }
    })
  }
  private fun account(id: String = "account_A") = Reply(200, JSONObject().put("user", JSONObject()
    .put("emailAddress", "synthetic@example.invalid").put("permissionId", id)).toString())
  private fun folder(): JSONObject = JSONObject().put("id", intent.folderId).put("name", "wa-reco")
    .put("mimeType", "application/vnd.google-apps.folder").put("trashed", false).put("ownedByMe", true)
    .put("capabilities", JSONObject().put("canAddChildren", true))
    .put("appProperties", JSONObject().put(DriveManagedFolder.PROPERTY, marker))
  private fun failure(code: String, action: () -> Unit) {
    try { action(); fail("Expected $code") }
    catch (error: DriveBackupFailure) { assertEquals(code, error.code) }
  }
  private class Journal(var value: DriveManagedFolder? = null) {
    var commits = 0
    var failSave = false
    var active = true
    val events = mutableListOf<String>()
    fun provision(server: Server, marker: String): DriveDefaultFolderProvisioner = DriveDefaultFolderProvisioner(server.client,
      { value },
      { account, id, operation ->
        events.add("reserve")
        if (failSave) throw DriveBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
        DriveManagedFolder(account.permissionId, id, operation, false).also { value = it }
      },
      { account, reserved, folder ->
        check(active); assertEquals(account.permissionId, reserved.accountId); assertEquals(reserved.folderId, folder.id)
        events.add("commit"); value = reserved.copy(confirmed = true); commits++
      },
      { if (!active) throw DriveBackupFailure("FOREGROUND_REQUIRED") }, { marker })
  }
  @Test fun reservePrecedesPostAndSuccessRequiresVerifiedOwnedMarkerReceipt() {
    val server = Server(account(), Reply(200, "{\"ids\":[\"folder_A\"]}"), Reply(404), Reply(200), Reply(200, folder().toString()))
    val journal = Journal()
    journal.provision(server, marker).connect("synthetic-token")
    assertEquals(listOf("reserve", "commit"), journal.events)
    assertEquals(1, journal.commits); assertTrue(journal.value!!.confirmed)
    assertEquals(listOf("GET", "GET", "GET", "POST", "GET"), server.calls.map { it.requestMethod })
    val body = JSONObject(server.calls[3].written.toString("UTF-8"))
    assertEquals(intent.folderId, body.getString("id")); assertEquals("wa-reco", body.getString("name"))
    assertEquals(marker, body.getJSONObject("appProperties").getString(DriveManagedFolder.PROPERTY))
  }
  @Test fun failedDurableReservationNeverStartsFolderCreation() {
    val server = Server(account(), Reply(200, "{\"ids\":[\"folder_A\"]}"))
    val journal = Journal().apply { failSave = true }
    failure("LOCAL_QUEUE_UNAVAILABLE") { journal.provision(server, marker).connect("synthetic-token") }
    assertEquals(0, journal.commits); assertNull(journal.value)
    assertTrue(server.calls.all { it.requestMethod == "GET" })
  }
  @Test fun lostPostAcknowledgementReconcilesSameIdentityWithoutAnotherCreate() {
    val first = Server(account(), Reply(404), Reply(200, lost = true))
    val journal = Journal(intent)
    failure("NETWORK") { journal.provision(first, marker).connect("synthetic-token") }
    assertEquals(intent, journal.value); assertEquals(0, journal.commits)
    val retry = Server(account(), Reply(200, folder().toString()))
    journal.provision(retry, marker).connect("synthetic-token")
    assertEquals(1, journal.commits); assertTrue(retry.calls.none { it.requestMethod == "POST" })
    assertTrue(retry.calls.last().url.path.endsWith(intent.folderId))
  }
  @Test fun reservedMissingIdRetriesOnlyThatIdAnd409RequiresVerification() {
    val server = Server(account(), Reply(404), Reply(409), Reply(200, folder().toString()))
    val journal = Journal(intent)
    journal.provision(server, marker).connect("synthetic-token")
    assertEquals(1, journal.commits)
    assertEquals(intent.folderId, JSONObject(server.calls[2].written.toString("UTF-8")).getString("id"))
    assertTrue(server.calls.none { it.url.path.endsWith("generateIds") })
    val missing = Server(account(), Reply(404), Reply(409), Reply(404))
    val pending = Journal(intent)
    failure("NETWORK") { pending.provision(missing, marker).connect("synthetic-token") }
    assertEquals(0, pending.commits); assertFalse(pending.value!!.confirmed)
  }
  @Test fun confirmedMissingFolderDoesNotSilentlyCreateReplacement() {
    val server = Server(account(), Reply(404))
    val journal = Journal(intent.copy(confirmed = true))
    failure("FOLDER_UNAVAILABLE") { journal.provision(server, marker).connect("synthetic-token") }
    assertEquals(0, journal.commits); assertTrue(server.calls.none { it.requestMethod == "POST" })
  }
  @Test fun wrongAccountReservationCannotReceiveCreateOrBecomeDestination() {
    val server = Server(account("account_B"))
    val journal = Journal(intent)
    failure("ACCOUNT_CHANGED") { journal.provision(server, marker).connect("synthetic-token") }
    assertEquals(1, server.calls.size); assertEquals(0, journal.commits)
  }
  @Test fun everyManagedFolderMustHaveExactIdMarkerOwnershipAndWritableFolderType() {
    val cases = listOf(folder().put("id", "other_folder"), folder().put("mimeType", "audio/wav"),
      folder().put("trashed", true), folder().put("ownedByMe", false), folder().put("driveId", "shared_drive"),
      folder().put("capabilities", JSONObject().put("canAddChildren", false)),
      folder().put("appProperties", JSONObject().put(DriveManagedFolder.PROPERTY, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
      folder().apply { remove("appProperties") }, folder().apply { remove("ownedByMe") })
    for (body in cases) {
      val server = Server(account(), Reply(200, body.toString())); val journal = Journal(intent)
      failure("REMOTE_MISMATCH") { journal.provision(server, marker).connect("synthetic-token") }
      assertEquals(0, journal.commits); assertTrue(server.calls.none { it.requestMethod == "POST" })
    }
  }
  @Test fun cancellationBeforeCreateAndBeforeCommitLeaveReservationUnselected() {
    val beforeCreate = Server(account(), Reply(404))
    val journal = Journal(intent)
    var checks = 0
    val provision = DriveDefaultFolderProvisioner(beforeCreate.client, { intent }, { _, _, _ -> error("Unexpected reserve") },
      { _, _, _ -> fail("Canceled commit ran") }, { if (++checks == 4) throw DriveBackupFailure("FOREGROUND_REQUIRED") })
    failure("FOREGROUND_REQUIRED") { provision.connect("synthetic-token") }
    assertTrue(beforeCreate.calls.none { it.requestMethod == "POST" })
    val beforeCommit = Server(account(), Reply(200, folder().toString()))
    checks = 0
    val second = DriveDefaultFolderProvisioner(beforeCommit.client, { intent }, { _, _, _ -> error("Unexpected reserve") },
      { _, _, _ -> fail("Canceled commit ran") }, { if (++checks == 4) throw DriveBackupFailure("RECORDING_BUSY") })
    failure("RECORDING_BUSY") { second.connect("synthetic-token") }
    assertEquals(0, journal.commits)
  }
  @Test fun cancellationAfterTransmittedCreateKeepsExactReservationForReconciliation() {
    val server = Server(account(), Reply(404), Reply(200))
    val journal = Journal(intent)
    var checks = 0
    val provision = DriveDefaultFolderProvisioner(server.client, { journal.value }, { _, _, _ -> error("Unexpected reserve") },
      { _, _, _ -> fail("Canceled commit ran") }, { if (++checks == 5) throw DriveBackupFailure("FOREGROUND_REQUIRED") })
    failure("FOREGROUND_REQUIRED") { provision.connect("synthetic-token") }
    assertEquals(intent, journal.value); assertEquals(0, journal.commits)
    assertEquals(1, server.calls.count { it.requestMethod == "POST" })
    val retry = Server(account(), Reply(200, folder().toString()))
    journal.provision(retry, marker).connect("synthetic-token")
    assertEquals(1, journal.commits); assertTrue(retry.calls.none { it.requestMethod == "POST" })
  }
}
