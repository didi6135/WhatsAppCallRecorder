package com.didi4164.WhatsAppCallRecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import kotlin.math.sqrt

class RecordingService : Service() {
  @Volatile private var stopRequested = false
  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var usbConnection: UsbAudioBridge.Connection? = null
  private var captureSource = "microphone"
  private var workerRunning = false
  @Volatile private var automaticArmed = false
  @Volatile private var automaticCapture = false
  private var foregroundPromoted = false

  companion object {
    private const val CHANNEL = "microphone_recording"
    private const val NOTIFICATION_ID = 4164
    private const val ACTION_START = "recorder.START"
    private const val ACTION_STOP = "recorder.STOP"
    private const val ACTION_ARM = "recorder.ARM_AUTOMATIC"
    private val lock = Any()
    private var busy = false
    private var recording = false
    private var startedAt = 0L
    private var lastSoundAt = 0L
    private var elapsed = 0L
    private var level = 0.0
    private var source = "microphone"
    private var outputLevel = 0.0
    private var microphoneLevel = 0.0
    private var silenced = false
    private var error: String? = null
    private var startPromise: Promise? = null
    private val stopPromises = mutableListOf<Promise>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeService: RecordingService? = null
    private var pendingArm: CompletableFuture<Unit>? = null

    fun isAutomaticArmed(): Boolean = activeService?.automaticArmed == true
    fun isAutomaticCapture(): Boolean = activeService?.let { it.automaticCapture && isBusyForOwner() } == true

    /** One visible user action establishes the microphone FGS before any background call event. */
    fun armAutomatic(context: Context): CompletableFuture<Unit> {
      check(Looper.myLooper() == Looper.getMainLooper()) { AppText.choose("יש להפעיל מצב אוטומטי מתוך האפליקציה.", "Enable automatic recording from the app.") }
      if (isAutomaticArmed()) return CompletableFuture.completedFuture(Unit)
      check(!isBusyForOwner() && pendingArm == null) { AppText.choose("סיימו את ההקלטה הפעילה לפני הפעלת מצב אוטומטי.", "Finish the current recording before enabling automatic recording.") }
      check(UsbAudioBridge.isConnected() && !RecorderReadinessService.isStopping()) { AppText.choose("יש להפעיל תחילה את רכיב ההקלטה.", "Activate the recording component first.") }
      check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { AppText.choose("נדרשת הרשאת מיקרופון.", "Microphone permission is required.") }
      val request = CompletableFuture<Unit>()
      pendingArm = request
      try {
        context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_ARM))
      } catch (failure: Exception) {
        pendingArm = null; request.completeExceptionally(failure)
      }
      mainHandler.postDelayed({
        if (pendingArm === request && !request.isDone) {
          pendingArm = null
          request.completeExceptionally(IllegalStateException(AppText.choose("המצב האוטומטי לא הופעל בזמן. נסו שוב כשהאפליקציה פתוחה.", "Automatic mode did not start in time. Try again while the app is open.")))
          activeService?.let { if (!it.workerRunning) it.stopSelf() }
        }
      }, 5000L)
      return request
    }

    fun disarmAutomatic(context: Context) {
      check(Looper.myLooper() == Looper.getMainLooper())
      pendingArm?.completeExceptionally(IllegalStateException(AppText.choose("המצב האוטומטי כובה.", "Automatic mode was disabled."))); pendingArm = null
      activeService?.let { service ->
        service.automaticArmed = false
        RecorderReadinessService.refreshAutomaticState()
        if (service.automaticCapture && service.workerRunning) service.requestCaptureStop()
        else if (!service.workerRunning) { service.leaveForeground(); service.stopSelf() }
      }
    }

    fun startAutomaticCapture(context: Context, acceptedUid: Int,
      acceptedSignals: Collection<AutoCallPolicy.NotificationSignal>): Boolean {
      check(Looper.myLooper() == Looper.getMainLooper())
      val service = activeService ?: return false
      if (!service.automaticArmed || service.workerRunning || RecorderReadinessService.isStopping() || !UsbAudioBridge.isConnected()) return false
      synchronized(lock) {
        if (busy) return false
        busy = true; recording = false; error = null; source = "usb"; startPromise = null
      }
      val identity = WhatsAppCallNotificationService.nameForAcceptedStart(context, acceptedUid, acceptedSignals)
      service.beginWorker("usb", true, identity)
      return true
    }

    fun stopAutomaticCapture(context: Context) {
      check(Looper.myLooper() == Looper.getMainLooper())
      activeService?.let { if (it.automaticCapture) it.requestCaptureStop() }
    }

    fun isBusy(): Boolean = synchronized(lock) { busy }
    fun status(context: Context): WritableMap = synchronized(lock) {
      val now = SystemClock.elapsedRealtime()
      Arguments.createMap().apply {
        putBoolean("isRecording", recording)
        putDouble("elapsedMs", (if (recording) now - startedAt else elapsed).toDouble())
        putDouble("level", if (recording) level else 0.0)
        putBoolean("usbConnected", UsbAudioBridge.isConnected())
        putString("source", source)
        putDouble("outputLevel", if (recording) outputLevel else 0.0)
        putDouble("microphoneLevel", if (recording) microphoneLevel else 0.0)
        putBoolean("isSilenced", if (recording) silenced else false)
        putDouble("silentForMs", (if (recording) now - lastSoundAt else 0L).toDouble())
        putInt("audioMode", (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode)
        putString("error", error)
      }
    }
    fun start(context: Context, captureSource: String, promise: Promise) {
      synchronized(lock) {
        if (busy) { promise.reject("ALREADY_RECORDING", AppText.choose("הקלטה כבר פעילה", "A recording is already active")); return }
        if (captureSource !in listOf("microphone", "usb")) {
          promise.reject("INVALID_SOURCE", AppText.choose("מקור הקלטה לא תקין", "The recording source is invalid")); return
        }
        if (captureSource == "usb" && RecorderReadinessService.isStopping()) {
          promise.reject("RECORDER_SHUTTING_DOWN", AppText.choose("המקליט נכבה. המתן לסיום והפעל אותו מחדש לפני הקלטה.", "The recorder is shutting down. Wait, then activate it before recording.")); return
        }
        if (captureSource == "usb" && !UsbAudioBridge.isConnected()) {
          promise.reject("USB_DISCONNECTED", AppText.choose("יש להשלים את הגדרת הקלטת השיחה לפני התחלת ההקלטה", "Complete call recording setup before starting a recording")); return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
          promise.reject("MIC_PERMISSION", AppText.choose("נדרשת הרשאת מיקרופון", "Microphone permission is required")); return
        }
        busy = true; recording = false; error = null; source = captureSource; startPromise = promise
      }
      try {
        val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra("source", captureSource)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
      } catch (e: Exception) {
        synchronized(lock) { busy = false; startPromise = null; error = e.message }
        promise.reject("START_FAILED", AppText.choose("לא ניתן להתחיל הקלטה. חזור למסך האפליקציה ונסה שוב.", "Recording could not start. Return to the app and try again."), e)
      }
    }
    fun stop(context: Context, promise: Promise) {
      mainHandler.post { AutoRecordingController.onManualStop(context.applicationContext) }
      synchronized(lock) {
        if (!busy) { promise.resolve(status(context)); return }
        stopPromises.add(promise)
      }
      try {
        context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
      } catch (e: Exception) {
        synchronized(lock) { stopPromises.remove(promise) }
        promise.reject("STOP_FAILED", AppText.choose("לא ניתן לעצור את ההקלטה", "Recording could not stop"), e)
      }
    }
    fun isBusyForOwner(): Boolean = synchronized(lock) { busy }
    fun requestOwnerStop(context: Context) {
      if (isBusyForOwner()) {
        context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null
  override fun onCreate() { super.onCreate(); activeService = this }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_ARM) {
      val request = pendingArm
      if (request == null || request.isDone) {
        if (!workerRunning && !automaticArmed) stopSelf()
        return START_NOT_STICKY
      }
      try {
        check(UsbAudioBridge.isConnected() && !RecorderReadinessService.isStopping()) { AppText.choose("רכיב ההקלטה אינו מחובר.", "The recording component is disconnected.") }
        showArmedNotification()
        automaticArmed = true
        RecorderReadinessService.refreshAutomaticState()
        pendingArm = null; request.complete(Unit)
      } catch (failure: Exception) {
        automaticArmed = false; pendingArm = null; request.completeExceptionally(failure)
        if (!workerRunning) { leaveForeground(); stopSelf() }
      }
    } else if (intent?.action == ACTION_STOP) {
      AutoRecordingController.onManualStop(applicationContext)
      android.util.Log.i("RecordingService", "STOP intent received USB=${usbConnection != null}")
      requestCaptureStop()
    } else if (intent?.action == ACTION_START && !workerRunning) {
      val nextSource = intent.getStringExtra("source") ?: "microphone"
      val identity = if (nextSource == "usb")
        WhatsAppCallNotificationService.nameForAcceptedStart(applicationContext) else null
      beginWorker(nextSource, false, identity)
    }
    return START_NOT_STICKY
  }

  private fun requestCaptureStop() {
    stopRequested = true
    // The worker owns TCP STOP and finalization; main only changes this flag.
    try { audioRecord?.stop() } catch (_: Exception) { }
  }

  private fun beginWorker(nextSource: String, automatic: Boolean, identity: CallNameIdentity? = null) {
    stopRequested = false; workerRunning = true; captureSource = nextSource; automaticCapture = automatic
    try { showNotification(); Thread({ capture(identity) }, "MicrophoneRecorder").start() }
    catch (failure: Exception) { complete(failure, false) }
  }

  private fun showArmedNotification() {
    val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notifications.createNotificationChannel(NotificationChannel(CHANNEL, AppText.choose("הקלטת שיחות", "Call recording"), NotificationManager.IMPORTANCE_LOW))
    val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle(AppText.choose("הקלטה אוטומטית מוכנה", "Automatic recording is ready"))
      .setContentText(AppText.choose("WhatsApp ו־WhatsApp Business • ממתין לשיחה, אינו מקליט כרגע", "WhatsApp and WhatsApp Business: waiting for a call; no audio is being recorded"))
      .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openIntent).build()
    publishForegroundNotification(notification)
  }

  private fun publishForegroundNotification(notification: Notification) {
    if (foregroundPromoted) {
      // The user already established the microphone FGS while visible. A call callback
      // updates its notification without requesting a new background FGS promotion.
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    } else {
      if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
      else startForeground(NOTIFICATION_ID, notification)
      foregroundPromoted = true
    }
  }

  private fun leaveForeground() {
    foregroundPromoted = false
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  private fun showNotification() {
    val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
      NotificationChannel(CHANNEL, AppText.choose("הקלטת מיקרופון", "Microphone recording"), NotificationManager.IMPORTANCE_LOW)
    )
    val stopIntent = PendingIntent.getService(this, 1,
      Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val openIntent = PendingIntent.getActivity(this, 0,
      Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
    val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle(if (captureSource == "usb") AppText.choose("הקלטת שיחה פעילה", "Call recording is active") else AppText.choose("הקלטת מיקרופון פעילה", "Microphone recording is active"))
      .setContentText(AppText.choose("הקלטה מקומית • לחץ לעצירה או חזור לאפליקציה", "Local recording. Tap to stop or return to the app"))
      .setOngoing(true).setContentIntent(openIntent).setOnlyAlertOnce(true)
      .addAction(Notification.Action.Builder(null, AppText.choose("עצירה ושמירה", "Stop and save"), stopIntent).build()).build()
    publishForegroundNotification(notification)
  }

  private fun capture(identity: CallNameIdentity?) {
    if (captureSource == "usb") { captureUsb(identity); return }
    var failure: Exception? = null
    var wasSilenced = false
    var soundMs = 0L
    var metadata: org.json.JSONObject? = null
    var output: RandomAccessFile? = null
    try {
      val minimum = AudioRecord.getMinBufferSize(WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      check(minimum > 0) { AppText.choose("המכשיר אינו תומך בהקלטה בתצורה הזאת", "This device does not support the recording configuration") }
      val recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(WavFile.SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
      audioRecord = recorder
      check(recorder.state == AudioRecord.STATE_INITIALIZED) { AppText.choose("המיקרופון אינו זמין", "The microphone is unavailable") }
      metadata = RecordingStore.create(this)
      output = RandomAccessFile(RecordingStore.file(this, metadata.getString("id"), ".pending.wav"), "rw")
      output.write(WavFile.header(0L))
      recorder.startRecording()
      check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { AppText.choose("Android לא אפשר התחלת הקלטה", "Android did not allow recording to start") }
      synchronized(lock) {
        recording = true; startedAt = SystemClock.elapsedRealtime(); lastSoundAt = startedAt
        level = 0.0; outputLevel = 0.0; microphoneLevel = 0.0; silenced = false; elapsed = 0L
      }
      mainHandler.post {
        synchronized(lock) {
          if (recording) startPromise?.resolve(status(this))
          else startPromise?.reject("START_FAILED", error ?: AppText.choose("ההקלטה הופסקה לפני שהתחילה", "Recording stopped before it started"))
          startPromise = null
        }
      }
      val samples = ShortArray(2048)
      val bytes = ByteArray(samples.size * 2)
      while (!stopRequested) {
        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        if (count < 0) {
          if (stopRequested) break
          throw IllegalStateException(AppText.choose("קריאת המיקרופון נכשלה: $count", "Microphone read failed: $count"))
        }
        if (count == 0) {
          if (stopRequested) break
          throw IllegalStateException(AppText.choose("לא התקבלו דגימות מהמיקרופון", "No microphone samples were received"))
        }
        var sum = 0.0
        for (i in 0 until count) {
          val sample = samples[i].toInt()
          bytes[i * 2] = (sample and 255).toByte()
          bytes[i * 2 + 1] = (sample shr 8).toByte()
          val normalized = sample / 32768.0
          sum += normalized * normalized
        }
        output.write(bytes, 0, count * 2)
        check(output.length() < 0xffffffe0L) { AppText.choose("ההקלטה הגיעה לגודל הקובץ המרבי", "The recording reached the maximum file size") }
        val rms = sqrt(sum / count).coerceIn(0.0, 1.0)
        val policySilenced = Build.VERSION.SDK_INT >= 29 && (recorder.activeRecordingConfiguration?.isClientSilenced == true)
        wasSilenced = wasSilenced || policySilenced
        if (rms >= CaptureQuality.SOUND_THRESHOLD) soundMs += count * 1000L / WavFile.SAMPLE_RATE
        synchronized(lock) {
          level = rms; microphoneLevel = rms; silenced = policySilenced
          if (rms >= CaptureQuality.SOUND_THRESHOLD) lastSoundAt = SystemClock.elapsedRealtime()
        }
      }
    } catch (e: Exception) { failure = e }
    finally {
      try { audioRecord?.stop() } catch (_: Exception) { }
      audioRecord?.release(); audioRecord = null
      try {
        if (output != null && metadata != null) {
          WavFile.finalize(output)
          output.close(); output = null
          metadata.put("microphoneSoundMs", soundMs).put("outputSoundMs", 0L)
          RecordingStore.finish(this, metadata, CaptureQuality.classify(soundMs, wasSilenced, failure != null), wasSilenced)
        }
      } catch (e: Exception) { failure = e }
      finally { try { output?.close() } catch (_: Exception) { } }
      complete(failure, metadata != null)
    }
  }

  private fun captureUsb(identity: CallNameIdentity?) {
    var failure: Exception? = null
    val startupCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val startAnnounced = java.util.concurrent.atomic.AtomicBoolean(false)
    var wasSilenced = false
    var hadGaps = false
    var outputGapAffectedMs = 0L
    var microphoneGapAffectedMs = 0L
    var gapAffectedPackets = 0L
    var outputSoundMs = 0L
    var microphoneSoundMs = 0L
    var metadata: org.json.JSONObject? = null
    var output: RandomAccessFile? = null
    var connection: UsbAudioBridge.Connection? = null
    try {
      metadata = RecordingStore.create(this, "usb", 2, identity?.displayName, identity?.packageName)
      metadata.put("startedAutomatically", automaticCapture)
      output = RandomAccessFile(RecordingStore.file(this, metadata.getString("id"), ".pending.wav"), "rw")
      output.write(WavFile.header(0L, 2))
      if (stopRequested) { startupCancelled.set(true); return }
      connection = UsbAudioBridge.beginCapture(); usbConnection = connection
      val startDeadline = SystemClock.elapsedRealtime() + 8000L
      var ready = false
      var stopSent = false
      var stopDeadline = Long.MAX_VALUE
      var lastFrameAt = SystemClock.elapsedRealtime()
      var done = false
      while (!done) {
        if (stopRequested && !stopSent) {
          if (!startAnnounced.get()) startupCancelled.set(true)
          val stopSentAt = SystemClock.elapsedRealtime()
          connection.requestStop(); stopSent = true
          // One deadline covers a still-pending START acknowledgement and its
          // PCM tail/native release. A late STARTED never grants another 8s.
          stopDeadline = stopSentAt + 8000L
        }
        val now = SystemClock.elapsedRealtime()
        check(now < stopDeadline) { AppText.choose("רכיב ההקלטה לא נעצר בזמן", "The recording component did not stop in time") }
        if (!ready && !stopSent) check(now < startDeadline) { AppText.choose("רכיב ההקלטה לא אישר התחלת הקלטה", "The recording component did not acknowledge recording start") }
        val remaining = if (stopSent) stopDeadline - now else if (!ready) startDeadline - now else 200L
        when (val frame = connection.next(minOf(200L, remaining).coerceAtLeast(1L))) {
          UsbAudioBridge.Frame.Started -> if (!ready) {
            ready = true
            val acknowledgedAt = SystemClock.elapsedRealtime()
            lastFrameAt = acknowledgedAt
            if (stopRequested || stopSent) startupCancelled.set(true)
            mainHandler.post {
              synchronized(lock) {
                // STOP and this publication execute on main. A cancelled or
                // already drained startup cannot publish success afterwards.
                if (stopRequested || startupCancelled.get()) {
                  startupCancelled.set(true)
                  startPromise?.reject("START_CANCELLED", AppText.choose("תחילת ההקלטה בוטלה.", "Recording start was canceled."))
                } else {
                  recording = true; startedAt = acknowledgedAt; lastSoundAt = startedAt
                  level = 0.0; outputLevel = 0.0; microphoneLevel = 0.0; silenced = false; elapsed = 0L
                  startAnnounced.set(true)
                  startPromise?.resolve(status(this))
                }
                startPromise = null
              }
            }
          }
          is UsbAudioBridge.Frame.Pcm -> {
            check(ready || stopSent) { AppText.choose("רכיב ההקלטה שלח אודיו לפני אישור ההתחלה", "The recording component sent audio before acknowledging start") }
            lastFrameAt = SystemClock.elapsedRealtime()
            output.write(frame.bytes)
            check(output.length() < 0xffffffe0L) { AppText.choose("ההקלטה הגיעה לגודל הקובץ המרבי", "The recording reached the maximum file size") }
            val count = frame.bytes.size / 4
            var sumLeft = 0.0
            var sumRight = 0.0
            for (i in 0 until count) {
              val offset = i * 4
              val left = ((frame.bytes[offset].toInt() and 255) or (frame.bytes[offset + 1].toInt() shl 8)).toShort() / 32768.0
              val right = ((frame.bytes[offset + 2].toInt() and 255) or (frame.bytes[offset + 3].toInt() shl 8)).toShort() / 32768.0
              sumLeft += left * left; sumRight += right * right
            }
            val leftRms = sqrt(sumLeft / count)
            val rightRms = sqrt(sumRight / count)
            val sampleMs = count * 1000L / WavFile.SAMPLE_RATE
            if (leftRms >= CaptureQuality.SOUND_THRESHOLD) outputSoundMs += sampleMs
            if (rightRms >= CaptureQuality.SOUND_THRESHOLD) microphoneSoundMs += sampleMs
            val policySilenced = frame.flags and 3 != 0
            wasSilenced = wasSilenced || policySilenced
            hadGaps = hadGaps || frame.flags and 12 != 0
            // Flags identify affected packets, not an exact lost-sample count.
            if (frame.flags and 4 != 0) outputGapAffectedMs += sampleMs
            if (frame.flags and 8 != 0) microphoneGapAffectedMs += sampleMs
            if (frame.flags and 12 != 0) gapAffectedPackets++
            synchronized(lock) {
              outputLevel = leftRms; microphoneLevel = rightRms; level = maxOf(leftRms, rightRms)
              silenced = policySilenced
              if (level >= CaptureQuality.SOUND_THRESHOLD) lastSoundAt = SystemClock.elapsedRealtime()
            }
          }
          UsbAudioBridge.Frame.Stopped -> {
            check(stopSent) { AppText.choose("רכיב ההקלטה עצר את ההקלטה באופן בלתי צפוי", "The recording component stopped recording unexpectedly") }
            if (!startAnnounced.get()) startupCancelled.set(true)
            done = true
          }
          is UsbAudioBridge.Frame.Failure -> throw IllegalStateException(frame.message)
          else -> if (ready && !stopSent) check(SystemClock.elapsedRealtime() - lastFrameAt < 5000L) { AppText.choose("לא התקבלו נתוני אודיו מרכיב ההקלטה", "No audio data was received from the recording component") }
        }
      }
    } catch (e: Exception) { failure = e }
    finally {
      if (failure != null) connection?.disconnect()
      else connection?.end()
      usbConnection = null
      try {
        if (output != null && metadata != null) {
          WavFile.finalize(output, 2); output.close(); output = null
          metadata.put("outputSoundMs", outputSoundMs).put("microphoneSoundMs", microphoneSoundMs)
            .put("outputGapAffectedMs", outputGapAffectedMs).put("microphoneGapAffectedMs", microphoneGapAffectedMs)
            .put("gapAffectedPackets", gapAffectedPackets)
            .put("startupCancelled", startupCancelled.get())
            .put("captureError", failure?.message ?: org.json.JSONObject.NULL)
          RecordingStore.finish(this, metadata,
            CaptureQuality.classify(maxOf(outputSoundMs, microphoneSoundMs), wasSilenced, failure != null || hadGaps || startupCancelled.get()), wasSilenced)
        }
      } catch (e: Exception) { failure = e }
      finally { try { output?.close() } catch (_: Exception) { } }
      complete(failure, metadata != null)
    }
  }

  private fun complete(failure: Exception?, hadFile: Boolean) {
    mainHandler.post {
      synchronized(lock) {
        // Finish the old service before allowing the next start or settling JS.
        // A worker must not release busy and later settle a new session's promise.
        elapsed = if (recording) SystemClock.elapsedRealtime() - startedAt else 0L
        recording = false; level = 0.0; outputLevel = 0.0; microphoneLevel = 0.0; silenced = false; error = failure?.message
        workerRunning = false
        val finishedAutomatic = automaticCapture
        automaticCapture = false
        startPromise?.reject("START_FAILED", failure?.message ?: AppText.choose("ההקלטה לא התחילה", "Recording did not start"))
        startPromise = null
        val completedStops = stopPromises.toList()
        stopPromises.clear()
        busy = false
        if (automaticArmed) {
          try { showArmedNotification() }
          catch (promotionFailure: Exception) {
            automaticArmed = false; leaveForeground(); stopSelf()
            AutoRecordingController.onOwnerStopping(applicationContext)
          }
        } else { leaveForeground(); stopSelf() }
        if (finishedAutomatic) AutoRecordingController.onRecorderCompleted(applicationContext, failure)
        completedStops.forEach { promise ->
          if (failure != null) promise.reject("RECORDING_FAILED", (if (hadFile) AppText.choose("ההקלטה הופסקה; הנתונים נשמרו ככל שניתן. ", "Recording was interrupted; available data was saved. ") else "") + failure.message)
          else promise.resolve(status(this))
        }
      }
    }
  }
  override fun onDestroy() {
    val wasArmed = automaticArmed
    automaticArmed = false
    foregroundPromoted = false
    RecorderReadinessService.refreshAutomaticState()
    if (activeService === this) activeService = null
    pendingArm?.completeExceptionally(IllegalStateException(AppText.choose("שירות ההקלטה הופסק.", "The recording service stopped."))); pendingArm = null
    if (wasArmed) AutoRecordingController.onOwnerStopping(applicationContext)
    stopRequested = true
    // Worker observes this flag and completes the bounded USB shutdown itself.
    try { audioRecord?.stop() } catch (_: Exception) { }
    super.onDestroy()
  }
}
