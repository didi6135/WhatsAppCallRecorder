package com.didi4164.WhatsAppCallRecorder

import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.util.concurrent.Executors

class CallRecorderModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  private val io = Executors.newSingleThreadExecutor()
  override fun getName(): String = "CallRecorder"
  @ReactMethod fun getStatus(promise: Promise) { promise.resolve(RecordingService.status(context)) }
  @ReactMethod fun getAutoRecordingStatus(promise: Promise) { promise.resolve(AutoRecordingController.statusMap(context)) }
  @ReactMethod fun setAutoRecordingEnabled(enabled: Boolean, promise: Promise) {
    if (!enabled) {
      android.os.Handler(android.os.Looper.getMainLooper()).post { AutoRecordingController.setEnabled(context, false, promise) }
      return
    }
    val activity = currentActivity
    if (activity == null || activity.isFinishing) { promise.reject("FOREGROUND_REQUIRED", AppText.choose("פתחו את האפליקציה כדי להפעיל הקלטה אוטומטית.", "Open the app to enable automatic recording.")); return }
    activity.runOnUiThread {
      if (!activity.hasWindowFocus()) { promise.reject("FOREGROUND_REQUIRED", AppText.choose("יש להפעיל הקלטה אוטומטית כשהאפליקציה פתוחה על המסך.", "Enable automatic recording while the app is on screen.")); return@runOnUiThread }
      AutoRecordingController.setEnabled(context, true, promise)
    }
  }
  @ReactMethod fun openNotificationAccessSetup(promise: Promise) {
    val activity = currentActivity
    if (activity == null || activity.isFinishing) { promise.reject("FOREGROUND_REQUIRED", AppText.choose("פתחו את האפליקציה כדי לאפשר זיהוי שיחות.", "Open the app to enable call detection.")); return }
    activity.runOnUiThread {
      try {
        val component = android.content.ComponentName(context, WhatsAppCallNotificationService::class.java)
        try {
          activity.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()))
        } catch (_: android.content.ActivityNotFoundException) {
          activity.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        promise.resolve(null)
      } catch (failure: Exception) { promise.reject("NOTIFICATION_SETUP_FAILED", AppText.choose("לא ניתן לפתוח את הרשאת זיהוי השיחות.", "Call detection permission could not be opened."), failure) }
    }
  }
  @ReactMethod fun getSystemAccessStatus(promise: Promise) {
    promise.resolve(NativeWirelessAudioBridge.statusMap(context).apply {
      putMap("pairingSetup", Arguments.makeNativeMap(PairingNotificationController.statusMap()))
      putBoolean("developerOptionsEnabled", runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver,
          android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
      }.getOrDefault(false))
    })
  }
  @ReactMethod fun prepareSystemPairing(promise: Promise) { PairingNotificationController.prepare(context, promise) }
  @ReactMethod fun pairSystemRecorder(pairingPort: Int, pairingCode: String, promise: Promise) {
    PairingNotificationController.cancel(context)
    NativeWirelessAudioBridge.pair(context, pairingPort, pairingCode, promise)
  }
  @ReactMethod fun connectSystemRecorder(connectionPort: Int, promise: Promise) {
    try {
      val pairing = UsbAudioBridge.getPairing()
      NativeWirelessAudioBridge.startHelper(context, connectionPort, pairing.port, pairing.key, pairing.appUid, promise)
    } catch (e: Exception) { promise.reject("SYSTEM_CONNECTION_FAILED", e.message, e) }
  }
  @ReactMethod fun openSystemAccessSetup(destination: String, promise: Promise) {
    val activity = currentActivity
    if (activity == null) { promise.reject("FOREGROUND_REQUIRED", AppText.choose("פתח את האפליקציה כדי להגדיר גישה", "Open the app to configure access")); return }
    activity.runOnUiThread {
      try {
        val intent = when (destination) {
          "about" -> Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
          "developer" -> Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
          "wireless" -> Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
          else -> throw IllegalArgumentException(AppText.choose("יעד הגדרה לא תקין", "The setup destination is invalid"))
        }
        try { activity.startActivity(intent) }
        catch (unavailable: android.content.ActivityNotFoundException) {
          activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        promise.resolve(null)
      } catch (e: Exception) { promise.reject("SETUP_FAILED", e.message, e) }
    }
  }
  @ReactMethod fun startRecording(source: String, promise: Promise) {
    val activity = currentActivity
    if (activity == null || activity.isFinishing) {
      promise.reject("FOREGROUND_REQUIRED", AppText.choose("פתח את האפליקציה כדי להתחיל הקלטה", "Open the app to start recording")); return
    }
    activity.runOnUiThread { RecordingService.start(context, source, promise) }
  }
  @ReactMethod fun stopRecording(promise: Promise) { RecordingService.stop(context, promise) }
  @ReactMethod fun listRecordings(promise: Promise) { io.execute {
    try {
      val result = Arguments.createArray()
      RecordingStore.list(context).forEach { item ->
        result.pushMap(Arguments.createMap().apply {
          putString("id", item.getString("id")); putString("title", item.getString("title"))
          putString("date", item.getString("date")); putDouble("durationMs", item.getLong("durationMs").toDouble())
          putString("filePath", item.getString("filePath")); putDouble("fileSize", item.getLong("fileSize").toDouble())
          putString("status", item.getString("status")); putBoolean("wasSilenced", item.getBoolean("wasSilenced"))
          putString("captureSource", item.optString("captureSource", "microphone"))
          putInt("channels", item.optInt("channels", 1))
          putBoolean("startedAutomatically", item.optBoolean("startedAutomatically", false))
          putDouble("outputSoundMs", item.optLong("outputSoundMs", 0L).toDouble())
          putDouble("microphoneSoundMs", item.optLong("microphoneSoundMs", 0L).toDouble())
        })
      }
      promise.resolve(result)
    } catch (e: Exception) { promise.reject("LOAD_FAILED", e.message, e) }
  } }
  @ReactMethod fun deleteRecording(id: String, promise: Promise) { io.execute {
    try { RecordingStore.delete(context, id); promise.resolve(null) }
    catch (e: Exception) { promise.reject("DELETE_FAILED", e.message, e) }
  } }
  @ReactMethod fun shareRecording(id: String, promise: Promise) {
    val activity = currentActivity
    if (activity == null) { promise.reject("FOREGROUND_REQUIRED", AppText.choose("פתח את האפליקציה כדי לשתף", "Open the app to share")); return }
    activity.runOnUiThread {
      try {
        val file = RecordingStore.file(context, id, ".wav")
        check(file.exists()) { AppText.choose("קובץ ההקלטה לא נמצא", "The recording file was not found") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
        val send = Intent(Intent.ACTION_SEND).setType("audio/wav")
          .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          .apply { clipData = ClipData.newRawUri(AppText.choose("הקלטת מיקרופון", "Microphone recording"), uri) }
        activity.startActivity(Intent.createChooser(send, AppText.choose("שיתוף הקלטה", "Share recording"))); promise.resolve(null)
      } catch (e: Exception) { promise.reject("SHARE_FAILED", e.message, e) }
    }
  }
  @ReactMethod fun openWhatsApp(promise: Promise) {
    val activity = currentActivity
    if (activity == null) { promise.reject("FOREGROUND_REQUIRED", AppText.choose("פתח את האפליקציה", "Open the app")); return }
    activity.runOnUiThread {
      try {
        val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
          ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
          ?: throw IllegalStateException(AppText.choose("WhatsApp אינו מותקן בפרופיל הזה", "WhatsApp is not installed in this profile"))
        activity.startActivity(intent); promise.resolve(null)
      } catch (e: Exception) { promise.reject("WHATSAPP_UNAVAILABLE", e.message, e) }
    }
  }
  @ReactMethod fun getDeviceInfo(promise: Promise) {
    promise.resolve(Arguments.createMap().apply {
      putString("manufacturer", Build.MANUFACTURER); putString("model", Build.MODEL)
      putString("androidVersion", Build.VERSION.RELEASE); putInt("sdk", Build.VERSION.SDK_INT)
    })
  }
}
