package com.didi4164.WhatsAppCallRecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCallPolicyTest {
  private val waUid = 10123
  private val businessUid = 10456
  private fun call(uid: Int = waUid, packageName: String = "com.whatsapp") =
    AutoCallPolicy.NotificationSignal(packageName, uid, "ram-only-key", "call", true, callType = 2)
  private fun audio(at: Long, uid: Int = waUid, mode: Int = 3, known: Boolean = true) =
    AutoCallPolicy.AudioObservation(mode, uid, known, at)
  private fun env(busy: Boolean = false, capture: Boolean = false) =
    AutoCallPolicy.Environment(busy = busy, automaticCapture = capture)
  private fun started(): AutoCallPolicy = AutoCallPolicy().also {
    assertEquals(AutoCallPolicy.Decision.None, it.step(1000, listOf(call()), audio(1000), env()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), it.step(2000, listOf(call()), audio(2000), env()))
    it.onStartAccepted(waUid)
  }

  @Test fun onlyBothExactWhatsAppPackagesAreAccepted() {
    assertTrue(AutoCallPolicy.isOngoingCall(call()))
    assertTrue(AutoCallPolicy.isOngoingCall(call(businessUid, "com.whatsapp.w4b")))
    listOf("com.whatsapp.clone", "com.whatsapp.w4b.fake", "com.skype", "android").forEach {
      assertFalse(AutoCallPolicy.isOngoingCall(call(packageName = it)))
    }
  }

  @Test fun incomingScreeningMissedAndGroupNotificationsCannotAuthorizeCapture() {
    listOf(call().copy(callType = 1), call().copy(callType = 3), call().copy(callType = 0, ongoing = false, hasHangup = true),
      call().copy(groupSummary = true), call().copy(hasAnswer = true), call().copy(uid = -1)).forEach {
      assertFalse(AutoCallPolicy.isOngoingCall(it))
    }
  }

  @Test fun explicitOngoingCallStyleDoesNotRequireLegacyOngoingFlag() {
    val signal = call().copy(ongoing = false)
    assertTrue(AutoCallPolicy.isOngoingCall(signal))
    val policy = AutoCallPolicy()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(signal), audio(1000), env()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(2000, listOf(signal), audio(2000), env()))
    assertFalse(AutoCallPolicy.isOngoingCall(signal.copy(callType = 0, hasHangup = true)))
  }

  @Test fun normalizedAndroidXNullAnswerAllowsOnlyOngoingWithExactAudioOwner() {
    // The on-device producer/extractor probe establishes this AndroidX null-answer shape.
    val ongoing = call().copy(ongoing = false, hasAnswer = false, hasHangup = true)
    val policy = AutoCallPolicy()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(ongoing), audio(1000, businessUid), env()))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(2000, listOf(ongoing), audio(2000, known = false), env()))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(ongoing), audio(3000), env()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(4000, listOf(ongoing), audio(4000), env()))

    val rejected = listOf(
      ongoing.copy(callType = 1, hasAnswer = true, hasHangup = false),
      ongoing.copy(callType = 3, hasAnswer = true),
      ongoing.copy(callType = 99),
      ongoing.copy(uid = -1),
      ongoing.copy(uid = 1000),
      ongoing.copy(groupSummary = true),
    )
    rejected.forEach { signal ->
      val blocked = AutoCallPolicy()
      assertEquals(AutoCallPolicy.Decision.None, blocked.step(1000, listOf(signal), audio(1000, signal.uid), env()))
      assertEquals(AutoCallPolicy.Decision.None, blocked.step(2000, listOf(signal), audio(2000, signal.uid), env()))
    }
  }

  @Test fun systemUidCannotBeAttributedToWhatsAppEvenWithMatchingOwner() {
    for (uid in listOf(0, 1000, 9999)) {
      val invalid = call(uid)
      assertFalse(AutoCallPolicy.isOngoingCall(invalid))
      val policy = AutoCallPolicy()
      assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(invalid), audio(1000, uid), env()))
      assertEquals(AutoCallPolicy.Decision.None, policy.step(2000, listOf(invalid), audio(2000, uid), env()))
    }
  }

  @Test fun legacyCategoryAloneOrUnknownCallTypeAreRejected() {
    assertFalse(AutoCallPolicy.isOngoingCall(call().copy(callType = 0)))
    assertFalse(AutoCallPolicy.isOngoingCall(call().copy(callType = 99, hasHangup = true)))
    assertFalse(AutoCallPolicy.isOngoingCall(call().copy(callType = 0, category = "msg", hasHangup = true)))
  }

  @Test fun legacyUsesHangupOrCountupChronometerWithoutAnswer() {
    assertTrue(AutoCallPolicy.isOngoingCall(call().copy(callType = 0, hasHangup = true)))
    assertTrue(AutoCallPolicy.isOngoingCall(call().copy(callType = 0, showChronometer = true)))
    assertFalse(AutoCallPolicy.isOngoingCall(call().copy(callType = 0, showChronometer = true, chronometerCountDown = true)))
    assertFalse(AutoCallPolicy.isOngoingCall(call().copy(callType = 0, hasHangup = true, hasAnswer = true)))
  }

  @Test fun distinctFreshAudioSamplesStartAfterStabilityWindow() {
    val policy = AutoCallPolicy()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(call()), audio(1000), env()))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1300, listOf(call()), audio(1300), env()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(1500, listOf(call()), audio(1500), env()))
  }

  @Test fun pollingDuplicateSnapshotNeverSuppliesSecondSample() {
    val policy = AutoCallPolicy()
    for (now in listOf(1000L, 1500L, 2000L, 3000L)) {
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(1000), env()))
    }
  }

  @Test fun wrongUidUnknownIdleStaleOrFutureAudioCannotStart() {
    val invalid = listOf(audio(1000, businessUid), audio(1000, known = false), audio(1000, mode = 0),
      audio(0), audio(4000))
    invalid.forEach { observation ->
      val policy = AutoCallPolicy()
      assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call()), observation, env()))
      assertEquals(AutoCallPolicy.Decision.None, policy.step(3500, listOf(call()), observation, env()))
    }
  }

  @Test fun incomingDoesNotBecomeRecordingJustBecauseAudioModeIsCommunication() {
    val policy = AutoCallPolicy()
    val incoming = call().copy(callType = 1, hasAnswer = true)
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(incoming), audio(1000), env()))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(2000, listOf(incoming), audio(2000), env()))
  }

  @Test fun acceptedStartDoesNotStartTwice() {
    val policy = started()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call(), call()), audio(3000), env(true, true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(4000, listOf(call()), audio(4000), env(true, true)))
  }

  @Test fun manualRecordingIsNeverStoppedOrOverridden() {
    val policy = AutoCallPolicy()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(call()), audio(1000), env(true, false)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, emptyList(), audio(3000, mode = 0),
      env(true, false).copy(listenerConnected = false)))
  }

  @Test fun helperListenerPermissionAndDisarmLossStopAutomaticImmediately() {
    listOf(env(true, true).copy(helperConnected = false), env(true, true).copy(listenerConnected = false),
      env(true, true).copy(notificationAccess = false), env(true, true).copy(armed = false)).forEach {
      assertEquals(AutoCallPolicy.Decision.Stop, started().step(2100, listOf(call()), audio(2000), it))
    }
  }

  @Test fun missingNotificationGetsBoundedGraceAndStops() {
    val policy = started()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, emptyList(), audio(3000), env(true, true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(4499, emptyList(), audio(4000), env(true, true)))
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(4500, emptyList(), audio(4500), env(true, true)))
  }

  @Test fun briefNotificationRemovalRecoversWithoutNewFile() {
    val policy = started()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, emptyList(), audio(3000), env(true, true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3500, listOf(call()), audio(3500), env(true, true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(5000, listOf(call()), audio(5000), env(true, true)))
  }

  @Test fun endingModeOrUnknownOwnerStopsAfterGrace() {
    for (observation in listOf(audio(3000, mode = 0), audio(3000, known = false))) {
      val policy = started()
      assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call()), observation, env(true, true)))
      assertEquals(AutoCallPolicy.Decision.Stop, policy.step(4500, listOf(call()), observation.copy(receivedAtMs = 4500), env(true, true)))
    }
  }

  @Test fun freshKnownDifferentOwnerStopsImmediatelyEvenIfNotWhatsApp() {
    for (uid in listOf(businessUid, 99999)) {
      assertEquals(AutoCallPolicy.Decision.Stop, started().step(3000, listOf(call()), audio(3000, uid), env(true, true)))
    }
  }

  @Test fun systemOwnerWithoutTelecomIsUnknownAndStopsAfterGrace() {
    val policy = started()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call()), audio(3000, 1000), env(true, true)))
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(4500, listOf(call()), audio(4500, 1000), env(true, true)))
  }

  @Test fun manualStopSuppressesSameCallEvenIfNotificationsDisappear() {
    val policy = started()
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    for (now in listOf(3000L, 4000L, 5000L)) {
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, if (now == 4000L) emptyList() else listOf(call()), audio(now), env()))
      assertTrue(policy.isSuppressed(waUid))
    }
  }

  @Test fun stableKnownIdleClearsManualSuppressionForNextCall() {
    val policy = started()
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    policy.step(3000, emptyList(), audio(3000, mode = 0), env())
    policy.step(4500, emptyList(), audio(4500, mode = 0), env())
    assertFalse(policy.isSuppressed(waUid))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(5000, listOf(call()), audio(5000), env()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(6000, listOf(call()), audio(6000), env()))
  }

  @Test fun staleUnknownSamplesDoNotClearManualSuppression() {
    val policy = started()
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    policy.step(3000, emptyList(), audio(3000, mode = 0, known = false), env())
    policy.step(9000, emptyList(), audio(3000, mode = 0), env())
    assertTrue(policy.isSuppressed(waUid))
  }

  @Test fun repeatedIdleSnapshotDoesNotProveStableIdle() {
    val policy = started()
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    policy.step(3000, emptyList(), audio(3000, mode = 0), env())
    policy.step(4500, emptyList(), audio(3000, mode = 0), env())
    assertTrue(policy.isSuppressed(waUid))
  }

  @Test fun uidSwitchDrainsFirstFileBeforeStartingBusinessCall() {
    val policy = started()
    val both = listOf(call(), call(businessUid, "com.whatsapp.w4b"))
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(3000, both, audio(3000, businessUid), env(true, true)))
    policy.onStopAccepted()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(5000, both, audio(5000, businessUid), env(true, false)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(6000, both, audio(6000, businessUid), env()))
    assertEquals(AutoCallPolicy.Decision.Start(businessUid), policy.step(7000, both, audio(7000, businessUid), env()))
  }

  @Test fun busyFinalizationPreventsPrematureStartAfterCompletionCallback() {
    val policy = started()
    policy.onCaptureCompleted()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call()), audio(3000), env(true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(4000, listOf(call()), audio(4000), env(true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(5000, listOf(call()), audio(5000), env()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(6000, listOf(call()), audio(6000), env()))
  }

  @Test fun completionFailureSuppressesAcceptedUidInsteadOfNewAudioOwner() {
    val policy = started()
    val both = listOf(call(), call(businessUid, "com.whatsapp.w4b"))
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(3000, both, audio(3000, businessUid), env(true, true)))
    policy.onStopAccepted()
    assertEquals(waUid, policy.acceptedEpisodeUid())
    policy.onCaptureCompleted(failed = true)
    assertTrue(policy.isSuppressed(waUid))
    assertFalse(policy.isSuppressed(businessUid))
    assertEquals(null, policy.acceptedEpisodeUid())
    assertEquals(AutoCallPolicy.Decision.None, policy.step(4000, both, audio(4000, businessUid), env()))
    assertEquals(AutoCallPolicy.Decision.Start(businessUid), policy.step(5000, both, audio(5000, businessUid), env()))
  }

  @Test fun acceptedUidSurvivesDisableResetUntilFailureCompletion() {
    val policy = started()
    policy.onStopAccepted()
    policy.reset()
    assertEquals(waUid, policy.acceptedEpisodeUid())
    policy.onCaptureCompleted(failed = true)
    assertTrue(policy.isSuppressed(waUid))
    assertFalse(policy.isSuppressed(businessUid))
    assertEquals(null, policy.acceptedEpisodeUid())
  }
}
