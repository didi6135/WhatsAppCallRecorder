package com.didi4164.WhatsAppCallRecorder

/** Structural call evidence only. This policy has no Android, audio, or persistence access. */
class AutoCallPolicy {
  data class NotificationSignal(
    val packageName: String,
    val uid: Int,
    val key: String,
    val category: String?,
    val ongoing: Boolean,
    val groupSummary: Boolean = false,
    val callType: Int = 0,
    val hasAnswer: Boolean = false,
    val hasHangup: Boolean = false,
    val showChronometer: Boolean = false,
    val chronometerCountDown: Boolean = false,
    val actionCount: Int = 0,
    val hasFullScreenIntent: Boolean = false,
  )

  data class TelecomObservation(
    val known: Boolean,
    val packageCode: Int,
    val userId: Int,
    val liveCalls: Int,
    val activeCalls: Int,
    val foregroundMatched: Boolean,
    val selfManaged: Boolean,
    val voip: Boolean,
    val receivedAtMs: Long,
  )
  data class AudioObservation(
    val mode: Int, val ownerUid: Int, val known: Boolean, val receivedAtMs: Long,
    val telecom: TelecomObservation? = null,
  )
  data class CallAttribution(
    val startEligibleUid: Int? = null,
    val knownCurrentOwnerUid: Int? = null,
    val definitiveEnded: Boolean = false,
    val delegated: Boolean = false,
    val sampleAtMs: Long? = null,
    val telecomSampleAtMs: Long? = null,
  )
  data class Environment(
    val armed: Boolean = true,
    val notificationAccess: Boolean = true,
    val listenerConnected: Boolean = true,
    val helperConnected: Boolean = true,
    val busy: Boolean = false,
    val automaticCapture: Boolean = false,
  )
  sealed class Decision {
    object None : Decision()
    data class Start(val uid: Int) : Decision()
    object Stop : Decision()
  }

  companion object {
    const val COMMUNICATION_MODE = 3
    const val MAX_SAMPLE_AGE_MS = 2500L
    const val START_STABILITY_MS = 500L
    const val STOP_GRACE_MS = 1500L
    private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun allowsPackage(packageName: String): Boolean = packageName in PACKAGES

    /** CATEGORY_CALL alone and incoming/screening notifications never authorize recording. */
    fun isOngoingCall(signal: NotificationSignal): Boolean {
      if (!allowsPackage(signal.packageName) || signal.uid < 10000 || signal.groupSummary) return false
      if (signal.callType == 1 || signal.callType == 3 || signal.hasAnswer) return false
      if (signal.callType == 2) return true
      if (!signal.ongoing || signal.callType != 0 || signal.category != "call") return false
      return signal.hasHangup || (signal.showChronometer && !signal.chronometerCountDown)
    }
  }

  private data class EvidenceTimes(val audioAtMs: Long, val telecomAtMs: Long? = null) {
    fun discontinuous(next: EvidenceTimes): Boolean =
      audioAtMs > next.audioAtMs || next.audioAtMs - audioAtMs > MAX_SAMPLE_AGE_MS ||
        (telecomAtMs == null) != (next.telecomAtMs == null) ||
        (telecomAtMs != null && next.telecomAtMs != null &&
          (telecomAtMs > next.telecomAtMs || next.telecomAtMs - telecomAtMs > MAX_SAMPLE_AGE_MS))

    fun advances(next: EvidenceTimes): Boolean = next.audioAtMs > audioAtMs &&
      (telecomAtMs == null || (next.telecomAtMs != null && next.telecomAtMs > telecomAtMs))

    fun spans(next: EvidenceTimes, durationMs: Long): Boolean = next.audioAtMs - audioAtMs >= durationMs &&
      (telecomAtMs == null || (next.telecomAtMs != null && next.telecomAtMs - telecomAtMs >= durationMs))
  }
  private data class Pending(val uid: Int, val delegated: Boolean, val first: EvidenceTimes, val last: EvidenceTimes, val samples: Int)
  private data class IdleProof(val first: EvidenceTimes, val last: EvidenceTimes)
  private data class SuppressedEpisode(val delegated: Boolean, val idleProof: IdleProof? = null)
  private var pending: Pending? = null
  private var captureUid: Int? = null
  private var captureDelegated = false
  private var acceptedUid: Int? = null
  private var acceptedDelegated = false
  private var invalidSinceMs: Long? = null
  private var waitingForFinalization = false
  private val suppressed = mutableMapOf<Int, SuppressedEpisode>()

