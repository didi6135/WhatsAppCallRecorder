package com.codaki.usbaudio;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ownership transfer, startup failure and session-separation cases without Android/device access. */
public final class BootstrapHandoffTest {
  private static int tests;
  public static void main(String[] args) throws Exception {
    onlyExplicitAckIsAccepted();
    detachedOwnershipSurvivesParentDeadline();
    timeoutCannotLaterHandOff();
    cleanupCancellationIsIdempotent();
    duplicateHandoffIsRejected();
    completeAndDeadlineAreAtomic();
    independentProcessSessionIsAccepted();
    originalParentGroupIsRejected();
    originalParentSessionIsRejected();
    incorrectPidIsRejected();
    processNameParenthesesAreParsed();
    malformedMetadataIsRejected();
    System.out.println("BootstrapHandoff: " + tests + " cases passed");
  }

  private static void onlyExplicitAckIsAccepted() throws Exception {
    BootstrapHandoff.requireAck(1);
    for (int value : new int[]{-1, 0, 2, 255}) {
      try { BootstrapHandoff.requireAck(value); throw new AssertionError("invalid ACK accepted"); }
      catch (IOException expected) { }
    }
    tests++;
  }

  private static void detachedOwnershipSurvivesParentDeadline() {
    BootstrapHandoff parent = new BootstrapHandoff(), child = new BootstrapHandoff();
    child.complete(); parent.complete();
    check(!parent.cancelIfPending(), "parent EOF/deadline must not revoke handed-off child");
    check(!child.cancelIfPending(), "old startup deadline must not revoke active child");
    check(parent.isComplete() && child.isComplete(), "both transfer states retained"); tests++;
  }

  private static void timeoutCannotLaterHandOff() {
    BootstrapHandoff handoff = new BootstrapHandoff(); check(handoff.cancelIfPending(), "pending startup expires");
    try { handoff.complete(); throw new AssertionError("timed-out startup handed off"); }
    catch (IllegalStateException expected) { }
    check(!handoff.isComplete(), "timeout still requires child cleanup"); tests++;
  }

  private static void cleanupCancellationIsIdempotent() {
    BootstrapHandoff handoff = new BootstrapHandoff();
    check(handoff.cancelIfPending(), "first cancellation owns cleanup");
    check(!handoff.cancelIfPending(), "second cancellation must not duplicate cleanup"); tests++;
  }

  private static void duplicateHandoffIsRejected() {
    BootstrapHandoff handoff = new BootstrapHandoff(); handoff.complete();
    try { handoff.complete(); throw new AssertionError("duplicate handoff accepted"); }
    catch (IllegalStateException expected) { }
    tests++;
  }

  private static void completeAndDeadlineAreAtomic() throws Exception {
    for (int iteration = 0; iteration < 200; iteration++) {
      BootstrapHandoff handoff = new BootstrapHandoff(); CountDownLatch start = new CountDownLatch(1);
      AtomicBoolean completed = new AtomicBoolean(), cancelled = new AtomicBoolean();
      Thread ready = new Thread(() -> {
        await(start); try { handoff.complete(); completed.set(true); } catch (IllegalStateException expired) { }
      });
      Thread deadline = new Thread(() -> { await(start); cancelled.set(handoff.cancelIfPending()); });
      ready.start(); deadline.start(); start.countDown(); ready.join(); deadline.join();
      check(completed.get() != cancelled.get(), "exactly one of handoff/deadline must win");
      check(handoff.isComplete() == completed.get(), "final transfer state matches winner");
    }
    tests++;
  }

  private static void independentProcessSessionIsAccepted() {
    BootstrapHandoff.requireIndependentSession("1234 (app_process) S 888 1234 1234 0 0 0", 1234); tests++;
  }
  private static void originalParentGroupIsRejected() {
    reject("1234 (app_process) S 888 888 1234 0 0 0", 1234); tests++;
  }
  private static void originalParentSessionIsRejected() {
    reject("1234 (app_process) S 888 1234 888 0 0 0", 1234); tests++;
  }
  private static void incorrectPidIsRejected() {
    reject("1234 (app_process) S 888 1234 1234 0 0 0", 5678); tests++;
  }
  private static void processNameParenthesesAreParsed() {
    BootstrapHandoff.requireIndependentSession("1234 (app (process) name) S 888 1234 1234 0 0 0", 1234); tests++;
  }
  private static void malformedMetadataIsRejected() {
    for (String value : new String[]{null, "", "abc", "1234 (app) S", "1234 (app) S 888 bad 1234", "1234 app S 888 1234 1234"}) reject(value, 1234);
    char[] oversized = new char[4097]; reject(new String(oversized), 1234); tests++;
  }
  private static void reject(String stat, int pid) {
    try { BootstrapHandoff.requireIndependentSession(stat, pid); throw new AssertionError("invalid session accepted"); }
    catch (SecurityException expected) { }
  }
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException failed) { throw new AssertionError(failed); } }
}
