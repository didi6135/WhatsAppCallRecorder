package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials, chat binding and queue are all authenticated/encrypted and excluded from backup. */
object TelegramBackupStore {
  private const val ALIAS = "wa-reco-telegram-private-v1"
  private const val MAX_BYTES = 16 * 1024 * 1024
  private var cached: JSONObject? = null
  private var unavailable = false
  private fun disk(context: Context) = AtomicFile(File(context.noBackupFilesDir, "telegram-backup-v1.bin"))
  private fun key(): SecretKey {
    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keys.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
    }.generateKey()
  }
  private fun encrypt(plain: ByteArray): ByteArray {
    require(plain.size <= MAX_BYTES)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    check(cipher.iv.size == 12)
    return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
  }
  private fun decrypt(bytes: ByteArray): ByteArray {
    require(bytes.size in 30..(MAX_BYTES + 29) && bytes[0] == 1.toByte())
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
    }
    return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
  }
  private fun save(context: Context, value: JSONObject) {
    TelegramBackupLedger.validate(value)
    val bytes = encrypt(value.toString().toByteArray(Charsets.UTF_8))
    val atomic = disk(context)
    val output = atomic.startWrite()
    try {
      output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
      // Dispatch cannot begin until both the journal contents and rename are durable.
      val directory = Os.open(context.noBackupFilesDir.absolutePath, OsConstants.O_RDONLY, 0)
      try { Os.fsync(directory) } finally { Os.close(directory) }
      cached = value
    } catch (_: Exception) {
      runCatching { atomic.failWrite(output) }
      // A possibly committed intent must be re-read/recovered rather than lost from memory.
      cached = null
      throw TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    }
  }
  private fun state(context: Context): JSONObject {
    if (unavailable) throw TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
    cached?.let { return it }
    try {
      val atomic = disk(context)
      val exists = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists() || File(atomic.baseFile.path + ".new").exists()
      val loaded = if (exists) atomic.openRead().use { input ->
        val bytes = input.readBytesBounded(MAX_BYTES + 29)
        JSONObject(String(decrypt(bytes), Charsets.UTF_8)).also(TelegramBackupLedger::validate)
      } else TelegramBackupLedger.fresh()
      if (TelegramBackupLedger.recoverInterrupted(loaded)) save(context, loaded) else cached = loaded
      return loaded
    } catch (_: Exception) { unavailable = true; throw TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE") }
  }
  private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    while (true) {
      val count = read(buffer); if (count < 0) return output.toByteArray()
      if (output.size() + count > limit) throw TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE")
      output.write(buffer, 0, count)
    }
  }
  @Synchronized fun snapshot(context: Context): JSONObject = JSONObject(state(context).toString())
  @Synchronized fun config(context: Context): TelegramBackupLedger.Config = TelegramBackupLedger.config(state(context))
  @Synchronized fun status(context: Context): Map<String, Any?> = TelegramBackupLedger.status(state(context), System.currentTimeMillis())
  @Synchronized fun next(context: Context): TelegramBackupLedger.Selection? = TelegramBackupLedger.next(state(context), System.currentTimeMillis())
  @Synchronized fun contains(context: Context, id: String, expected: TelegramBackupLedger.Config): Boolean {
    val jobs = state(context).getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.getString("id") == id && job.getString("destination") == expected.destination) return true
    }
    return false
  }
  @Synchronized fun current(context: Context, expected: TelegramBackupLedger.Config): Boolean {
    val actual = config(context)
    return actual.enabled && actual.connected && actual.generation == expected.generation && actual.destination == expected.destination
  }
  @Synchronized fun hasPending(context: Context): Boolean {
    val value = state(context); val cfg = TelegramBackupLedger.config(value)
    if (!cfg.enabled || !cfg.connected || TelegramBackupLedger.blocked(value)) return false
    val jobs = value.getJSONArray("jobs")
    for (i in 0 until jobs.length()) {
      val job = jobs.getJSONObject(i)
      if (job.getString("destination") != cfg.destination) continue
      val parts = job.getJSONArray("parts")
      for (j in 0 until parts.length()) {
        val phase = parts.getJSONObject(j).getString("state")
        if (phase == "DELIVERED") continue
        if (phase == "PENDING") return true
        break
      }
    }
    return false
  }
  @Synchronized fun <T> change(context: Context, action: (JSONObject) -> T): T {
    val next = JSONObject(state(context).toString())
    try { val result = action(next); save(context, next); return result }
    catch (failure: TelegramBackupFailure) { throw failure }
    catch (_: Exception) { throw TelegramBackupFailure("LOCAL_QUEUE_UNAVAILABLE") }
  }
}
