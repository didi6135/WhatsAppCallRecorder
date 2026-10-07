package com.didi4164.WhatsAppCallRecorder;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveGoogleStatusTest {
  @Test public void actualPhoneInternalErrorDoesNotRequestReauthorization() {
    assertEquals("GOOGLE_INTERNAL_ERROR", DriveGoogleStatus.code(8));
    assertTrue(DriveGoogleStatus.retryable(8));
    assertFalse(DriveGoogleStatus.needsConsent(8));
    DriveBackupActionDiagnostics state = new DriveBackupActionDiagnostics();
    DriveBackupActionDiagnostics.Ticket action = state.begin(1, "connect");
    assertTrue(state.fail(action, 1, "pickerResult", DriveGoogleStatus.code(8), 8, 0));
    assertEquals("GOOGLE_INTERNAL_ERROR", state.snapshot(1).get("code"));
    assertEquals(8, state.snapshot(1).get("authStatusCode"));
  }
  @Test public void knownNetworkConfigurationAndConsentCasesRemainDistinct() {
    assertEquals("NETWORK", DriveGoogleStatus.code(7)); assertTrue(DriveGoogleStatus.retryable(7));
    assertFalse(DriveGoogleStatus.needsConsent(7));
    assertEquals("CONFIGURATION_REQUIRED", DriveGoogleStatus.code(10));
    assertFalse(DriveGoogleStatus.retryable(10)); assertFalse(DriveGoogleStatus.needsConsent(10));
    for (Integer code : new Integer[]{4,5,6,null}) {
      assertEquals("AUTH_REQUIRED", DriveGoogleStatus.code(code));
      assertTrue(DriveGoogleStatus.needsConsent(code)); assertFalse(DriveGoogleStatus.retryable(code));
    }
  }
}
