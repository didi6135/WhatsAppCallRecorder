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
    println("Drive backup queue: $checks checks passed (host Context/AtomicFile shims; no Android lifecycle claim).")
  }
}