  fun recordingUid(): Int? = captureUid
  fun acceptedEpisodeUid(): Int? = acceptedUid
  fun isSuppressed(uid: Int?): Boolean = uid != null && suppressed.containsKey(uid)
  fun isCurrentCallSuppressed(attribution: CallAttribution): Boolean {
    val uid = attribution.startEligibleUid ?: attribution.knownCurrentOwnerUid
    return if (uid != null) isSuppressed(uid) else suppressed.values.any { it.delegated }
  }

  fun reset() {
    pending = null
    captureUid = null
    captureDelegated = false
    invalidSinceMs = null
    waitingForFinalization = false
    suppressed.clear()
    // An accepted capture may still be finalizing after disable/reset; retain its attribution.
  }

  /** A user STOP blocks only this UID's current audio episode, even if notifications flicker. */
  fun suppressCurrentCall(uid: Int?, delegated: Boolean = false) {
    val actualUid = captureUid ?: uid
    if (actualUid != null && actualUid >= 0) {
      val actualDelegated = if (captureUid != null) captureDelegated || (actualUid == uid && delegated) else
        delegated || pending?.takeIf { it.uid == actualUid }?.delegated == true ||
          (acceptedUid == actualUid && acceptedDelegated) || suppressed[actualUid]?.delegated == true
      suppressed[actualUid] = SuppressedEpisode(actualDelegated)
    }
    pending = null
  }

  fun onStartAccepted(uid: Int) {
    captureDelegated = pending?.takeIf { it.uid == uid }?.delegated == true
    captureUid = uid
    acceptedUid = uid
    acceptedDelegated = captureDelegated
    pending = null
    invalidSinceMs = null
  }

  fun onStopAccepted() {
    captureUid = null
    captureDelegated = false
    pending = null
    invalidSinceMs = null
    waitingForFinalization = true
  }

  fun onCaptureCompleted(failed: Boolean = false) {
    // A completion failure belongs to the accepted recording, even after audio ownership changed.
    if (failed) acceptedUid?.let { uid -> suppressed[uid] = SuppressedEpisode(acceptedDelegated) }
    acceptedUid = null
    acceptedDelegated = false
    captureUid = null
    captureDelegated = false
    pending = null
    invalidSinceMs = null
    // Actual busy=false is still required before a subsequent START.
    waitingForFinalization = true
  }

  private fun isFresh(audio: AudioObservation?, nowMs: Long): Boolean = audio != null && audio.known &&
    audio.receivedAtMs >= 0L && nowMs - audio.receivedAtMs in 0..MAX_SAMPLE_AGE_MS

  fun resolveAttribution(nowMs: Long, notifications: Collection<NotificationSignal>, audio: AudioObservation?): CallAttribution {
    if (!isFresh(audio, nowMs)) return CallAttribution()
    val owner = audio!!
    val telecom = owner.telecom?.takeIf { it.known && it.receivedAtMs >= 0L &&
      nowMs - it.receivedAtMs in 0..MAX_SAMPLE_AGE_MS }
    val ended = owner.mode == 0 && telecom != null && telecom.liveCalls == 0 && telecom.activeCalls == 0 &&
      telecom.packageCode == 0 && telecom.userId == -1 && !telecom.foregroundMatched && !telecom.selfManaged && !telecom.voip
    if (owner.mode != COMMUNICATION_MODE) return CallAttribution(
      definitiveEnded = ended, sampleAtMs = owner.receivedAtMs,
      telecomSampleAtMs = telecom?.receivedAtMs,
    )
    if (owner.ownerUid == 1000) {
      if (telecom == null || telecom.liveCalls != 1 || telecom.activeCalls != 1 || telecom.userId < 0 ||
        !telecom.foregroundMatched || !telecom.selfManaged || !telecom.voip) return CallAttribution(sampleAtMs = owner.receivedAtMs)
      val packageName = when (telecom.packageCode) { 1 -> "com.whatsapp"; 2 -> "com.whatsapp.w4b"; else -> return CallAttribution(sampleAtMs = owner.receivedAtMs) }
      // StatusBarNotification supplies the trusted full UID; Telecom supplies its current package/user.
      val matches = notifications.filter { it.packageName == packageName && !it.groupSummary &&
        it.uid >= 10000 && it.uid % 100000 >= 10000 && it.uid / 100000 == telecom.userId }.map { it.uid }.toSet()
      val uid = matches.singleOrNull() ?: return CallAttribution(sampleAtMs = owner.receivedAtMs)
      return CallAttribution(
        startEligibleUid = uid.takeIf { notifications.any { signal -> signal.uid == uid && signal.packageName == packageName && isOngoingCall(signal) } },
        knownCurrentOwnerUid = uid, delegated = true, sampleAtMs = owner.receivedAtMs,
        telecomSampleAtMs = telecom.receivedAtMs,
      )
    }
    val eligible = notifications.any { it.uid == owner.ownerUid && isOngoingCall(it) }
    return CallAttribution(
      startEligibleUid = owner.ownerUid.takeIf { eligible },
      knownCurrentOwnerUid = owner.ownerUid,
      sampleAtMs = owner.receivedAtMs,
    )
  }

