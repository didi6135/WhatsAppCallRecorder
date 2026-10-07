package com.didi4164.WhatsAppCallRecorder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WirelessActivationFailureTest {
  @Test public void everyCurrentStageHasDistinctFixedBilingualGuidance() {
    Set<String> codes = new HashSet<>(), messages = new HashSet<>();
    for (String stage : new String[] {"DISCOVERY", "ADB_CONNECTION", "BOOTSTRAP", "AUTHENTICATION",
        "HANDOFF", "DETACH", "LIVENESS", "READINESS_OWNER"}) {
      WirelessActivationFailure failure = WirelessActivationFailure.forStage(stage);
      assertNotNull(stage, failure);
      assertEquals("ACTIVATION_" + stage + "_FAILED", failure.errorCode);
      assertTrue(codes.add(failure.errorCode)); assertTrue(messages.add(failure.englishMessage));
      assertTrue(failure.hebrewMessage.matches(".*[\\u0590-\\u05ff].*"));
      assertFalse(failure.englishMessage.matches(".*[\\u0590-\\u05ff].*"));
      assertFalse(failure.englishMessage.trim().isEmpty());
    }
  }
  @Test public void unknownOrContaminatedStagesCannotCreateDiagnosticOutput() {
    for (String stage : new String[] {null, "", "discovery", "FUTURE", "toString", "__proto__",
        "DISCOVERY\nAUTHENTICATION", "AUTHENTICATION port=12345 code=123456", "BOOTSTRAP /private/key"})
      assertNull(WirelessActivationFailure.forStage(stage));
  }
  @Test public void readinessFailureDoesNotInventPermissionDenialOrNotificationOutcome() {
    String authentication = WirelessActivationFailure.forStage("AUTHENTICATION").englishMessage;
    assertTrue(authentication.contains("did not confirm readiness"));
    assertFalse(authentication.toLowerCase().contains("denied"));
    assertFalse(authentication.toLowerCase().contains("blocked"));
    String readiness = WirelessActivationFailure.forStage("READINESS_OWNER").englishMessage;
    assertTrue(readiness.contains("could not stay active"));
    assertFalse(readiness.contains("notification did not start"));
  }
  @Test public void exposedDiagnosticsContainOnlyImmutableStrings() {
    for (Field field : WirelessActivationFailure.class.getDeclaredFields()) {
      assertEquals(String.class, field.getType());
      assertTrue(Modifier.isFinal(field.getModifiers()));
      assertTrue(Modifier.isPublic(field.getModifiers()));
    }
  }
}
