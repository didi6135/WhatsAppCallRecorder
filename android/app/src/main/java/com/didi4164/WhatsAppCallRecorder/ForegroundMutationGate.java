package com.didi4164.WhatsAppCallRecorder;

/** Bounded focus restoration for an already-authorized dialog callback; never treats background as readiness. */
public final class ForegroundMutationGate implements Runnable {
  public static final long TIMEOUT_MS = 2000, POLL_MS = 32;
  public interface Scheduler { long now(); void post(Runnable task, long delayMs); }
  public interface Reader { State read(); }
  public interface Failure { void reject(String code); }
  public static final class State {
    public final boolean validActivity, resumed, focused, sameGeneration, connectionIdle, recordingIdle;
    public State(boolean validActivity, boolean resumed, boolean focused, boolean sameGeneration,
        boolean connectionIdle, boolean recordingIdle) {
      this.validActivity = validActivity; this.resumed = resumed; this.focused = focused;
      this.sameGeneration = sameGeneration; this.connectionIdle = connectionIdle; this.recordingIdle = recordingIdle;
    }
  }
  public static String rejection(State state) {
    if (!state.validActivity || !state.resumed) return "FOREGROUND_REQUIRED";
    if (!state.connectionIdle) return "CONNECTION_BUSY";
    if (!state.sameGeneration) return "ACCOUNT_CHANGED";
    if (!state.recordingIdle) return "RECORDING_BUSY";
    return null;
  }
  private final Scheduler scheduler;
  private final Reader reader;
  private final Runnable ready;
  private final Failure failure;
  private final long deadline;
  private volatile boolean canceled;
  private boolean settled;
  public ForegroundMutationGate(Scheduler scheduler, Reader reader, Runnable ready, Failure failure) {
    this.scheduler = scheduler; this.reader = reader; this.ready = ready; this.failure = failure;
    deadline = scheduler.now() + TIMEOUT_MS;
  }
  public void start() { scheduler.post(this, 0); }
  public void cancel() { canceled = true; run(); }
  public boolean isCanceled() { return canceled; }
  public String beginExecution() {
    return scheduler.now() >= deadline ? "FOREGROUND_REQUIRED" : rejectionNow();
  }
  /** Recheck directly before protected mutation, even after the wait completed. */
  public String rejectionNow() {
    if (canceled) return "FOREGROUND_REQUIRED";
    State state;
    try { state = reader.read(); }
    catch (RuntimeException error) { return "LOCAL_QUEUE_UNAVAILABLE"; }
    String code = rejection(state);
    return code != null ? code : state.focused ? null : "FOREGROUND_REQUIRED";
  }
  @Override public synchronized void run() {
    if (settled) return;
    State state;
    try { state = reader.read(); }
    catch (RuntimeException error) { settled = true; failure.reject("LOCAL_QUEUE_UNAVAILABLE"); return; }
    String code = canceled ? "FOREGROUND_REQUIRED" : rejection(state);
    if (code == null && scheduler.now() >= deadline) code = "FOREGROUND_REQUIRED";
    if (code != null) { settled = true; failure.reject(code); }
    else if (state.focused) { settled = true; ready.run(); }
    else scheduler.post(this, Math.min(POLL_MS, deadline - scheduler.now()));
  }
}
