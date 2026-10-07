package com.didi4164.WhatsAppCallRecorder;

import java.util.PriorityQueue;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ForegroundMutationGateTest {
  private static final class Task implements Comparable<Task> {
    final long at; final Runnable action;
    Task(long at, Runnable action) { this.at = at; this.action = action; }
    public int compareTo(Task other) { return Long.compare(at, other.at); }
  }
  private static final class Clock implements ForegroundMutationGate.Scheduler {
    long now; final PriorityQueue<Task> tasks = new PriorityQueue<>();
    public long now() { return now; }
    public void post(Runnable task, long delay) { tasks.add(new Task(now + delay, task)); }
    void until(long end) { while (!tasks.isEmpty() && tasks.peek().at <= end) { Task task = tasks.remove(); now = task.at; task.action.run(); } now = end; }
  }
  private static final class Fixture {
    final Clock clock = new Clock(); boolean valid = true, resumed = true, focused, generation = true, idle = true, recordingIdle = true;
    int commits, failures; String code; boolean readerFails;
    final ForegroundMutationGate gate = new ForegroundMutationGate(clock, this::state, () -> commits++, failure -> { failures++; code = failure; });
    ForegroundMutationGate.State state() {
      if (readerFails) throw new IllegalStateException("synthetic unavailable journal");
      return new ForegroundMutationGate.State(valid, resumed, focused, generation, idle, recordingIdle);
    }
    void start() { gate.start(); clock.until(0); }
  }
  @Test public void positiveDialogCallbackWaitsForFocusAndDispatchesExactlyOnce() {
    Fixture f = new Fixture(); f.start(); f.clock.until(64); assertEquals(0, f.commits);
    f.focused = true; f.clock.until(96); assertEquals(1, f.commits); assertEquals(0, f.failures);
    f.clock.until(10000); f.gate.run(); assertEquals(1, f.commits);
  }
  @Test public void focusNeverReturnsTimesOutWithoutMutation() {
    Fixture f = new Fixture(); f.start(); f.clock.until(1999); assertEquals(0, f.failures);
    f.clock.until(2000); assertEquals("FOREGROUND_REQUIRED", f.code); assertEquals(0, f.commits);
    f.focused = true; f.clock.until(5000); assertEquals(0, f.commits); assertEquals(1, f.failures);
  }
  @Test public void realBackgroundIsRejectedInsteadOfWaitingForResume() {
    Fixture f = new Fixture(); f.start(); f.resumed = false; f.clock.until(32);
    assertEquals("FOREGROUND_REQUIRED", f.code); f.resumed = true; f.focused = true; f.clock.until(100);
    assertEquals(0, f.commits);
  }
  @Test public void differentDestroyedOrInvalidatedActivityIsRejected() {
    Fixture f = new Fixture(); f.valid = false; f.focused = true; f.start();
    assertEquals("FOREGROUND_REQUIRED", f.code); assertEquals(0, f.commits);
  }
  @Test public void changedDestinationCannotReceiveOldDialogApproval() {
    Fixture f = new Fixture(); f.start(); f.generation = false; f.focused = true; f.clock.until(32);
    assertEquals("ACCOUNT_CHANGED", f.code); assertEquals(0, f.commits);
  }
  @Test public void competingConnectionAndCaptureBlockTheAuthorizedMutation() {
    Fixture connection = new Fixture(); connection.start(); connection.idle = false; connection.focused = true; connection.clock.until(32);
    assertEquals("CONNECTION_BUSY", connection.code); assertEquals(0, connection.commits);
    Fixture capture = new Fixture(); capture.start(); capture.recordingIdle = false; capture.focused = true; capture.clock.until(32);
    assertEquals("RECORDING_BUSY", capture.code); assertEquals(0, capture.commits);
  }
  @Test public void cancellationPreventsQueuedPollFromExecutingMutation() {
    Fixture f = new Fixture(); f.start(); f.gate.cancel(); f.focused = true; f.clock.until(5000);
    assertEquals("FOREGROUND_REQUIRED", f.code); assertEquals(0, f.commits); assertEquals(1, f.failures);
  }
  @Test public void queuedIoMustRecheckFocusLifecycleGenerationAndCancelBeforeCommit() {
    Fixture f = new Fixture(); f.focused = true; f.start(); assertEquals(1, f.commits); assertNull(f.gate.rejectionNow());
    f.generation = false; assertEquals("ACCOUNT_CHANGED", f.gate.rejectionNow()); f.generation = true;
    f.focused = false; assertEquals("FOREGROUND_REQUIRED", f.gate.rejectionNow()); f.focused = true;
    f.resumed = false; assertEquals("FOREGROUND_REQUIRED", f.gate.rejectionNow()); f.resumed = true;
    f.gate.cancel(); assertEquals("FOREGROUND_REQUIRED", f.gate.rejectionNow()); assertEquals(0, f.failures);
  }
  @Test public void queuedIoCannotBeginAfterTheFocusWaitDeadline() {
    Fixture f = new Fixture(); f.focused = true; f.start(); assertNull(f.gate.beginExecution());
    f.clock.until(2000); assertEquals("FOREGROUND_REQUIRED", f.gate.beginExecution());
    // An operation already begun still uses live commit guards after network work, without a second timeout.
    assertNull(f.gate.rejectionNow()); assertEquals(1, f.commits); assertEquals(0, f.failures);
  }
  @Test public void readerFailureSettlesWaitOnceAndCommitRecheckDoesNotSettleTwice() {
    Fixture waiting = new Fixture(); waiting.readerFails = true; waiting.start(); waiting.clock.until(5000);
    assertEquals("LOCAL_QUEUE_UNAVAILABLE", waiting.code); assertEquals(1, waiting.failures); assertEquals(0, waiting.commits);
    Fixture dispatched = new Fixture(); dispatched.focused = true; dispatched.start(); dispatched.readerFails = true;
    assertEquals("LOCAL_QUEUE_UNAVAILABLE", dispatched.gate.rejectionNow()); assertEquals(0, dispatched.failures);
  }
}
