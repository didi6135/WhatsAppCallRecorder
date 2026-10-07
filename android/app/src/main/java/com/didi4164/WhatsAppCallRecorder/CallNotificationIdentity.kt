package com.didi4164.WhatsAppCallRecorder

import java.util.Locale

/** Optional display label only; never verified identity and never capture authorization. */
class CallNameIdentity(val displayName: String, val packageName: String) {
  override fun toString() = "CallNameIdentity(redacted)"
}

/** No names are cached: match one freshly queried notification at the accepted START. */
object CallNotificationIdentity {
  class NameFields(val callPersonName: String?, val peopleNames: List<String?>, val title: String?) {
    override fun toString() = "CallNotificationNameFields(redacted)"
  }

  fun readDisplayName(enabled: Boolean, signal: AutoCallPolicy.NotificationSignal, fields: () -> NameFields): String? {
    if (!enabled || !AutoCallPolicy.isOngoingCall(signal)) return null
    val names = fields()
    return extractDisplayName(signal, names.callPersonName, names.peopleNames, names.title)
  }

  fun selectLiveCall(
    nowMs: Long,
    live: Collection<AutoCallPolicy.NotificationSignal>,
    audio: AutoCallPolicy.AudioObservation?,
    expectedUid: Int? = null,
    acceptedSignals: Collection<AutoCallPolicy.NotificationSignal>? = null,
  ): AutoCallPolicy.NotificationSignal? {
    // Concurrent call notifications, even from another allowlisted package, are ambiguous.
    val signal = live.filter { AutoCallPolicy.isOngoingCall(it) }.singleOrNull() ?: return null
    if (signal.key.isBlank()) return null
    val uid = AutoCallPolicy().resolveAttribution(nowMs, live, audio).startEligibleUid ?: return null
    if (uid != signal.uid || (expectedUid != null && uid != expectedUid)) return null
    if (acceptedSignals != null && acceptedSignals.none {
        AutoCallPolicy.isOngoingCall(it) && it.packageName == signal.packageName &&
          it.uid == signal.uid && it.key == signal.key
      }) return null
    return signal
  }

  /** Read only these display-name fields, and only after the structural/live selection gate. */
  fun extractDisplayName(
    signal: AutoCallPolicy.NotificationSignal,
    callPersonName: String?,
    peopleNames: List<String?>,
    title: String?,
  ): String? {
    if (!AutoCallPolicy.isOngoingCall(signal) || peopleNames.size > 1) return null
    val callPerson = usableName(callPersonName)
    val singlePerson = peopleNames.singleOrNull()?.let(::usableName)
    // Conflicting structured labels are not safe to attach to a recording.
    if (callPerson != null && singlePerson != null && callPerson != singlePerson) return null
    return callPerson ?: singlePerson ?: usableName(title)
  }

  private fun usableName(value: String?): String? {
    val name = RecordingNames.sanitizeDisplayName(value) ?: return null
    val label = name.lowercase(Locale.ROOT).replace(Regex("[\\s:•·.]+"), " ").trim()
    if (label in genericLabels || operationPrefixes.any { label == it || label.startsWith("$it ") } ||
      Regex("[0-9]{1,2} [0-9]{2}(?: [0-9]{2})?").matches(label) ||
      Regex("[0-9]+ (?:participants?|people|משתתפים|משתתפות)").matches(label)) return null
    return name
  }

  private val genericLabels = setOf(
    "whatsapp", "whatsapp business", "call", "voice call", "video call", "whatsapp call",
    "ongoing call", "incoming call", "outgoing call", "missed call", "group call",
    "voice chat", "video chat", "unknown", "unknown caller", "private number",
    "שיחה", "שיחת קול", "שיחה קולית", "שיחת וידאו", "שיחה נכנסת", "שיחה יוצאת",
    "שיחה פעילה", "שיחה שלא נענתה", "שיחה קבוצתית", "לא ידוע", "מספר חסוי",
    "appel", "appel vocal", "appel vidéo", "appel en cours", "llamada", "llamada de voz",
    "videollamada", "anruf", "sprachanruf", "videoanruf", "chamada", "chamada de voz",
    "chamada de vídeo", "звонок", "голосовой звонок", "видеозвонок", "مكالمة", "مكالمة صوتية",
  )
  private val operationPrefixes = setOf(
    "calling", "ringing", "connecting", "reconnecting", "incoming voice call", "outgoing voice call",
    "incoming video call", "outgoing video call", "מתקשר", "מתקשרת", "מחייג", "מחייגת", "מתחבר", "מצלצל",
  )
}
