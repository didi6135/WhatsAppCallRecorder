package com.didi4164.WhatsAppCallRecorder;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DriveConnectionAttemptTest {
  @Test public void invalidatedAndPreviousActivityResultsCannotMatchNewAttempt() {
    DriveConnectionAttempt first = new DriveConnectionAttempt();
    DriveConnectionAttempt next = new DriveConnectionAttempt();
    assertTrue(first.matches(first.requestCode));
    assertFalse(next.matches(first.requestCode));
    first.cancel();
    assertFalse(first.matches(first.requestCode));
    assertTrue(next.matches(next.requestCode));
  }
  @Test public void canceledAttemptCannotReserveOrCommitLocalDestination() {
    DriveConnectionAttempt action = new DriveConnectionAttempt();
    action.cancel();
    try { action.mutate(() -> { fail("Canceled reservation ran"); return null; }); fail("Expected rejection"); }
    catch (IllegalStateException expected) { assertEquals("FOREGROUND_REQUIRED", expected.getMessage()); }
    try { action.complete(() -> fail("Canceled commit ran")); fail("Expected rejection"); }
    catch (IllegalStateException expected) { assertEquals("FOREGROUND_REQUIRED", expected.getMessage()); }
  }
  @Test public void completedAttemptCannotConsumeDuplicateCallback() {
    DriveConnectionAttempt action = new DriveConnectionAttempt();
    int[] commits = {0};
    action.complete(() -> commits[0]++);
    assertEquals(1, commits[0]);
    assertFalse(action.matches(action.requestCode));
    try { action.complete(() -> commits[0]++); fail("Expected rejection"); }
    catch (IllegalStateException expected) { assertEquals(1, commits[0]); }
  }
  @Test public void failedCommitDoesNotPretendDestinationWasSelected() {
    DriveConnectionAttempt action = new DriveConnectionAttempt();
    try { action.complete(() -> { throw new IllegalStateException("LOCAL_QUEUE_UNAVAILABLE"); }); fail("Expected rejection"); }
    catch (IllegalStateException expected) { assertTrue(action.isActive()); }
    action.cancel();
  }
}
