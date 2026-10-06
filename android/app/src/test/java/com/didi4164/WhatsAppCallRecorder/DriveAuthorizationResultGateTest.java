package com.didi4164.WhatsAppCallRecorder;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Offline application-boundary tests; the injected decoder does not impersonate Google's SDK. */
public class DriveAuthorizationResultGateTest {
  private static final class AuthFailure extends Exception {
    final int status;
    AuthFailure(int status) { super("synthetic provider text must not cross the bridge"); this.status = status; }
  }
  private final DriveAuthorizationResultGate.StatusReader statuses = error -> error instanceof AuthFailure ? ((AuthFailure) error).status : null;

  @Test public void errorBearingCanceledIntentIsDecodedAndFails() {
    AtomicInteger calls = new AtomicInteger();
    DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(0, true, () -> {
      calls.incrementAndGet(); throw new AuthFailure(10);
    }, statuses);
    assertEquals(1, calls.get());
    assertEquals(DriveAuthorizationResultGate.Decision.FAIL, result.decision);
    assertEquals(Integer.valueOf(10), result.authStatusCode);
    assertNull(result.value);
  }
  @Test public void successfulDecodeCannotAcceptCanceledActivity() {
    AtomicInteger calls = new AtomicInteger();
    DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(0, true, () -> {
      calls.incrementAndGet(); return "synthetic-token-bearing-result";
    }, statuses);
    assertEquals(1, calls.get());
    assertEquals(DriveAuthorizationResultGate.Decision.CANCEL, result.decision);
    assertNull(result.value);
  }
  @Test public void sdkCanceledStatusResolvesAsCancellation() {
    for (int resultCode : new int[]{-1, 0}) {
      DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(resultCode, true, () -> { throw new AuthFailure(16); }, statuses);
      assertEquals(DriveAuthorizationResultGate.Decision.CANCEL, result.decision);
      assertNull(result.value); assertNull(result.failure);
    }
  }
  @Test public void nullCanceledDataDoesNotInvokeDecoder() {
    DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(0, false, () -> { fail("Decoder must not run"); return "bad"; }, statuses);
    assertEquals(DriveAuthorizationResultGate.Decision.CANCEL, result.decision);
  }
  @Test public void nullSuccessfulDataFailsRatherThanSilentlyCanceling() {
    DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(-1, false, () -> { fail("Decoder must not run"); return "bad"; }, statuses);
    assertEquals(DriveAuthorizationResultGate.Decision.FAIL, result.decision);
    assertNotNull(result.failure); assertNull(result.value);
  }
  @Test public void validOkResultIsAcceptedOnce() {
    Object expected = new Object(); AtomicInteger calls = new AtomicInteger();
    DriveAuthorizationResultGate.Outcome<Object> result = DriveAuthorizationResultGate.evaluate(-1, true, () -> { calls.incrementAndGet(); return expected; }, statuses);
    assertEquals(1, calls.get()); assertEquals(DriveAuthorizationResultGate.Decision.ACCEPT, result.decision); assertSame(expected, result.value);
  }
  @Test public void unknownActivityCodeCannotAuthorize() {
    DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(27, true, () -> "result", statuses);
    assertEquals(DriveAuthorizationResultGate.Decision.FAIL, result.decision); assertNull(result.value);
  }
  @Test public void nullDecodedResultCannotAuthorize() {
    assertEquals(DriveAuthorizationResultGate.Decision.FAIL, DriveAuthorizationResultGate.evaluate(-1, true, () -> null, statuses).decision);
  }
  @Test public void networkErrorAndUnknownFailureAreRetainedForSanitizedMapping() {
    DriveAuthorizationResultGate.Outcome<String> network = DriveAuthorizationResultGate.evaluate(-1, true, () -> { throw new AuthFailure(7); }, statuses);
    assertEquals(Integer.valueOf(7), network.authStatusCode); assertEquals(DriveAuthorizationResultGate.Decision.FAIL, network.decision);
    DriveAuthorizationResultGate.Outcome<String> unknown = DriveAuthorizationResultGate.evaluate(-1, true, () -> { throw new Exception("synthetic private body"); }, statuses);
    assertEquals(DriveAuthorizationResultGate.Decision.FAIL, unknown.decision); assertNull(unknown.authStatusCode);
  }
  @Test public void unexpectedGoogleStatusIsNotExported() {
    for (int status : new int[]{-1, 65536, Integer.MAX_VALUE}) {
      DriveAuthorizationResultGate.Outcome<String> result = DriveAuthorizationResultGate.evaluate(-1, true, () -> { throw new AuthFailure(status); }, statuses);
      assertEquals(DriveAuthorizationResultGate.Decision.FAIL, result.decision); assertNull(result.authStatusCode);
    }
  }
}
