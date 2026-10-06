package com.didi4164.WhatsAppCallRecorder

/** Missing evidence gets a bounded period of awake time; CPU suspension cannot consume it. */
class AutoEvidenceRecoveryWatchdog(private val recoveryWindowMs: Long = 5000L) {
  enum class State { FRESH, RECOVERING, EXPIRED }
  private var missingSinceUptimeMs: Long? = null

  init { require(recoveryWindowMs > 0L) }

  fun reset() { missingSinceUptimeMs = null }

  fun observe(nowUptimeMs: Long, evidenceFresh: Boolean): State {
    require(nowUptimeMs >= 0L)
    if (evidenceFresh) { reset(); return State.FRESH }
    val since = missingSinceUptimeMs
    if (since == null || nowUptimeMs < since) {
      missingSinceUptimeMs = nowUptimeMs
      return State.RECOVERING
    }
    return if (nowUptimeMs - since >= recoveryWindowMs) State.EXPIRED else State.RECOVERING
  }
}