  fun step(nowMs: Long, notifications: Collection<NotificationSignal>, audio: AudioObservation?, env: Environment): Decision {
    val fresh = isFresh(audio, nowMs)
    val attribution = resolveAttribution(nowMs, notifications, audio)
    // Only a fresh known idle/other-owner observation can end a manually suppressed episode.
    suppressed.keys.toList().forEach { uid ->
      val oldEpisode = suppressed.getValue(uid)
      // Attribution can become unavailable during a direct-to-Telecom handoff. Do not
      // require a successful delegated START proof before protecting an existing STOP.
      val requiresTelecomEndProof = fresh && (
        (audio!!.mode == COMMUNICATION_MODE && audio.ownerUid == 1000) ||
          (audio.mode == 0 && audio.telecom != null && !attribution.definitiveEnded))
      val episode = if (requiresTelecomEndProof ||
        (attribution.delegated && attribution.knownCurrentOwnerUid == uid))
        oldEpisode.copy(delegated = true) else oldEpisode
      // A held Telecom call can temporarily leave AudioService in NORMAL. Unknown or live
      // Telecom evidence must never turn that transition into permission to undo manual STOP.
      val idle = fresh && if (episode.delegated) attribution.definitiveEnded else
        audio!!.mode != COMMUNICATION_MODE || (attribution.knownCurrentOwnerUid != null && attribution.knownCurrentOwnerUid != uid)
      if (!idle) suppressed[uid] = episode.copy(idleProof = null)
      else {
        val times = EvidenceTimes(audio!!.receivedAtMs, attribution.telecomSampleAtMs.takeIf { episode.delegated })
        val proof = episode.idleProof
        if (proof == null || proof.last.discontinuous(times))
          suppressed[uid] = episode.copy(idleProof = IdleProof(times, times))
        else if (proof.last.advances(times)) {
          if (proof.first.spans(times, STOP_GRACE_MS)) suppressed.remove(uid)
          else suppressed[uid] = episode.copy(idleProof = proof.copy(last = times))
        }
      }
    }

    val operational = env.armed && env.notificationAccess && env.listenerConnected && env.helperConnected
    if (!operational) {
      pending = null
      invalidSinceMs = null
      return if (captureUid != null || env.automaticCapture) Decision.Stop else Decision.None
    }

    val eligibleUid = attribution.startEligibleUid?.takeUnless(suppressed::containsKey)

    val activeUid = captureUid
    if (activeUid != null || env.automaticCapture) {
      pending = null
      if (activeUid != null && attribution.delegated && attribution.knownCurrentOwnerUid == activeUid) {
        captureDelegated = true
        if (acceptedUid == activeUid) acceptedDelegated = true
      }
      // A known different owner is a different call, not a transient notification update.
      if (activeUid != null && attribution.knownCurrentOwnerUid != null && attribution.knownCurrentOwnerUid != activeUid)
        return Decision.Stop
      if (activeUid != null && eligibleUid == activeUid) {
        invalidSinceMs = null
        return Decision.None
      }
      val since = invalidSinceMs
      if (since == null) invalidSinceMs = nowMs
      else if (nowMs - since >= STOP_GRACE_MS) return Decision.Stop
      return Decision.None
    }

    if (waitingForFinalization) {
      if (env.busy) { pending = null; return Decision.None }
      waitingForFinalization = false
    }
    if (env.busy || eligibleUid == null) {
      pending = null
      return Decision.None
    }

    val times = EvidenceTimes(audio!!.receivedAtMs, attribution.telecomSampleAtMs.takeIf { attribution.delegated })
    val old = pending
    if (old == null || old.uid != eligibleUid || old.delegated != attribution.delegated || old.last.discontinuous(times)) {
      pending = Pending(eligibleUid, attribution.delegated, times, times, 1)
      return Decision.None
    }
    if (!old.last.advances(times)) return Decision.None
    val next = old.copy(last = times, samples = old.samples + 1)
    pending = next
    return if (next.samples >= 2 && next.first.spans(next.last, START_STABILITY_MS))
      Decision.Start(eligibleUid) else Decision.None
  }
}
