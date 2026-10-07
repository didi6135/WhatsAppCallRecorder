package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Executes the production journal/state logic against synthetic local files and explicit host shims. */
object DriveBackupQueueHostTest {
  private var checks = 0
  private fun verify(condition: Boolean) { checks++; check(condition) { "Check $checks" } }
  private fun reset() {
    for ((name, value) in listOf("cached" to null, "unavailable" to false, "activeGeneration" to null)) {
      DriveBackupQueue.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(DriveBackupQueue, value)
    }
  }
  private fun reject(action: () -> Unit) { checks++; try { action() } catch (_: Exception) { return }; error("Expected rejection $checks") }
  @JvmStatic fun main(args: Array<String>) {
    val root = File(args[0]); root.mkdirs()
    val context = Context(Files.createTempDirectory(root.toPath(), "journal-").toFile())
    verify(DriveBackupQueue.status(context)["phase"] == "disconnected")
    DriveBackupQueue.select(context, "synthetic@example.invalid", "account", "folder", "Synthetic")
    verify(!DriveBackupQueue.config(context).enabled)
    DriveBackupQueue.enqueue(context, "1-deadbeef", 100)
    verify(DriveBackupQueue.status(context)["queuedCount"] == 0)
    DriveBackupQueue.setEnabled(context, true)
    val original = DriveBackupQueue.config(context)
    DriveBackupQueue.enqueue(context, "1-deadbeef", 100)
    DriveBackupQueue.enqueue(context, "1-deadbeef", 100)
    DriveBackupQueue.enqueue(context, "2-deadbeef", 200)
    DriveBackupQueue.enqueue(context, "../../unsafe", 200)
    DriveBackupQueue.enqueue(context, "3-deadbeef", 44)
    verify(DriveBackupQueue.status(context)["queuedCount"] == 2)
    val first = DriveBackupQueue.next(context, original)!!
    first.put("remoteId", "stable_remote_id").put("md5", "0123456789abcdef0123456789abcdef")
      .put("session", "syntheticEncryptedSession").put("offset", 50).put("state", "uploading")
    verify(DriveBackupQueue.update(context, original, first))
    DriveBackupQueue.begin(context, original); DriveBackupQueue.phase(context, original, "uploading")
    verify(DriveBackupQueue.status(context)["uploadingId"] == "1-deadbeef")
    DriveBackupQueue.end(original)
    verify(DriveBackupQueue.status(context)["uploadingId"] == null)
    verify(DriveBackupQueue.status(context)["phase"] == "ready")
    reset() // Process loss: durable identity/session remain; transient uploading does not.
    verify(DriveBackupQueue.status(context)["queuedCount"] == 2)
    verify(DriveBackupQueue.status(context)["uploadingId"] == null)
    var cfg = DriveBackupQueue.config(context)
    var resumed = DriveBackupQueue.next(context, cfg)!!
    verify(resumed.optString("remoteId") == "stable_remote_id" && resumed.optLong("offset") == 50L)
    resumed.put("state", "failed").put("errorCode", "LOCAL_FILE_MISSING")
    verify(DriveBackupQueue.update(context, cfg, resumed))
    verify(DriveBackupQueue.next(context, cfg)!!.getString("id") == "2-deadbeef")
    DriveBackupQueue.retry(context)
    verify(DriveBackupQueue.config(context).generation > cfg.generation)
    verify(!DriveBackupQueue.update(context, cfg, resumed))
    cfg = DriveBackupQueue.config(context); resumed = DriveBackupQueue.next(context, cfg)!!
    verify(resumed.optString("remoteId") == "stable_remote_id" && resumed.optString("session") == "syntheticEncryptedSession")
    resumed.put("state", "uploaded").put("offset", 100); resumed.remove("session")
    verify(DriveBackupQueue.update(context, cfg, resumed))
    DriveBackupQueue.disconnect(context)
    verify(DriveBackupQueue.status(context)["phase"] == "disconnected")
    DriveBackupQueue.select(context, "synthetic@example.invalid", "account", "folder", "Synthetic")
    DriveBackupQueue.setEnabled(context, true); DriveBackupQueue.enqueue(context, "1-deadbeef", 100)
    verify(DriveBackupQueue.status(context)["uploadedCount"] == 1)
    verify(DriveBackupQueue.status(context)["queuedCount"] == 1)
    DriveBackupQueue.select(context, "synthetic@example.invalid", "account", "otherFolder", "Other")
    verify(DriveBackupQueue.status(context)["uploadedCount"] == 0 && !DriveBackupQueue.config(context).enabled)
    DriveBackupQueue.setEnabled(context, true); DriveBackupQueue.enqueue(context, "1-deadbeef", 100)
    cfg = DriveBackupQueue.config(context); DriveBackupQueue.phase(context, cfg, "needsConsent", "AUTH_REQUIRED")
    verify(DriveBackupQueue.blocked(context))
    DriveBackupQueue.retry(context); verify(!DriveBackupQueue.blocked(context))
    val journal = File(context.noBackupFilesDir, "drive-backup-v1.json")
    val valid = journal.readText()
    journal.renameTo(File(journal.path + ".bak")); journal.writeText("incomplete crash write")
    reset(); verify(DriveBackupQueue.status(context)["queuedCount"] == 1)
    verify(journal.readText() == valid)
    for (corrupt in listOf("{broken", "{\"version\":1,\"generation\":1,\"jobs\":null}", JSONObject(valid).put("jobs", org.json.JSONArray().put(JSONObject().put("id", "1-deadbeef").put("destination", "account:folder").put("size",100).put("state","uploaded"))).toString())) {
      journal.writeText(corrupt); reset()
      verify(DriveBackupQueue.status(context)["errorCode"] == "LOCAL_QUEUE_UNAVAILABLE")
      verify(DriveBackupQueue.blocked(context))
      reject { DriveBackupQueue.select(context, "synthetic@example.invalid", "account", "folder", "Synthetic") }
      reject { DriveBackupQueue.setEnabled(context, false) }
      reject { DriveBackupQueue.retry(context) }
      verify(journal.readText() == corrupt)
    }
    journal.writeBytes(ByteArray(8 * 1024 * 1024 + 1)); reset()
    verify(DriveBackupQueue.status(context)["errorCode"] == "LOCAL_QUEUE_UNAVAILABLE")

    val managedContext = Context(Files.createTempDirectory(root.toPath(), "managed-journal-").toFile())
    reset()
    DriveBackupQueue.setEnabled(managedContext, false)
    val disconnected = DriveBackupQueue.config(managedContext)
    val marker = "0123456789abcdef0123456789abcdef"
    val beforeReservation = File(managedContext.noBackupFilesDir, "drive-backup-v1.json").readText()
    android.util.AtomicFile.failNextFinish = true
    reject { DriveBackupQueue.reserveManaged(managedContext, disconnected.generation, "account_A", "managed_A", marker) }
    verify(DriveBackupQueue.managed(managedContext, "account_A") == null)
    verify(File(managedContext.noBackupFilesDir, "drive-backup-v1.json").readText() == beforeReservation)
    android.util.AtomicFile.silentlySkipNextCommit = true
    reject { DriveBackupQueue.reserveManaged(managedContext, disconnected.generation, "account_A", "managed_A", marker) }
    verify(DriveBackupQueue.managed(managedContext, "account_A") == null)
    verify(File(managedContext.noBackupFilesDir, "drive-backup-v1.json").readText() == beforeReservation)
    android.system.Os.failNextSync = true
    reject { DriveBackupQueue.reserveManaged(managedContext, disconnected.generation, "account_A", "managed_A", marker) }
    verify(DriveBackupQueue.managed(managedContext, "account_A") == null)
    val syncsBeforeReservation = android.system.Os.directorySyncs
    val reserved = DriveBackupQueue.reserveManaged(managedContext, disconnected.generation, "account_A", "managed_A", marker)
    verify(android.system.Os.directorySyncs == syncsBeforeReservation + 1)
    verify(!reserved.confirmed && reserved.accountId == "account_A")
    verify(!DriveBackupQueue.config(managedContext).connected && !DriveBackupQueue.config(managedContext).enabled)
    verify(DriveBackupQueue.reserveManaged(managedContext, disconnected.generation, "account_A", "discarded_id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") == reserved)
    reset() // Process loss before/after POST does not allocate another folder identity.
    verify(DriveBackupQueue.managed(managedContext, "account_A") == reserved)
    verify(DriveBackupQueue.managed(managedContext, "account_B") == null)
    reject { DriveBackupQueue.selectManaged(managedContext, disconnected.generation + 1, "synthetic@example.invalid", reserved, "wa-reco") }
    reject { DriveBackupQueue.selectManaged(managedContext, disconnected.generation, "synthetic@example.invalid", reserved.copy(marker = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "wa-reco") }
    verify(!DriveBackupQueue.config(managedContext).connected)
    DriveBackupQueue.selectManaged(managedContext, disconnected.generation, "synthetic@example.invalid", reserved, "wa-reco")
    verify(DriveBackupQueue.status(managedContext)["folderSource"] == "managed")
    verify(DriveBackupQueue.managed(managedContext, "account_A")!!.confirmed)
    verify(DriveBackupQueue.config(managedContext).connected && !DriveBackupQueue.config(managedContext).enabled)
    DriveBackupQueue.enqueue(managedContext, "4-deadbeef", 100)
    verify(DriveBackupQueue.status(managedContext)["queuedCount"] == 0)
    DriveBackupQueue.setEnabled(managedContext, true)
    DriveBackupQueue.enqueue(managedContext, "4-deadbeef", 100)
    val connected = DriveBackupQueue.config(managedContext)
    DriveBackupQueue.disconnect(managedContext)
    reject { DriveBackupQueue.selectManaged(managedContext, connected.generation, "synthetic@example.invalid", reserved, "wa-reco") }
    reset()
    verify(DriveBackupQueue.managed(managedContext, "account_A")!!.folderId == reserved.folderId)
    verify(DriveBackupQueue.managed(managedContext, "account_A")!!.confirmed)
    verify(DriveBackupQueue.status(managedContext)["folderSource"] == null)
    DriveBackupQueue.select(managedContext, "synthetic@example.invalid", "account_A", "selected_A", "User folder")
    verify(DriveBackupQueue.status(managedContext)["folderSource"] == "selected")
    val selected = DriveBackupQueue.config(managedContext)
    reject { DriveBackupQueue.reserveManaged(managedContext, selected.generation - 1, "account_B", "managed_B", marker) }
    verify(DriveBackupQueue.managed(managedContext, "account_B") == null)
    verify(DriveBackupQueue.config(managedContext) == selected)
    val managedJournal = File(managedContext.noBackupFilesDir, "drive-backup-v1.json")
    val approvedGeneration = selected.generation
    DriveBackupQueue.select(managedContext, "other@example.invalid", "account_B", "selected_B", "Other destination")
    val replacement = DriveBackupQueue.config(managedContext)
    val beforeApproval = managedJournal.readText()
    reject { DriveBackupQueue.setEnabled(managedContext, true, approvedGeneration) }
    reject { DriveBackupQueue.disconnect(managedContext, approvedGeneration) }
    verify(DriveBackupQueue.config(managedContext) == replacement && !replacement.enabled)
    verify(managedJournal.readText() == beforeApproval)
    var commitChecks = 0
    reject { DriveBackupQueue.setEnabled(managedContext, true, replacement.generation) {
      commitChecks++; throw DriveBackupFailure("FOREGROUND_REQUIRED")
    } }
    reject { DriveBackupQueue.disconnect(managedContext, replacement.generation) {
      commitChecks++; throw DriveBackupFailure("FOREGROUND_REQUIRED")
    } }
    verify(commitChecks == 2 && DriveBackupQueue.config(managedContext) == replacement)
    verify(managedJournal.readText() == beforeApproval)
    DriveBackupQueue.setEnabled(managedContext, true, replacement.generation) { commitChecks++ }
    verify(commitChecks == 3 && DriveBackupQueue.config(managedContext).enabled)
    DriveBackupQueue.setEnabled(managedContext, false)
    val managedBytes = managedJournal.readText()
    val badManaged = JSONObject(managedBytes).getJSONArray("managedFolders").getJSONObject(0)
    badManaged.put("marker", "invalid marker")
    val corrupted = JSONObject(managedBytes).put("managedFolders", org.json.JSONArray().put(badManaged)).toString()
    managedJournal.writeText(corrupted); reset()
    verify(DriveBackupQueue.status(managedContext)["errorCode"] == "LOCAL_QUEUE_UNAVAILABLE")
    reject { DriveBackupQueue.reserveManaged(managedContext, 1, "account_A", "managed_A", marker) }
    verify(managedJournal.readText() == corrupted)
    val namesContext = Context(Files.createTempDirectory(root.toPath(), "name-journal-").toFile())
    reset()
    DriveBackupQueue.select(namesContext, "synthetic@example.invalid", "account", "folder", "Synthetic")
    DriveBackupQueue.setEnabled(namesContext, true)
    val namedId = "10-deadbeef"
    val frozenName = RecordingNames.exportFileName(namedId, "דוד Smith 👩‍💻")
    DriveBackupQueue.enqueue(namesContext, namedId, 100, frozenName)
    DriveBackupQueue.enqueue(namesContext, namedId, 100, RecordingNames.exportFileName(namedId, "Changed"))
    var namedConfig = DriveBackupQueue.config(namesContext)
    var namedJob = DriveBackupQueue.next(namesContext, namedConfig)!!
    verify(namedJob.getString("fileName") == frozenName)
    namedJob.put("remoteId", "same_remote").put("md5", "0123456789abcdef0123456789abcdef")
      .put("session", "same_session").put("offset", 50).put("state", "uploading")
    verify(DriveBackupQueue.update(namesContext, namedConfig, namedJob))
    reset()
    namedConfig = DriveBackupQueue.config(namesContext); namedJob = DriveBackupQueue.next(namesContext, namedConfig)!!
    verify(namedJob.getString("fileName") == frozenName && namedJob.getString("session") == "same_session")
    val changedJob = JSONObject(namedJob.toString()).put("fileName", RecordingNames.exportFileName(namedId, "Changed"))
    reject { DriveBackupQueue.update(namesContext, namedConfig, changedJob) }
    val missingName = JSONObject(namedJob.toString()); missingName.remove("fileName")
    reject { DriveBackupQueue.update(namesContext, namedConfig, missingName) }
    namedJob.put("state", "uploaded").put("offset", 100); namedJob.remove("session")
    verify(DriveBackupQueue.update(namesContext, namedConfig, namedJob))
    DriveBackupQueue.enqueue(namesContext, namedId, 100, RecordingNames.exportFileName(namedId, "Changed"))
    verify(DriveBackupQueue.status(namesContext)["uploadedCount"] == 1 && DriveBackupQueue.status(namesContext)["queuedCount"] == 0)
    DriveBackupQueue.enqueue(namesContext, "11-deadbeef", 100)
    DriveBackupQueue.enqueue(namesContext, "11-deadbeef", 100, RecordingNames.exportFileName("11-deadbeef", "Alex"))
    val legacyJob = DriveBackupQueue.next(namesContext, namedConfig)!!
    verify(!legacyJob.has("fileName"))
    val migratedLegacy = JSONObject(legacyJob.toString()).put("fileName", RecordingNames.exportFileName("11-deadbeef", "Alex"))
    reject { DriveBackupQueue.update(namesContext, namedConfig, migratedLegacy) }
    DriveBackupQueue.enqueue(namesContext, "12-deadbeef", 100, "../../unsafe.wav")
    val namesJournal = File(namesContext.noBackupFilesDir, "drive-backup-v1.json")
    val namesBytes = namesJournal.readText()
    verify(!JSONObject(namesBytes).getJSONArray("jobs").getJSONObject(2).has("fileName"))
    for (unsafe in listOf("../../unsafe.wav", RecordingNames.exportFileName("13-deadbeef", "Wrong recording"), 42)) {
      val badNames = JSONObject(namesBytes)
      badNames.getJSONArray("jobs").getJSONObject(0).put("fileName", unsafe)
      namesJournal.writeText(badNames.toString()); reset()
      verify(DriveBackupQueue.status(namesContext)["errorCode"] == "LOCAL_QUEUE_UNAVAILABLE")
    }

    val completionContext = Context(Files.createTempDirectory(root.toPath(), "completion-journal-").toFile())
    reset()
    DriveBackupQueue.select(completionContext, "synthetic@example.invalid", "account", "folder", "Synthetic")
    DriveBackupQueue.setEnabled(completionContext, true)
    var completionConfig = DriveBackupQueue.config(completionContext)
    DriveBackupQueue.enqueue(completionContext, "20-deadbeef", 100)
    val pendingCompletion = DriveBackupQueue.next(completionContext, completionConfig)!!
    pendingCompletion.put("remoteId", "remote_20").put("md5", "0123456789abcdef0123456789abcdef")
    verify(DriveBackupQueue.update(completionContext, completionConfig, pendingCompletion))
    val completionJournal = File(completionContext.noBackupFilesDir, "drive-backup-v1.json")
    var notices = 0
    fun completed(job: JSONObject) = JSONObject(job.toString()).put("state", "uploaded")
      .put("offset", job.getLong("size")).put("uploadedAt", 123L)
    fun notifyAfterCommit() {
      val durable = JSONObject(completionJournal.readText()).getJSONArray("jobs").getJSONObject(0)
      verify(durable.getString("state") == "uploaded" && durable.getLong("offset") == 100L)
      verify(DriveBackupQueue.status(completionContext)["uploadedCount"] == 1)
      notices++
    }
    reject { DriveBackupQueue.completeUpload(completionContext, completionConfig, pendingCompletion, ::notifyAfterCommit) }
    verify(notices == 0 && DriveBackupQueue.status(completionContext)["uploadedCount"] == 0)
    for (invalid in listOf(completed(pendingCompletion).put("offset", 99),
      completed(pendingCompletion).put("remoteId", "another_remote"),
      completed(pendingCompletion).put("md5", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
      completed(pendingCompletion).put("size", 101).put("offset", 101))) {
      reject { DriveBackupQueue.completeUpload(completionContext, completionConfig, invalid, ::notifyAfterCommit) }
      verify(notices == 0 && DriveBackupQueue.status(completionContext)["uploadedCount"] == 0)
    }
    val successCompletion = completed(pendingCompletion)
    android.util.AtomicFile.failNextFinish = true
    reject { DriveBackupQueue.completeUpload(completionContext, completionConfig, successCompletion, ::notifyAfterCommit) }
    verify(notices == 0 && DriveBackupQueue.status(completionContext)["uploadedCount"] == 0)
    android.util.AtomicFile.silentlySkipNextCommit = true
    reject { DriveBackupQueue.completeUpload(completionContext, completionConfig, successCompletion, ::notifyAfterCommit) }
    verify(notices == 0 && DriveBackupQueue.status(completionContext)["uploadedCount"] == 0)
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, successCompletion, ::notifyAfterCommit))
    verify(notices == 1 && DriveBackupQueue.next(completionContext, completionConfig) == null)
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, successCompletion, ::notifyAfterCommit))
    verify(notices == 1)
    reset() // Completed receipts are the durable one-time notice claim, including older app receipts.
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, successCompletion, ::notifyAfterCommit))
    verify(notices == 1 && DriveBackupQueue.status(completionContext)["uploadedCount"] == 1)
    DriveBackupQueue.enqueue(completionContext, "21-deadbeef", 100)
    val nextCompletion = DriveBackupQueue.next(completionContext, completionConfig)!!
    nextCompletion.put("remoteId", "remote_21").put("md5", "0123456789abcdef0123456789abcdef")
    verify(DriveBackupQueue.update(completionContext, completionConfig, nextCompletion))
    DriveBackupQueue.setEnabled(completionContext, false)
    verify(!DriveBackupQueue.completeUpload(completionContext, completionConfig, completed(nextCompletion)) { notices++ })
    verify(notices == 1 && DriveBackupQueue.status(completionContext)["uploadedCount"] == 1)
    DriveBackupQueue.setEnabled(completionContext, true)
    completionConfig = DriveBackupQueue.config(completionContext)
    val disabledNoticeCompletion = completed(DriveBackupQueue.next(completionContext, completionConfig)!!)
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, disabledNoticeCompletion) {
      // Android permission/channel failure must not turn a verified backup into a failed upload.
      throw SecurityException("Synthetic notifications disabled")
    })
    verify(DriveBackupQueue.status(completionContext)["uploadedCount"] == 2)
    reset()
    verify(DriveBackupQueue.next(completionContext, completionConfig) == null)
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, disabledNoticeCompletion) { notices++ })
    verify(notices == 1)
    DriveBackupQueue.enqueue(completionContext, "22-deadbeef", 100)
    val syncCompletion = DriveBackupQueue.next(completionContext, completionConfig)!!
    syncCompletion.put("remoteId", "remote_22").put("md5", "0123456789abcdef0123456789abcdef")
    verify(DriveBackupQueue.update(completionContext, completionConfig, syncCompletion))
    android.system.Os.failNextSync = true
    reject { DriveBackupQueue.completeUpload(completionContext, completionConfig, completed(syncCompletion)) { notices++ } }
    verify(notices == 1)
    reset() // A commit interrupted before the notice must not replay its historical receipt after restart.
    verify(DriveBackupQueue.status(completionContext)["uploadedCount"] == 3)
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, completed(syncCompletion)) { notices++ })
    verify(notices == 1)
    DriveBackupQueue.enqueue(completionContext, "23-deadbeef", 100)
    val legacyCompletion = DriveBackupQueue.next(completionContext, completionConfig)!!
    legacyCompletion.put("remoteId", "remote_23").put("md5", "0123456789abcdef0123456789abcdef")
    verify(DriveBackupQueue.update(completionContext, completionConfig, completed(legacyCompletion)))
    reset() // Receipts from app versions before completion notifications are never announced retroactively.
    verify(DriveBackupQueue.completeUpload(completionContext, completionConfig, completed(legacyCompletion)) { notices++ })
    verify(notices == 1 && DriveBackupQueue.next(completionContext, completionConfig) == null)
    println("Drive backup queue: $checks checks passed (host Context/AtomicFile shims; no Android lifecycle claim).")
  }
}
