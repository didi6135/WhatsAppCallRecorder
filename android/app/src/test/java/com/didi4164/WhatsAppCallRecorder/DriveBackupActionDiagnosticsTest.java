package com.didi4164.WhatsAppCallRecorder;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Process-local diagnostics are tested independently of Android Activities and the durable queue. */
public class DriveBackupActionDiagnosticsTest {
  private final DriveBackupActionDiagnostics diagnostics = new DriveBackupActionDiagnostics();
  @Test public void failureSurvivesRepeatedSnapshotsAndConsumerRecreation() {
    DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "connect");
    assertTrue(diagnostics.fail(ticket, 3, "authorize", "CONFIGURATION_REQUIRED", 10, null));
    Map<String, Object> first = diagnostics.snapshot(3);
    Map<String, Object> remountedConsumer = diagnostics.snapshot(3);
    assertEquals(first, remountedConsumer); assertNotSame(first, remountedConsumer);
    assertEquals("CONFIGURATION_REQUIRED", remountedConsumer.get("code"));
  }
  @Test public void newActionIdentityRejectsObsoleteFailureAndCancellation() {
    DriveBackupActionDiagnostics.Ticket old = diagnostics.begin(3, "connect");
    DriveBackupActionDiagnostics.Ticket current = diagnostics.begin(3, "folder");
    assertTrue(diagnostics.fail(current, 3, "selection", "FOLDER_UNAVAILABLE", null, -1));
    assertFalse(diagnostics.fail(old, 3, "authorize", "NETWORK", 7, null));
    assertFalse(diagnostics.clear(old, 3));
    assertEquals("FOLDER_UNAVAILABLE", diagnostics.snapshot(3).get("code"));
  }
  @Test public void generationChangeClearsDiagnosticAndRejectsOldCallback() {
    DriveBackupActionDiagnostics.Ticket old = diagnostics.begin(3, "connect");
    diagnostics.fail(old, 3, "authorize", "CONFIGURATION_REQUIRED", 10, null);
    assertNull(diagnostics.snapshot(4));
    assertFalse(diagnostics.fail(old, 4, "selection", "NETWORK", 7, null));
    assertNull(diagnostics.snapshot(4));
  }
  @Test public void successOrCancelClearsOnlyCurrentAction() {
    DriveBackupActionDiagnostics.Ticket failed = diagnostics.begin(3, "connect");
    diagnostics.fail(failed, 3, "authorize", "AUTH_REQUIRED", null, null);
    DriveBackupActionDiagnostics.Ticket retry = diagnostics.begin(3, "connect");
    assertTrue(diagnostics.clear(retry, 3)); assertNull(diagnostics.snapshot(3));
  }
  @Test public void queueStatusAndReceiptsAreNotModifiedOrMarkedFailed() {
    Map<String, Object> queue = new LinkedHashMap<>();
    Object receipts = new Object();
    queue.put("enabled", true); queue.put("phase", "uploading"); queue.put("errorCode", null);
    queue.put("queuedCount", 2); queue.put("uploadedCount", 4); queue.put("receipts", receipts);
    Map<String, Object> before = new LinkedHashMap<>(queue);
    DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "folder");
    diagnostics.fail(ticket, 3, "pickerResult", "CONFIGURATION_REQUIRED", 10, 0);
    Map<String, Object> decorated = diagnostics.decorate(queue, 3);
    assertEquals(before, queue); assertFalse(queue.containsKey("lastActionError"));
    for (String key : before.keySet()) assertSame(before.get(key), decorated.get(key));
    assertNotNull(decorated.get("lastActionError"));
  }
  @Test public void bridgeRecordContainsOnlyWhitelistedMetadata() {
    DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "connect");
    diagnostics.fail(ticket, 3, "authorize", "synthetic token/raw URL", 10, -1);
    Map<String, Object> record = diagnostics.snapshot(3);
    assertEquals(new HashSet<>(Arrays.asList("action", "stage", "code", "authStatusCode", "activityResultCode")), record.keySet());
    assertEquals("UPLOAD_FAILED", record.get("code")); assertFalse(record.toString().contains("synthetic"));
  }
  @Test public void numericBoundsAreExactAndUnsafeValuesAreOmitted() {
    for (int auth : new int[]{0, 65535}) {
      DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "connect");
      diagnostics.fail(ticket, 3, "pickerResult", "AUTH_REQUIRED", auth, -65535);
      assertEquals(auth, diagnostics.snapshot(3).get("authStatusCode"));
    }
    DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "folder");
    diagnostics.fail(ticket, 3, "selection", "AUTH_REQUIRED", -1, 65536);
    assertFalse(diagnostics.snapshot(3).containsKey("authStatusCode")); assertFalse(diagnostics.snapshot(3).containsKey("activityResultCode"));
    ticket = diagnostics.begin(3, "folder"); diagnostics.fail(ticket, 3, "selection", "AUTH_REQUIRED", 65536, 65535);
    assertFalse(diagnostics.snapshot(3).containsKey("authStatusCode")); assertEquals(65535, diagnostics.snapshot(3).get("activityResultCode"));
  }
  @Test public void invalidActionAndStageAreRejected() {
    try { diagnostics.begin(3, "raw-provider-action"); fail("Invalid action"); } catch (IllegalArgumentException expected) { }
    DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "connect");
    try { diagnostics.fail(ticket, 3, "raw-provider-stage", "AUTH_REQUIRED", null, null); fail("Invalid stage"); } catch (IllegalArgumentException expected) { }
    assertNull(diagnostics.snapshot(3));
  }
  @Test public void snapshotCannotMutateNativeDiagnostic() {
    DriveBackupActionDiagnostics.Ticket ticket = diagnostics.begin(3, "connect");
    diagnostics.fail(ticket, 3, "authorize", "AUTH_REQUIRED", null, null);
    Map<String, Object> first = diagnostics.snapshot(3);
    try { first.put("code", "changed"); } catch (UnsupportedOperationException expected) { }
    assertEquals("AUTH_REQUIRED", diagnostics.snapshot(3).get("code"));
  }
  @Test public void emptyDiagnosticsRemainOptionalAndDoNotChangeWorkerErrors() {
    Map<String, Object> queue = new HashMap<>(); queue.put("phase", "needsConsent"); queue.put("errorCode", "AUTH_REQUIRED");
    Map<String, Object> decorated = diagnostics.decorate(queue, 3);
    assertNull(decorated.get("lastActionError")); assertEquals("needsConsent", decorated.get("phase")); assertEquals("AUTH_REQUIRED", decorated.get("errorCode"));
  }
}
