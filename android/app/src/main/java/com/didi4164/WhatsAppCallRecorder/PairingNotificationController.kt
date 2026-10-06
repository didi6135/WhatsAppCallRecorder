package com.didi4164.WhatsAppCallRecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.facebook.react.bridge.Promise
import com.codaki.usbaudio.ShellAudioCompatibility
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

/** Explicit, short-lived setup. Only a verified service on this phone is paired on loopback. */
object PairingNotificationController {
  internal const val CHANNEL = "wireless_recorder_pairing"
  internal const val NOTIFICATION_ID = 4165
  internal const val ACTION_BEGIN = "recorder.pairing.BEGIN"
  internal const val ACTION_CODE = "recorder.pairing.CODE"
  internal const val ACTION_CANCEL = "recorder.pairing.CANCEL"
  internal const val EXTRA_SESSION = "pairing_session"
  internal const val EXTRA_CODE = "pairing_code"
  private const val SERVICE_TYPE = "_adb-tls-pairing._tcp."
  private const val MAX_SESSION_MS = 120_000L
  private val main = Handler(Looper.getMainLooper())
  private var current: Session? = null // Main thread only, including NSD callbacks.
  @Volatile private var snapshot: Map<String, Any?> = emptyStatus()

  private class Session(val context: Context, val id: String, val promise: Promise) {
    val deadline = SystemClock.elapsedRealtime() + MAX_SESSION_MS
    val manager = context.getSystemService(NsdManager::class.java)
    var service: PairingNotificationService? = null
    var armed = false
    var settled = false
    var pairing = false
    var discovery: NsdManager.DiscoveryListener? = null
    val present = mutableSetOf<String>()
    val endpoints = mutableMapOf<String, Int>()
    val pending = ArrayDeque<NsdServiceInfo>()
    var resolving = false
    var error: String? = null
    var timeout: Runnable? = null
  }

