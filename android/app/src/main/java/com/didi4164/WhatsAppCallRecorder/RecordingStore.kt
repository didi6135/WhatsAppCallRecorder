package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object RecordingStore {
  private val validId = Regex("[0-9]+-[a-f0-9]{8}")
  fun directory(context: Context): File = File(context.filesDir, "recordings").apply {
    check(exists() || mkdirs()) { "לא ניתן ליצור תיקיית הקלטות" }
  }
  fun file(context: Context, id: String, suffix: String): File {
    require(validId.matches(id)) { "מזהה הקלטה לא תקין" }
    return File(directory(context), "$id$suffix")
  }
  @Synchronized fun create(context: Context, source: String = "microphone", channels: Int = 1): JSONObject {
    val now = System.currentTimeMillis()
    val id = "$now-${UUID.randomUUID().toString().take(8)}"
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(now))
    val item = JSONObject().put("id", id).put("date", date)
      .put("title", if (source == "usb") "שיחת WhatsApp • שני ערוצים" else "הקלטת מיקרופון").put("status", "recovered")
      .put("captureSource", source).put("channels", channels)
      .put("wasSilenced", false).put("durationMs", 0L).put("fileSize", 0L)
    atomicWrite(file(context, id, ".pending.json"), item)
    return item
  }
  private fun atomicWrite(target: File, value: JSONObject) {
    val temp = File(target.parentFile, target.name + ".tmp")
    temp.outputStream().use { stream ->
      stream.write(value.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
    }
    check(temp.renameTo(target)) { "לא ניתן לשמור מידע על ההקלטה" }
  }
  @Synchronized fun finish(context: Context, item: JSONObject, status: String, wasSilenced: Boolean) {
    val id = item.getString("id")
    val pending = file(context, id, ".pending.wav")
    val complete = file(context, id, ".wav")
    if (pending.exists()) check(pending.renameTo(complete)) { "לא ניתן לשמור את קובץ ההקלטה" }
    check(complete.exists()) { "קובץ ההקלטה חסר" }
    val size = complete.length()
    item.put("filePath", complete.absolutePath).put("fileSize", size)
      .put("durationMs", (size - WavFile.HEADER_SIZE).coerceAtLeast(0) * 1000 / (WavFile.SAMPLE_RATE * 2 * item.optInt("channels", 1)))
      .put("status", status).put("wasSilenced", wasSilenced)
    atomicWrite(file(context, id, ".json"), item)
    file(context, id, ".pending.json").delete()
    // Enqueue only after the local WAV and metadata have committed. Cloud
    // failures must never turn a successful local save into a capture error.
    try { DriveBackupManager.enqueueCompleted(context.applicationContext, id) }
    catch (_: Exception) { /* Startup reconciliation retries committed files. */ }
    // Metadata only: useful for release verification without exposing private audio.
    android.util.Log.i("RecorderStore", "SAVED id=$id status=$status channels=${item.optInt("channels", 1)} " +
      "durationMs=${item.optLong("durationMs")} bytes=$size outputSoundMs=${item.optLong("outputSoundMs")} " +
      "microphoneSoundMs=${item.optLong("microphoneSoundMs")} gaps=${item.optLong("gapAffectedPackets")}")
  }
  @Synchronized fun list(context: Context): List<JSONObject> {
    if (!RecordingService.isBusy()) recover(context)
    return directory(context).listFiles().orEmpty().filter {
      it.name.endsWith(".json") && !it.name.endsWith(".pending.json")
    }.mapNotNull { metadata ->
      try {
        val item = JSONObject(metadata.readText())
        val audio = file(context, item.getString("id"), ".wav")
        if (audio.exists()) item.put("filePath", audio.absolutePath).put("fileSize", audio.length()) else null
      } catch (_: Exception) { null }
    }.sortedByDescending { it.getString("id") }
  }
  private fun recover(context: Context) {
    directory(context).listFiles().orEmpty().filter { it.name.endsWith(".pending.json") }.forEach { metadata ->
      try {
        val item = JSONObject(metadata.readText())
        val id = item.getString("id")
        val committed = file(context, id, ".json")
        val complete = file(context, id, ".wav")
        if (committed.exists() && complete.exists()) {
          // finish() may have committed successfully immediately before a kill.
          // Validate committed metadata before discarding only the stale marker.
          val saved = JSONObject(committed.readText())
          if (saved.optString("id") == id && saved.has("status")) {
            metadata.delete()
            return@forEach
          }
        }
        val pending = file(context, id, ".pending.wav")
        val audio = if (pending.exists()) pending else file(context, id, ".wav")
        if (audio.exists() && audio.length() >= WavFile.HEADER_SIZE) {
          RandomAccessFile(audio, "rw").use { WavFile.finalize(it, item.optInt("channels", 1)) }
          finish(context, item, "recovered", item.optBoolean("wasSilenced"))
        }
      } catch (_: Exception) { /* Retain unfinished data for a later recovery attempt. */ }
    }
  }
  @Synchronized fun delete(context: Context, id: String) {
    check(!RecordingService.isBusy()) { "יש לעצור את ההקלטה לפני מחיקה" }
    val audio = file(context, id, ".wav")
    val metadata = file(context, id, ".json")
    check(!audio.exists() || audio.delete()) { "מחיקת קובץ ההקלטה נכשלה" }
    check(!metadata.exists() || metadata.delete()) { "מחיקת מידע ההקלטה נכשלה" }
  }
}
