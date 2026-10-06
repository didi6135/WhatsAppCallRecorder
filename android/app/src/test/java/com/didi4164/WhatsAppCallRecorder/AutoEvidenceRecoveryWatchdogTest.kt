package com.didi4164.WhatsAppCallRecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoEvidenceRecoveryWatchdogTest {
  private val waUid = 10123
  private val call = AutoCallPolicy.NotificationSignal("com.whatsapp", waUid, "ram-only", "call", true, callType = 2)

  @Test fun expiresOnlyAfterFiveSecondsOfObservedAwakeTime() {
    val watchdog = AutoEvidenceRecoveryWatchdog()
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(1000, false))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(5999, false))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.EXPIRED, watchdog.observe(6000, false))
  }

  @Test fun freshEvidenceClearsDeadlineAndLaterLossGetsFullWindow() {
    val watchdog = AutoEvidenceRecoveryWatchdog()
    watchdog.observe(1000, false)
    assertEquals(AutoEvidenceRecoveryWatchdog.State.FRESH, watchdog.observe(5900, true))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(6000, false))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(10999, false))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.EXPIRED, watchdog.observe(11000, false))
  }

  @Test fun resetForNewForegroundActivationClearsPreviousFailure() {
    val watchdog = AutoEvidenceRecoveryWatchdog()
    watchdog.observe(0, false)
    watchdog.observe(5000, false)
    watchdog.reset()
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(6000, false))
  }

  @Test fun deepSleepElapsedJumpDoesNotConsumeAwakeRecoveryOrAuthorizeStaleStart() {
    val watchdog = AutoEvidenceRecoveryWatchdog()
    val policy = AutoCallPolicy()
    watchdog.observe(1000, true)
    val afterSleepElapsed = 86_401_000L
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(1001, false))
    val old = AutoCallPolicy.AudioObservation(3, waUid, true, 1000)
    assertEquals(AutoCallPolicy.Decision.None, policy.step(afterSleepElapsed, listOf(call), old, AutoCallPolicy.Environment()))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(2501, false))
    val fresh = AutoCallPolicy.AudioObservation(3, waUid, true, afterSleepElapsed + 1500)
    assertEquals(AutoEvidenceRecoveryWatchdog.State.FRESH, watchdog.observe(2501, true))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(fresh.receivedAtMs, listOf(call), fresh, AutoCallPolicy.Environment()))
    val second = fresh.copy(receivedAtMs = fresh.receivedAtMs + 1000)
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(second.receivedAtMs, listOf(call), second, AutoCallPolicy.Environment()))
  }

  @Test fun staleActiveCaptureStillStopsDuringRecoveryWindow() {
    val watchdog = AutoEvidenceRecoveryWatchdog()
    val policy = AutoCallPolicy()
    policy.onStartAccepted(waUid)
    val old = AutoCallPolicy.AudioObservation(3, waUid, true, 1000)
    val active = AutoCallPolicy.Environment(busy = true, automaticCapture = true)
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(1001, false))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(100000, listOf(call), old, active))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(2501, false))
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(101500, listOf(call), old, active))
  }

  @Test fun actualTransportOrListenerLossRemainsImmediateEvenWhileRecovering() {
    val policy = AutoCallPolicy()
    policy.onStartAccepted(waUid)
    val audio = AutoCallPolicy.AudioObservation(3, waUid, true, 1000)
    val active = AutoCallPolicy.Environment(busy = true, automaticCapture = true)
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(1000, listOf(call), audio, active.copy(helperConnected = false)))
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(1000, listOf(call), audio, active.copy(listenerConnected = false)))
  }

  @Test fun repeatedChecksCannotRestartTheSameMissingEvidenceDeadline() {
    val watchdog = AutoEvidenceRecoveryWatchdog()
    for (now in listOf(0L, 100L, 500L, 1000L, 2000L, 4999L))
      assertEquals(AutoEvidenceRecoveryWatchdog.State.RECOVERING, watchdog.observe(now, false))
    assertEquals(AutoEvidenceRecoveryWatchdog.State.EXPIRED, watchdog.observe(5000, false))
  }
}
