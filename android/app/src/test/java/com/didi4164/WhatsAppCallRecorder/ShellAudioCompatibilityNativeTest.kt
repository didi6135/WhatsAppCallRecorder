package com.didi4164.WhatsAppCallRecorder

import com.codaki.usbaudio.ShellAudioCompatibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native pairing and activation use the same policy as the real shell helper. */
class ShellAudioCompatibilityNativeTest {
  @Test fun rejectsAndroid13AndEarlier() {
    listOf(24, 30, 31, 32, 33).forEach {
      assertFalse(ShellAudioCompatibility.isCandidateSdk(it))
      assertFalse(ShellAudioCompatibility.isExperimentalSdk(it))
    }
  }
  @Test fun android14And15AreExperimentalCandidates() {
    listOf(34, 35).forEach {
      assertTrue(ShellAudioCompatibility.isCandidateSdk(it))
      assertTrue(ShellAudioCompatibility.isExperimentalSdk(it))
    }
  }
  @Test fun android16RetainsItsExistingRouteAndFutureVersionsRemainExperimental() {
    assertTrue(ShellAudioCompatibility.isCandidateSdk(36))
    assertFalse(ShellAudioCompatibility.isExperimentalSdk(36))
    assertTrue(ShellAudioCompatibility.isCandidateSdk(37))
    assertTrue(ShellAudioCompatibility.isExperimentalSdk(37))
  }
}
