package com.didi4164.WhatsAppCallRecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCallTelecomPolicyTest {
  private val waUid = 10541
  private val businessUid = 10542
  private fun call(uid: Int = waUid, packageName: String = "com.whatsapp") =
    AutoCallPolicy.NotificationSignal(packageName, uid, "ram-only", "call", false, callType = 2)
  private fun proof(at: Long) = AutoCallPolicy.TelecomObservation(true, 1, 0, 1, 1, true, true, true, at)
  private fun audio(at: Long, telecom: AutoCallPolicy.TelecomObservation? = proof(at), mode: Int = 3, uid: Int = 1000) =
    AutoCallPolicy.AudioObservation(mode, uid, true, at, telecom)
  private fun started(): AutoCallPolicy = AutoCallPolicy().also {
    assertEquals(AutoCallPolicy.Decision.None, it.step(1000, listOf(call()), audio(1000), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), it.step(2000, listOf(call()), audio(2000), AutoCallPolicy.Environment()))
    it.onStartAccepted(waUid)
  }
  private fun stopped(): AutoCallPolicy = started().also {
    it.suppressCurrentCall(waUid)
    it.onStopAccepted()
    it.onCaptureCompleted()
  }

  @Test fun delegatedOwnerStartsOnlyItsExactNotificationUid() {
    val policy = AutoCallPolicy()
    val notifications = listOf(call(), call(businessUid, "com.whatsapp.w4b"))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, notifications, audio(1000), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(2000, notifications, audio(2000), AutoCallPolicy.Environment()))
    val resolution = policy.resolveAttribution(2000, notifications, audio(2000))
    assertEquals(waUid, resolution.startEligibleUid)
    assertEquals(waUid, resolution.knownCurrentOwnerUid)
    assertTrue(resolution.delegated)
    assertEquals(1000, audio(2000).ownerUid)
  }

  @Test fun businessAndSecondaryUserRequireMatchingPackageAndFullUid() {
    for ((code, pkg) in listOf(1 to "com.whatsapp", 2 to "com.whatsapp.w4b")) {
      val signal = call(110541, pkg)
      val policy = AutoCallPolicy()
      assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(signal), audio(1000, proof(1000).copy(packageCode = code, userId = 1)), AutoCallPolicy.Environment()))
      assertEquals(AutoCallPolicy.Decision.Start(signal.uid), policy.step(2000, listOf(signal), audio(2000, proof(2000).copy(packageCode = code, userId = 1)), AutoCallPolicy.Environment()))
    }
  }

  @Test fun systemUidWithoutProofAndOtherRawOwnersNeverUseDelegation() {
    for (ownerUid in listOf(1000, 1001, 2000, businessUid)) {
      val policy = AutoCallPolicy()
      for (now in listOf(1000L, 2000L)) {
        val observation = audio(now, if (ownerUid == 1000) null else proof(now), uid = ownerUid)
        assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), observation, AutoCallPolicy.Environment()))
      }
    }
  }

  @Test fun unknownHeldMultipleOtherPackageWrongUserOrFlagsNeverStart() {
    val invalid = listOf(
      proof(1000).copy(known = false), proof(1000).copy(packageCode = 0),
      proof(1000).copy(packageCode = 2), proof(1000).copy(packageCode = 3),
      proof(1000).copy(packageCode = 99), proof(1000).copy(userId = 1),
      proof(1000).copy(userId = -1), proof(1000).copy(liveCalls = 0, activeCalls = 0),
      proof(1000).copy(activeCalls = 0), proof(1000).copy(liveCalls = 2),
      proof(1000).copy(activeCalls = 2), proof(1000).copy(liveCalls = -1),
      proof(1000).copy(foregroundMatched = false), proof(1000).copy(selfManaged = false),
      proof(1000).copy(voip = false),
    )
    invalid.forEach { invalidProof ->
      val policy = AutoCallPolicy()
      for (now in listOf(1000L, 2000L)) {
        assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(now, invalidProof.copy(receivedAtMs = now)), AutoCallPolicy.Environment()))
      }
    }
  }

  @Test fun ringingIncomingScreeningAndAmbiguousUidNotificationsCannotAuthorizeDelegation() {
    val notifications = listOf(
      listOf(call().copy(callType = 1, hasAnswer = true)),
      listOf(call().copy(callType = 3)),
      listOf(call().copy(groupSummary = true)),
      listOf(call().copy(uid = 1000)),
      listOf(call(), call(10599)),
      emptyList(),
    )
    notifications.forEach { signals ->
      val policy = AutoCallPolicy()
      for (now in listOf(1000L, 2000L))
        assertEquals(AutoCallPolicy.Decision.None, policy.step(now, signals, audio(now), AutoCallPolicy.Environment()))
    }
  }

  @Test fun bothSourcesMustBeIndependentlyFreshKnownAndCommunicationMode() {
    val invalid = listOf(
      audio(4000, proof(1000)), audio(1000, proof(4000)),
      audio(4000, proof(5000)), audio(5000, proof(4000)),
      audio(4000, proof(-1)), audio(4000).copy(known = false),
      audio(4000, mode = 0), audio(4000, mode = 1), audio(4000, mode = 2),
    )
    invalid.forEach { observation ->
      val resolution = AutoCallPolicy().resolveAttribution(4000, listOf(call()), observation)
      assertEquals(null, resolution.startEligibleUid)
    }
  }

  @Test fun reusedTelecomOrAudioObservationCannotSupplySecondStartSample() {
    for (repeatAudio in listOf(false, true)) {
      val policy = AutoCallPolicy()
      for (now in listOf(1000L, 1500L, 2000L, 3000L)) {
        val observation = if (repeatAudio) audio(1000, proof(now)) else audio(now, proof(1000))
        assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), observation, AutoCallPolicy.Environment()))
      }
    }
  }

  @Test fun instabilityResetsDelegatedStartWindow() {
    val policy = AutoCallPolicy()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1000, listOf(call()), audio(1000), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(1500, listOf(call()), audio(1500, proof(1500).copy(activeCalls = 0)), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(2000, listOf(call()), audio(2000), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(2500, listOf(call()), audio(2500), AutoCallPolicy.Environment()))
  }

  @Test fun delegatedCaptureWithInvalidAttributionStopsAfterExistingGrace() {
    val invalidProofs = listOf(null, proof(3000).copy(known = false), proof(3000).copy(activeCalls = 0), proof(3000).copy(liveCalls = 2))
    invalidProofs.forEach { invalid ->
      val policy = started()
      val active = AutoCallPolicy.Environment(busy = true, automaticCapture = true)
      assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call()), audio(3000, invalid), active))
      assertEquals(AutoCallPolicy.Decision.Stop, policy.step(4500, listOf(call()), audio(4500, invalid?.copy(receivedAtMs = 4500)), active))
    }
  }

  @Test fun manualStopPersistsThroughNormalModeHeldUnknownMultipleAndNotificationFlicker() {
    val policy = stopped()
    for (now in listOf(3000L, 4500L, 6000L, 7500L)) {
      val held = audio(now, proof(now).copy(activeCalls = 0), mode = 0, uid = 0)
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, emptyList(), held, AutoCallPolicy.Environment()))
      assertTrue(policy.isSuppressed(waUid))
    }
    for (now in listOf(9000L, 10500L)) {
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(now, null, mode = 0, uid = 0), AutoCallPolicy.Environment()))
      assertTrue(policy.isSuppressed(waUid))
    }
    for (now in listOf(12000L, 13500L)) {
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(now, proof(now).copy(liveCalls = 2)), AutoCallPolicy.Environment()))
      assertTrue(policy.isSuppressed(waUid))
    }
    for (now in listOf(15000L, 16000L)) {
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(now), AutoCallPolicy.Environment()))
      assertTrue(policy.isSuppressed(waUid))
    }
  }

  @Test fun delegatedManualStopRequiresDistinctAudioAndKnownEmptyTelecomToClear() {
    val policy = stopped()
    val empty = proof(3000).copy(packageCode = 0, userId = -1, liveCalls = 0, activeCalls = 0, foregroundMatched = false, selfManaged = false, voip = false)
    policy.step(3000, emptyList(), audio(3000, empty, mode = 0, uid = 0), AutoCallPolicy.Environment())
    policy.step(4500, emptyList(), audio(4500, empty, mode = 0, uid = 0), AutoCallPolicy.Environment())
    assertTrue(policy.isSuppressed(waUid))
    policy.step(4500, emptyList(), audio(4500, empty.copy(receivedAtMs = 4500), mode = 0, uid = 0), AutoCallPolicy.Environment())
    assertFalse(policy.isSuppressed(waUid))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(5000, listOf(call()), audio(5000), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.Start(waUid), policy.step(6000, listOf(call()), audio(6000), AutoCallPolicy.Environment()))
  }

  @Test fun unknownOrStaleEmptyTelecomCannotClearDelegatedManualStop() {
    for (unknown in listOf(false, true)) {
      val policy = stopped()
      for (now in listOf(5000L, 6500L)) {
        val empty = proof(if (unknown) now else 1000).copy(known = !unknown, packageCode = 0, liveCalls = 0, activeCalls = 0)
        policy.step(now, emptyList(), audio(now, empty, mode = 0, uid = 0), AutoCallPolicy.Environment())
      }
      assertTrue(policy.isSuppressed(waUid))
    }
  }

  @Test fun delegatedCompletionFailureRetainsEpisodeKindAcrossReset() {
    val policy = started()
    policy.onStopAccepted()
    policy.reset()
    policy.onCaptureCompleted(failed = true)
    for (now in listOf(3000L, 4500L))
      policy.step(now, emptyList(), audio(now, null, mode = 0, uid = 0), AutoCallPolicy.Environment())
    assertTrue(policy.isSuppressed(waUid))
  }

  @Test fun aDirectEpisodeKeepsDelegationKnowledgeAfterItsOwnerBecomesSystemServer() {
    val policy = AutoCallPolicy()
    for (now in listOf(1000L, 2000L)) policy.step(now, listOf(call()), audio(now, null, uid = waUid), AutoCallPolicy.Environment())
    policy.onStartAccepted(waUid)
    assertEquals(AutoCallPolicy.Decision.None, policy.step(3000, listOf(call()), audio(3000), AutoCallPolicy.Environment(busy = true, automaticCapture = true)))
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    policy.onCaptureCompleted()
    for (now in listOf(4000L, 5500L))
      policy.step(now, emptyList(), audio(now, proof(now).copy(activeCalls = 0), mode = 0, uid = 0), AutoCallPolicy.Environment())
    assertTrue(policy.isSuppressed(waUid))
  }

  @Test fun aStoppedDirectEpisodeCannotLoseSuppressionIfTheContinuingCallBecomesDelegated() {
    val policy = AutoCallPolicy()
    for (now in listOf(1000L, 2000L)) policy.step(now, listOf(call()), audio(now, null, uid = waUid), AutoCallPolicy.Environment())
    policy.onStartAccepted(waUid)
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    policy.onCaptureCompleted()
    policy.step(3000, listOf(call()), audio(3000), AutoCallPolicy.Environment())
    for (now in listOf(4000L, 5500L))
      policy.step(now, emptyList(), audio(now, proof(now).copy(activeCalls = 0), mode = 0, uid = 0), AutoCallPolicy.Environment())
    assertTrue(policy.isSuppressed(waUid))
  }

  @Test fun explicitManualStopCanRetainDelegationObservedBeforeTheNextPolicyTick() {
    val policy = AutoCallPolicy()
    for (now in listOf(1000L, 2000L)) policy.step(now, listOf(call()), audio(now, null, uid = waUid), AutoCallPolicy.Environment())
    policy.onStartAccepted(waUid)
    policy.suppressCurrentCall(waUid, delegated = true)
    policy.onStopAccepted()
    policy.onCaptureCompleted()
    for (now in listOf(3000L, 4500L))
      policy.step(now, emptyList(), audio(now, proof(now).copy(activeCalls = 0), mode = 0, uid = 0), AutoCallPolicy.Environment())
    assertTrue(policy.isSuppressed(waUid))
  }

  @Test fun stoppedDirectCallCannotRestartAfterUnknownSystemOwnerThenHold() {
    val policy = AutoCallPolicy()
    for (now in listOf(1000L, 2000L)) policy.step(now, listOf(call()), audio(now, null, uid = waUid), AutoCallPolicy.Environment())
    policy.onStartAccepted(waUid)
    policy.suppressCurrentCall(waUid)
    policy.onStopAccepted()
    policy.onCaptureCompleted()
    policy.step(3000, emptyList(), audio(3000, null), AutoCallPolicy.Environment())
    for (now in listOf(4000L, 5500L))
      policy.step(now, emptyList(), audio(now, proof(now).copy(activeCalls = 0), mode = 0, uid = 0), AutoCallPolicy.Environment())
    for (now in listOf(6000L, 7000L)) {
      assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(now), AutoCallPolicy.Environment()))
      assertTrue(policy.isSuppressed(waUid))
    }
  }

  @Test fun holdAsFirstTelecomObservationCannotClearStoppedDirectCall() {
    for (unknown in listOf(false, true)) {
      val policy = AutoCallPolicy()
      for (now in listOf(1000L, 2000L)) policy.step(now, listOf(call()), audio(now, null, uid = waUid), AutoCallPolicy.Environment())
      policy.onStartAccepted(waUid)
      policy.suppressCurrentCall(waUid)
      policy.onStopAccepted()
      policy.onCaptureCompleted()
      for (now in listOf(3000L, 4500L)) {
        val held = proof(now).copy(known = !unknown, activeCalls = 0)
        policy.step(now, emptyList(), audio(now, held, mode = 0, uid = 0), AutoCallPolicy.Environment())
      }
      for (now in listOf(5000L, 6000L)) {
        assertEquals(AutoCallPolicy.Decision.None, policy.step(now, listOf(call()), audio(now), AutoCallPolicy.Environment()))
        assertTrue(policy.isSuppressed(waUid))
      }
      for (now in listOf(7000L, 8500L)) {
        val empty = proof(now).copy(packageCode = 0, userId = -1, liveCalls = 0, activeCalls = 0,
          foregroundMatched = false, selfManaged = false, voip = false)
        policy.step(now, emptyList(), audio(now, empty, mode = 0, uid = 0), AutoCallPolicy.Environment())
      }
      assertFalse(policy.isSuppressed(waUid))
    }
  }

  @Test fun delegatedUidSwitchStopsFirstFileBeforeBusyFinalizationAndBusinessStart() {
    val policy = started()
    val signals = listOf(call(), call(businessUid, "com.whatsapp.w4b"))
    val businessProof = proof(3000).copy(packageCode = 2)
    assertEquals(AutoCallPolicy.Decision.Stop, policy.step(3000, signals, audio(3000, businessProof), AutoCallPolicy.Environment(busy = true, automaticCapture = true)))
    policy.onStopAccepted()
    policy.onCaptureCompleted()
    assertEquals(AutoCallPolicy.Decision.None, policy.step(4000, signals, audio(4000, businessProof.copy(receivedAtMs = 4000)), AutoCallPolicy.Environment(busy = true)))
    assertEquals(AutoCallPolicy.Decision.None, policy.step(5000, signals, audio(5000, businessProof.copy(receivedAtMs = 5000)), AutoCallPolicy.Environment()))
    assertEquals(AutoCallPolicy.Decision.Start(businessUid), policy.step(6000, signals, audio(6000, businessProof.copy(receivedAtMs = 6000)), AutoCallPolicy.Environment()))
  }
}
