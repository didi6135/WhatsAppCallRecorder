package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import com.codaki.usbaudio.ShellAudioCompatibility
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The only ADB service exposed to JS is the fixed recorder bootstrap. */
object NativeWirelessAudioBridge {
  private const val PREFS = "wireless-recorder"
  private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "WirelessRecorderActivation") }
  private val main = Handler(Looper.getMainLooper())
  private val stateLock = Any()
  private val connecting = AtomicBoolean(false)
  @Volatile private var appContext: Context? = null
  @Volatile private var errorState: Pair<String, String?>? = null
  @Volatile private var activePairing: WirelessManager? = null
  @Volatile private var helperManager: WirelessManager? = null
  @Volatile private var helperStream: AdbStream? = null
  private var pairingGeneration = 0L
  private enum class ActivationStage { DISCOVERY, ADB_CONNECTION, BOOTSTRAP, AUTHENTICATION, HANDOFF, DETACH, LIVENESS }

  private fun setError(message: String?, code: String? = null) {
    // One immutable snapshot prevents a status poll from mixing an old code with a new message.
    errorState = message?.let { Pair(it, code) }
  }
  private fun failureDetails(failure: WirelessActivationFailure?): WritableMap? = failure?.let {
    Arguments.createMap().apply { putString("errorCode", it.errorCode) }
  }

  fun initialize(context: Context) {
    synchronized(stateLock) {
      if (appContext != null) return
      appContext = context.applicationContext
    }
    // Prepare the private identity while the user reads the setup instructions.
    if (ShellAudioCompatibility.isCandidateSdk(Build.VERSION.SDK_INT)) io.execute {
      try { WirelessManager(context.applicationContext) }
      catch (_: Exception) { setError(AppText.choose("לא ניתן להכין את הזהות המקומית. נסה להפעיל את האפליקציה מחדש.", "The local identity could not be prepared. Restart the app and try again.")) }
    }
  }

  fun statusMap(context: Context): WritableMap = Arguments.createMap().apply {
    putBoolean("available", ShellAudioCompatibility.isCandidateSdk(Build.VERSION.SDK_INT))
    putBoolean("experimental", ShellAudioCompatibility.isExperimentalSdk(Build.VERSION.SDK_INT))
    putBoolean("paired", context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false))
    val enabled = try { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (_: Exception) { false }
    putBoolean("wirelessDebuggingEnabled", enabled)
    // HMAC arrives before the worker accepts handoff. Readiness becomes visible
    // only after ADB closure, a fresh heartbeat and foreground-owner startup.
    putBoolean("helperConnected", UsbAudioBridge.isConnected() && !connecting.get())
    putBoolean("connecting", connecting.get())
    val failure = errorState
    putString("error", failure?.first)
    putString("errorCode", failure?.second)
  }

  fun pair(context: Context, pairPort: Int, code: String, promise: Promise) {
    pairAsync(context, pairPort, code) { result ->
      result.fold({ promise.resolve(null) }, { promise.reject("PAIRING_FAILED", it.message, it) })
    }
  }

  fun pairAsync(context: Context, pairPort: Int, code: String, callback: (Result<Unit>) -> Unit) {
    initialize(context)
    val cleanCode = code.trim()
    if (!ShellAudioCompatibility.isCandidateSdk(Build.VERSION.SDK_INT)) {
      main.post { callback(Result.failure(UnsupportedOperationException(AppText.choose("חיבור השיחות דורש Android 14 ומעלה. ב־Android 14/15 התמיכה ניסיונית.", "Call recording requires Android 14 or later. Support on Android 14/15 is experimental.")))) }
      return
    }
    if (pairPort !in 1024..65535 || !cleanCode.matches(Regex("[0-9]{6}"))) {
      main.post { callback(Result.failure(IllegalArgumentException(AppText.choose("הזן את שש הספרות שמופיעות בחלון קוד ההתאמה.", "Enter the six digits shown in the pairing code window.")))) }
      return
    }
    if (!connecting.compareAndSet(false, true)) {
      main.post { callback(Result.failure(IllegalStateException(AppText.choose("פעולת חיבור כבר מתבצעת. המתן לסיומה.", "A connection is already in progress. Wait for it to finish.")))) }
      return
    }
    val generation = synchronized(stateLock) { setError(null); ++pairingGeneration }
    io.execute {
      var local: WirelessManager? = null
      val outcome = runCatching {
        local = WirelessManager(context.applicationContext)
        synchronized(stateLock) {
          check(generation == pairingGeneration) { AppText.choose("ההתאמה בוטלה.", "Pairing was canceled.") }
          activePairing = local
        }
        check(local!!.pair("127.0.0.1", pairPort, cleanCode)) { AppText.choose("ההתאמה לא הושלמה.", "Pairing was not completed.") }
      }
      activePairing = null
      main.post {
        val completed: Result<Unit> = synchronized(stateLock) {
          if (generation != pairingGeneration) Result.failure(IllegalStateException(AppText.choose("ההתאמה בוטלה.", "Pairing was canceled.")))
          else if (outcome.isFailure) Result.failure(IllegalStateException(AppText.choose("ההתאמה לא הושלמה. פתח שוב את חלון הקוד והזן את הקוד החדש.", "Pairing was not completed. Open a new pairing window and enter its new code.")))
          else {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
            if (saved) Result.success(Unit) else Result.failure(IllegalStateException(AppText.choose("ההתאמה הצליחה אך לא נשמרה. נסה שוב.", "Pairing succeeded but could not be saved. Try again.")))
          }
        }
        setError(completed.exceptionOrNull()?.message)
        connecting.set(false)
        callback(completed)
      }
    }
  }

  fun cancelPairing() {
    synchronized(stateLock) { pairingGeneration++; setError(AppText.choose("ההתאמה בוטלה.", "Pairing was canceled.")) }
    activePairing?.cancelPairing()
  }

  fun startHelper(context: Context, connectionPort: Int, bridgePort: Int, key: ByteArray, appUid: Int, promise: Promise) {
    initialize(context)
    if (!ShellAudioCompatibility.isCandidateSdk(Build.VERSION.SDK_INT)) {
      promise.reject("UNSUPPORTED_ANDROID", AppText.choose("חיבור השיחות דורש Android 14 ומעלה. ב־Android 14/15 התמיכה ניסיונית.", "Call recording requires Android 14 or later. Support on Android 14/15 is experimental.")); return
    }
    if (connectionPort !in 0..65535 || (connectionPort != 0 && connectionPort < 1024)
      || bridgePort !in 1024..65535 || key.size != 32 || appUid != android.os.Process.myUid()) {
      promise.reject("INVALID_ACTIVATION", AppText.choose("פרטי ההפעלה אינם תקינים.", "Activation details are invalid.")); return
    }
    if (RecorderReadinessService.isStopping()) {
      promise.reject("OWNER_STOPPING", AppText.choose("רכיב ההקלטה נסגר כעת. המתן רגע ואז הפעל אותו שוב.", "The recording component is shutting down. Wait a moment before activating it again.")); return
    }
    val alreadyConnected = UsbAudioBridge.isConnected()
    val setupEnabled = try { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (_: Exception) { false }
    if (!alreadyConnected && !setupEnabled) {
      promise.reject("ACTIVATION_SETUP_REQUIRED", AppText.choose("להפעלת רכיב ההקלטה מחדש יש להתחבר ל־Wi-Fi ולהפעיל ניפוי באגים אלחוטי.", "To reactivate the recording component, connect to Wi-Fi and enable Wireless debugging.")); return
    }
    if (!connecting.compareAndSet(false, true)) { promise.reject("ACTIVATION_BUSY", AppText.choose("פעולת חיבור כבר מתבצעת.", "A connection is already in progress.")); return }
    setError(null)
    if (alreadyConnected) {
      main.post { finishWithReadinessOwner(context, promise) }
      return
    }
    val secret = key.copyOf()
    io.execute {
      var local: WirelessManager? = null
      var stream: AdbStream? = null
      var stage = ActivationStage.DISCOVERY
      try {
        closeHelper()
        local = WirelessManager(context.applicationContext)
        val port = if (connectionPort == 0) discoverLocalConnectionPort(context.applicationContext) else connectionPort
        stage = ActivationStage.ADB_CONNECTION
        check(local.connect("127.0.0.1", port)) { AppText.choose("חיבור המערכת לא הצליח.", "The system connection failed.") }
        stage = ActivationStage.BOOTSTRAP
        check(!RecorderReadinessService.isStopping()) { AppText.choose("רכיב ההקלטה עדיין נסגר.", "The recording component is still shutting down.") }
        val apk = File(context.applicationInfo.sourceDir).canonicalPath
        val command = "CLASSPATH=${shellQuote(apk)} /system/bin/app_process / com.codaki.usbaudio.ShellAudioBootstrap --spawn-detached --app-uid=$appUid --port=$bridgePort"
        stream = local.openStream("shell:$command")
        helperManager = local; helperStream = stream
        val input = stream.openInputStream()
        val signal = BootstrapHandoffSignal()
        Thread({
          try {
            val buffer = ByteArray(512)
            // Drain queued output even when the short-lived launcher has exited.
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              if (count > 0) {
                signal.accept(buffer, 0, count)
                Log.i("WirelessRecorder", String(buffer, 0, count, Charsets.UTF_8).take(512))
              }
            }
          } catch (_: Exception) { }
        }, "WirelessRecorderBootstrapOutput").start()
        val seedOutput = stream.openOutputStream()
        seedOutput.write(secret); seedOutput.flush()
        stage = ActivationStage.AUTHENTICATION
        val deadline = SystemClock.elapsedRealtime() + 10000L
        // The authenticated app socket is authoritative; parent EOF alone does
        // not prove that the detached worker failed.
        while (!UsbAudioBridge.isConnected() && SystemClock.elapsedRealtime() < deadline) {
          Thread.sleep(50L)
        }
        check(UsbAudioBridge.isConnected()) { AppText.choose("רכיב ההקלטה לא אישר שהוא מוכן.", "The recording component did not confirm readiness.") }
        stage = ActivationStage.HANDOFF
        seedOutput.write(1); seedOutput.flush()
        val handoffDeadline = SystemClock.elapsedRealtime() + 2000L
        while (!signal.isHandedOff && SystemClock.elapsedRealtime() < handoffDeadline) Thread.sleep(25L)
        check(signal.isHandedOff) { AppText.choose("הרכיב לא אישר את העברת השליטה.", "The component did not acknowledge the handoff.") }
        stage = ActivationStage.DETACH
        // Ownership is now the app-loopback connection, never this ADB socket.
        stream.close(); local.disconnect()
        if (helperStream === stream) helperStream = null
        if (helperManager === local) helperManager = null
        val disconnectedAt = SystemClock.elapsedRealtime()
        stage = ActivationStage.LIVENESS
        val livenessDeadline = disconnectedAt + 5000L
        while (UsbAudioBridge.lastActivityMs() <= disconnectedAt && SystemClock.elapsedRealtime() < livenessDeadline) Thread.sleep(25L)
        check(UsbAudioBridge.isConnected() && UsbAudioBridge.lastActivityMs() > disconnectedAt) {
          AppText.choose("הרכיב לא נשאר פעיל לאחר הפסקת חיבור ההפעלה.", "The component did not stay active after closing the activation connection.")
        }
        Log.i("WirelessRecorder", "DETACHED_HELPER_READY authenticated=true adbClosed=true freshHeartbeat=true")
        main.post { finishWithReadinessOwner(context, promise) }
      } catch (failure: Throwable) {
        try { stream?.close() } catch (_: Exception) { }
        try { local?.disconnect() } catch (_: Exception) { }
        if (helperStream === stream) helperStream = null
        if (helperManager === local) helperManager = null
        // After handoff, ADB closure cannot revoke the worker. Closing its app
        // socket also cleans up failures after it authenticated or detached.
        UsbAudioBridge.disconnectCurrent()
        if (failure is AdbPairingRequiredException || failure is AdbAuthenticationFailedException) {
          context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", false).apply()
        }
        val diagnostic = WirelessActivationFailure.forStage(stage.name)
        val message = diagnostic?.let { AppText.choose(it.hebrewMessage, it.englishMessage) }
          ?: AppText.choose("רכיב ההקלטה לא התחבר. התחבר ל-Wi-Fi וחזור לאשף ההגדרה כדי להפעיל אותו.", "The recording component did not connect. Connect to Wi-Fi and return to guided setup to activate it.")
        Log.e("WirelessRecorder", "DETACHED_ACTIVATION_FAILED stage=$stage", failure)
        setError(message, diagnostic?.errorCode)
        main.post { connecting.set(false); promise.reject("ACTIVATION_FAILED", message, failure, failureDetails(diagnostic)) }
      } finally { Arrays.fill(secret, 0.toByte()) }
    }
  }

  /** The user-visible owner must be foreground before activation succeeds. */
  private fun finishWithReadinessOwner(context: Context, promise: Promise) {
    try {
      check(UsbAudioBridge.isConnected()) { AppText.choose("חיבור ההקלטה נסגר לפני סיום ההפעלה.", "The recording connection closed during activation.") }
      RecorderReadinessService.start(context).whenComplete { _, failure ->
        main.post {
          if (failure == null && UsbAudioBridge.isConnected()) {
            setError(null); connecting.set(false); promise.resolve(null)
          } else failReadinessOwner(promise, failure ?: IllegalStateException(AppText.choose("חיבור ההקלטה נסגר.", "The recording connection closed.")))
        }
      }
    } catch (failure: Throwable) { failReadinessOwner(promise, failure) }
  }

  private fun failReadinessOwner(promise: Promise, failure: Throwable) {
    io.execute {
      UsbAudioBridge.disconnectCurrent()
      val diagnostic = WirelessActivationFailure.forStage("READINESS_OWNER")
      val message = diagnostic?.let { AppText.choose(it.hebrewMessage, it.englishMessage) }
        ?: AppText.choose("לא ניתן להשאיר את רכיב ההקלטה פעיל. חזור למסך האפליקציה ונסה שוב.", "The recording component could not stay active. Return to the app and try again.")
      setError(message, diagnostic?.errorCode)
      main.post { connecting.set(false); promise.reject("READINESS_OWNER_FAILED", message, failure, failureDetails(diagnostic)) }
    }
  }

  private fun discoverLocalConnectionPort(context: Context): Int {
    val latch = CountDownLatch(1)
    val result = AtomicInteger(-1)
    // Upstream AdbMdns accepts only addresses present on this phone's network
    // interfaces. The actual ADB connection still uses loopback exclusively.
    val discovery = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
      if (host != null && port in 1024..65535 && result.compareAndSet(-1, port)) latch.countDown()
    }
    try {
      discovery.start()
      check(latch.await(6000L, TimeUnit.MILLISECONDS)) { AppText.choose("לא נמצא מספר יציאת חיבור מקומי.", "No local connection port was found.") }
      return result.get()
    } finally { discovery.stop() }
  }

  private fun closeHelper() {
    val previousStream = helperStream; helperStream = null
    try { previousStream?.close() } catch (_: Exception) { }
    val previousManager = helperManager; helperManager = null
    try { previousManager?.disconnect() } catch (_: Exception) { }
  }

  private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

  private class WirelessManager(context: Context) : AbsAdbConnectionManager() {
    private val identity: Pair<PrivateKey, Certificate> = loadIdentity(context)
    init { setApi(Build.VERSION.SDK_INT); setHostAddress("127.0.0.1"); setTimeout(8000L, TimeUnit.MILLISECONDS); setThrowOnUnauthorised(true) }
    override fun getPrivateKey(): PrivateKey = identity.first
    override fun getCertificate(): Certificate = identity.second
    override fun getDeviceName(): String = "Codaki Recorder"
  }

  @Synchronized private fun loadIdentity(context: Context): Pair<PrivateKey, Certificate> {
    val file = AtomicFile(File(context.noBackupFilesDir, "wireless-recorder-identity.json"))
    if (file.baseFile.exists()) {
      val json = file.openRead().use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
      val encoded = Base64.decode(json.getString("privateKey"), Base64.NO_WRAP)
      val privateKey = try { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)) }
        finally { Arrays.fill(encoded, 0.toByte()) }
      val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(json.getString("certificate"), Base64.NO_WRAP)))
      check((privateKey as RSAPrivateKey).modulus == (certificate.publicKey as RSAPublicKey).modulus) { AppText.choose("הזהות המקומית אינה תקינה.", "The local identity is invalid.") }
      return Pair(privateKey, certificate)
    }
    val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val certificate = AdbIdentityCertificate.create(keys)
    val json = JSONObject().put("privateKey", Base64.encodeToString(keys.private.encoded, Base64.NO_WRAP))
      .put("certificate", Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
    val output = file.startWrite()
    try { output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync(); file.finishWrite(output) }
    catch (failure: Throwable) { file.failWrite(output); throw failure }
    return Pair(keys.private, certificate)
  }
}
