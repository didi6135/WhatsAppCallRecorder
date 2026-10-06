package com.codaki.usbaudio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** A metadata-only observer, explicitly armed over the authenticated app channel. */
public final class CallAudioOwnerMonitor implements AutoCloseable {
  public static final class Snapshot {
    public final CallAudioOwnerParser.State state;
    /** Captured when the probe finishes; queuing or PCM draining never refreshes it. */
    public final long observedAtMs;
    private Snapshot(CallAudioOwnerParser.State state, long observedAtMs) {
      this.state = state; this.observedAtMs = observedAtMs;
    }
  }
  private final CallAudioOwnerProbe probe;
  private final LongSupplier observationClock;
  private final Object lock = new Object();
  private final AtomicReference<Snapshot> pending = new AtomicReference<>();
  private boolean enabled, closed;
  private long generation;
  private Thread worker;

  /** The Android helper supplies SystemClock.elapsedRealtime, shared with its app owner. */
  public CallAudioOwnerMonitor(LongSupplier observationClock) { this(new CallAudioOwnerProbe(), observationClock); }
  CallAudioOwnerMonitor(CallAudioOwnerProbe probe) {
    this(probe, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
  }
  CallAudioOwnerMonitor(CallAudioOwnerProbe probe, LongSupplier observationClock) {
    if (probe == null || observationClock == null) throw new NullPointerException();
    this.probe = probe; this.observationClock = observationClock;
  }

  public void enable() {
    synchronized (lock) {
      if (closed || enabled) return;
      enabled = true; generation++; pending.set(null);
      if (worker == null) {
        worker = new Thread(this::observe, "CallAudioOwnerMonitor");
        worker.setDaemon(true); worker.start();
      }
      lock.notifyAll();
    }
  }

  public void disable() {
    synchronized (lock) { enabled = false; generation++; pending.set(null); lock.notifyAll(); }
    probe.cancel();
  }

  /** Only the existing helper writer drains this slot; observer threads never write TCP. */
  public Snapshot takePending() {
    synchronized (lock) { return enabled && !closed ? pending.getAndSet(null) : null; }
  }

  private void observe() {
    try {
      while (true) {
        long epoch;
        synchronized (lock) {
          while (!closed && !enabled) lock.wait();
          if (closed) return;
          epoch = generation;
        }
        long nextAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        CallAudioOwnerParser.State result = probe.sample();
        Snapshot snapshot = new Snapshot(result, observationClock.getAsLong());
        synchronized (lock) {
          if (closed) return;
          if (enabled && epoch == generation) pending.set(snapshot);
          while (!closed && enabled && epoch == generation) {
            long remaining = nextAt - System.nanoTime();
            if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.timedWait(lock, remaining);
          }
        }
      }
    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    finally { probe.close(); }
  }

  @Override public void close() {
    Thread previous;
    synchronized (lock) {
      closed = true; enabled = false; generation++; pending.set(null);
      previous = worker; lock.notifyAll();
    }
    probe.close();
    if (previous != null) previous.interrupt();
  }
}