  /** Resolves when the notification and NSD are armed, before the Settings screen is opened. */
  fun prepare(context: Context, promise: Promise) {
    main.post {
      val app = context.applicationContext
      if (!ShellAudioCompatibility.isCandidateSdk(Build.VERSION.SDK_INT)) {
        promise.reject("UNSUPPORTED_ANDROID", "חיבור השיחות דורש Android 14 ומעלה. ב־Android 14/15 התמיכה ניסיונית")
        return@post
      }
      if (current?.pairing == true) {
        promise.reject("PAIRING_BUSY", "האישור עדיין מתבצע. יש להמתין לסיום")
        return@post
      }
      finishCurrent(null, clearError = true)
      val notifications = app.getSystemService(NotificationManager::class.java)
      notifications.createNotificationChannel(NotificationChannel(CHANNEL, "הגדרת הקלטת שיחות", NotificationManager.IMPORTANCE_DEFAULT))
      if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
          !notifications.areNotificationsEnabled() || notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
        publish(null, "יש לאפשר התראות כדי להזין את קוד האישור מתוך מסך ההגדרות")
        promise.reject("NOTIFICATION_PERMISSION", snapshot["error"] as String)
        return@post
      }
      val session = Session(app, UUID.randomUUID().toString(), promise)
      current = session
      publish(session)
      session.timeout = Runnable {
        if (current === session) finishCurrent("זמן ההגדרה הסתיים. יש לפתוח שוב את אשף ההגדרה")
      }.also { main.postDelayed(it, MAX_SESSION_MS) }
      try {
        app.startForegroundService(Intent(app, PairingNotificationService::class.java)
          .setAction(ACTION_BEGIN).putExtra(EXTRA_SESSION, session.id))
      } catch (_: Exception) {
        finishCurrent("לא ניתן להתחיל את ההגדרה. יש לחזור לאפליקציה ולנסות שוב")
      }
    }
  }

  fun cancel(context: Context) {
    main.post { finishCurrent(null, clearError = true) }
  }

  fun statusMap(): Map<String, Any?> = snapshot.toMap()

  internal fun acceptsSession(id: String?): Boolean = id != null && current?.id == id

  internal fun serviceStarted(service: PairingNotificationService, action: String?, id: String?, code: String?) {
    val session = current
    if (session == null || session.id != id) {
      service.finishFor(id)
      return
    }
    session.service = service
    if (SystemClock.elapsedRealtime() >= session.deadline) {
      finishCurrent("זמן ההגדרה הסתיים. יש לפתוח שוב את אשף ההגדרה")
      return
    }
    when (action) {
      ACTION_BEGIN -> if (!session.armed) arm(session)
      ACTION_CODE -> submit(session, code.orEmpty())
      else -> finishCurrent("בקשת ההגדרה אינה תקינה")
    }
  }

  internal fun serviceDestroyed(service: PairingNotificationService, id: String?) {
    val session = current
    // A stopped service from an earlier prepare must never cancel a newer generation.
    if (session != null && session.id == id && session.service === service) {
      finishCurrent("ההגדרה נעצרה. יש לפתוח שוב את אשף ההגדרה", stopService = false)
    }
  }

  internal fun serviceTimedOut(service: PairingNotificationService, id: String?) {
    if (current?.id == id && current?.service === service) finishCurrent("זמן ההגדרה הסתיים. יש לפתוח שוב את אשף ההגדרה")
    else service.finishFor(id)
  }

  /** Called only by our explicit, nonexported receiver. Never passes an NSD host to ADB. */
  internal fun receive(context: Context, intent: Intent, code: String?) {
    val session = current ?: return
    val id = intent.getStringExtra(EXTRA_SESSION)
    val expected = Uri.parse("recorder-pairing://${session.id}/${intent.action}")
    if (id != session.id || intent.data != expected || SystemClock.elapsedRealtime() >= session.deadline) return
    when (intent.action) {
      ACTION_CANCEL -> finishCurrent(null, clearError = true)
      ACTION_CODE -> {
        if (session.pairing) return
        val normalized = PairingNotificationRules.normalizeCode(code)
        if (normalized == null) {
          session.error = "יש להזין בדיוק את שש הספרות שמופיעות בחלון האישור"
          update(session)
          return
        }
        try {
          context.startForegroundService(Intent(context, PairingNotificationService::class.java)
            .setAction(ACTION_CODE).putExtra(EXTRA_SESSION, id).putExtra(EXTRA_CODE, normalized))
        } catch (_: Exception) {
          session.error = "לא ניתן לבצע את האישור. יש לחזור לאפליקציה ולנסות שוב"
          update(session)
        }
      }
    }
  }

  private fun arm(session: Session) {
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(type: String) = Unit
      override fun onDiscoveryStopped(type: String) = Unit
      override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
      override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
        main.post { if (current === session) finishCurrent("לא ניתן לזהות את חלון האישור. יש לנסות שוב") }
      }
      override fun onServiceFound(info: NsdServiceInfo) {
        main.post {
          if (current !== session || session.pairing || info.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return@post
          if (session.present.size < 32 && session.present.add(info.serviceName)) {
            session.pending.addLast(info)
            resolveNext(session)
          }
        }
      }
      override fun onServiceLost(info: NsdServiceInfo) {
        main.post {
          if (current !== session) return@post
          session.present.remove(info.serviceName)
          session.endpoints.remove(info.serviceName)
          update(session)
        }
      }
    }
    session.discovery = listener
    try {
      session.manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
      session.armed = true
      update(session)
      session.settled = true
      session.promise.resolve(null)
    } catch (_: Exception) {
      finishCurrent("לא ניתן לזהות את חלון האישור. יש לנסות שוב")
    }
  }

  @Suppress("DEPRECATION")
  private fun resolveNext(session: Session) {
    if (current !== session || session.resolving || session.pairing) return
    val info = session.pending.removeFirstOrNull() ?: return
    if (info.serviceName !in session.present) { resolveNext(session); return }
    session.resolving = true
    val listener = object : NsdManager.ResolveListener {
      override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        main.post {
          if (current !== session) return@post
          session.resolving = false
          resolveNext(session)
        }
      }
      override fun onServiceResolved(resolved: NsdServiceInfo) {
        main.post {
          if (current !== session) return@post
          session.resolving = false
          val hosts = resolved.hostAddresses.ifEmpty { listOfNotNull(resolved.host) }
          if (resolved.serviceName in session.present && resolved.port in 1024..65535 && hosts.any(::isThisPhone)) {
            session.endpoints[resolved.serviceName] = resolved.port
          }
          update(session)
          resolveNext(session)
        }
      }
    }
    try { session.manager.resolveService(info, listener) }
    catch (_: Exception) { session.resolving = false; resolveNext(session) }
  }

  private fun isThisPhone(address: InetAddress): Boolean {
    return try {
      val ownAddresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
      PairingNotificationRules.isLocalAddress(address, ownAddresses)
    } catch (_: Exception) { false }
  }

  private fun submit(session: Session, code: String) {
    if (session.pairing) return
    val ports = session.endpoints.values.toSet()
    val localPort = PairingNotificationRules.singlePort(ports)
    if (PairingNotificationRules.normalizeCode(code) == null || localPort == null) {
      session.error = if (ports.size > 1) "זוהו כמה חלונות אישור. יש לסגור אותם ולפתוח חלון אישור אחד בטלפון הזה"
        else "חלון האישור לא זוהה עדיין. יש להשאיר אותו פתוח ולנסות שוב מההתראה"
      update(session)
      return
    }
    session.pairing = true
    session.error = null
    update(session)
    // NativeWirelessAudioBridge always connects to 127.0.0.1, never an advertised LAN host.
    try {
      NativeWirelessAudioBridge.pairAsync(session.context, localPort, code) { result ->
        main.post {
          if (current !== session) return@post
          session.pairing = false
          result.fold(
            onSuccess = { finishCurrent(null, clearError = true) },
            onFailure = {
              session.error = "האישור לא הצליח. יש להשאיר את חלון האישור פתוח ולבדוק את הקוד, או לפתוח חלון חדש"
              update(session)
            }
          )
        }
      }
    } catch (_: Exception) {
      session.pairing = false
      session.error = "לא ניתן לבצע את האישור. יש לנסות שוב מתוך אשף ההגדרה"
      update(session)
    }
  }

  private fun update(session: Session) {
    if (current !== session) return
    publish(session)
    try { session.context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(session.context, session)) }
    catch (_: Exception) { finishCurrent("התראת ההגדרה אינה זמינה. יש לאפשר התראות ולנסות שוב") }
  }

  internal fun foregroundNotification(context: Context): Notification = notification(context, current)

  private fun notification(context: Context, session: Session?): Notification {
    val ports = session?.endpoints?.values?.toSet().orEmpty()
    val text = when {
      session?.pairing == true -> "מאשרים את החיבור בטלפון. יש להשאיר את חלון האישור פתוח"
      session?.error != null -> session.error!!
      ports.size == 1 -> "בלי לסגור את חלון האישור: הזינו כאן את הקוד בן שש הספרות שמופיע בו"
      ports.size > 1 -> "יש לסגור חלונות אישור נוספים ולהשאיר חלון אחד פתוח בטלפון הזה"
      else -> "פתחו בהגדרות: ניפוי באגים אלחוטי ← התאמת מכשיר באמצעות קוד. השאירו את החלון פתוח"
    }
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent(context, MainActivity::class.java)
    val content = PendingIntent.getActivity(context, 4165, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = Notification.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_menu_manage)
      .setContentTitle("אישור הקלטת שיחות")
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setContentIntent(content)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setLocalOnly(true)
      .setAllowSystemGeneratedContextualActions(false)
    if (session != null) {
      builder.setTimeoutAfter((session.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
      if (!session.pairing && ports.size == 1) {
        val reply = android.app.RemoteInput.Builder(EXTRA_CODE).setLabel("קוד האישור בן שש ספרות").build()
        builder.addAction(Notification.Action.Builder(null, "הזנת קוד", actionIntent(context, session, ACTION_CODE, mutable = true))
          .addRemoteInput(reply).setAllowGeneratedReplies(false).setAuthenticationRequired(true).build())
      }
      builder.addAction(Notification.Action.Builder(null, "ביטול", actionIntent(context, session, ACTION_CANCEL, mutable = false)).build())
    }
    return builder.build()
  }

  private fun actionIntent(context: Context, session: Session, action: String, mutable: Boolean): PendingIntent {
    val intent = Intent(context, PairingNotificationReceiver::class.java)
      .setAction(action).setData(Uri.parse("recorder-pairing://${session.id}/$action"))
      .putExtra(EXTRA_SESSION, session.id)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(context, 0, intent, flags)
  }

  private fun finishCurrent(error: String?, clearError: Boolean = false, stopService: Boolean = true) {
    val session = current
    current = null // Invalidates callbacks and old PendingIntents before releasing resources.
    if (session != null) {
      if (session.pairing) NativeWirelessAudioBridge.cancelPairing()
      session.timeout?.let(main::removeCallbacks)
      session.discovery?.let { try { session.manager.stopServiceDiscovery(it) } catch (_: Exception) {} }
      session.discovery = null
      session.pending.clear()
      session.endpoints.clear()
      if (!session.settled) {
        session.settled = true
        session.promise.reject("PAIRING_SETUP", error ?: "ההגדרה בוטלה")
      }
      if (stopService) session.service?.finishFor(session.id)
      session.context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
    publish(null, if (clearError) null else error)
  }

  private fun publish(session: Session?, error: String? = session?.error) {
    snapshot = mapOf("active" to (session != null), "discovering" to (session?.armed == true && !session.pairing),
      "pairing" to (session?.pairing == true), "localPortAvailable" to (session?.endpoints?.values?.toSet()?.size == 1), "error" to error)
  }

  private fun emptyStatus(): Map<String, Any?> = mapOf("active" to false, "discovering" to false,
    "pairing" to false, "localPortAvailable" to false, "error" to null)
}
