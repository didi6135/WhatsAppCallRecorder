package com.codaki.usbaudio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Explicitly armed metadata observation; never reads or captures call audio. */
public final class CallAttributionMonitor implements AutoCloseable {
  interface AudioSource extends AutoCloseable {
    CallAudioOwnerParser.State sample();
    void cancel();
    void close();
  }
  interface TelecomSource extends AutoCloseable {
    TelecomCallParser.State sample();
    void cancel();
    void close();
  }
  private final AudioSource audio;
  private final TelecomSource telecom;
  private final LongSupplier clock;
  private final Object lock = new Object();
  private final AtomicReference<CallAttributionFrame.Envelope> pending = new AtomicReference<>();
  private boolean enabled, closed;
  private long generation;
  private Thread worker;

  public CallAttributionMonitor(LongSupplier clock) {
    this(clock, 36);
  }
  public CallAttributionMonitor(LongSupplier clock, int sdk) {
    this(audioSource(new CallAudioOwnerProbe()), telecomSource(new TelecomCallProbe(sdk)), clock);
  }
  CallAttributionMonitor(AudioSource audio, TelecomSource telecom, LongSupplier clock) {
    if (audio == null || telecom == null || clock == null) throw new NullPointerException();
    this.audio = audio; this.telecom = telecom; this.clock = clock;
  }
  private static AudioSource audioSource(CallAudioOwnerProbe probe) {
    return new AudioSource() {
      public CallAudioOwnerParser.State sample() { return probe.sample(); }
      public void cancel() { probe.cancel(); }
      public void close() { probe.close(); }
    };
  }
  private static TelecomSource telecomSource(TelecomCallProbe probe) {
    return new TelecomSource() {
      public TelecomCallParser.State sample() { return probe.sample(); }
      public void cancel() { probe.cancel(); }
      public void close() { probe.close(); }
    };
  }
  public void enable() {
    synchronized (lock) {
      if (closed || enabled) return;
      enabled = true; generation++; pending.set(null);
      if (worker == null) {
        worker = new Thread(this::observe, "CallAttributionMonitor");
        worker.setDaemon(true); worker.start();
      }
      lock.notifyAll();
    }
  }
  public void disable() {
    synchronized (lock) { enabled = false; generation++; pending.set(null); lock.notifyAll(); }
    audio.cancel(); telecom.cancel();
  }
  /** Only the helper's existing sole frame writer drains the bounded one-slot envelope. */
  public CallAttributionFrame.Envelope takePending() {
    synchronized (lock) { return enabled && !closed ? pending.getAndSet(null) : null; }
  }
  private void observe() {
    boolean followTelecom = false;
    long previousEpoch = -1;
    try {
      while (true) {
        long epoch;
        synchronized (lock) {
          while (!closed && !enabled) lock.wait();
          if (closed) return;
          epoch = generation;
        }
        if (epoch != previousEpoch) { followTelecom = false; previousEpoch = epoch; }
        long nextAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        CallAudioOwnerParser.State result = audio.sample();
        long audioAt = clock.getAsLong();
        CallAttributionFrame.Proof proof = null;
        if (followTelecom || (result.known && result.mode == 3 && result.ownerUid == 1000)) {
          TelecomCallParser.State call = telecom.sample();
          long telecomAt = clock.getAsLong();
          proof = new CallAttributionFrame.Proof(call.known, call.packageCode, call.userId,
              call.liveCalls, call.activeCalls, call.foregroundMatched, call.selfManaged, call.voip, telecomAt);
          // HOLD and unknown output are not end-of-episode evidence. Continue to observe the
          // authoritative live set even when Android temporarily leaves communication mode.
          // Keep emitting fresh zero-call proof after end as well: a paused app may miss a
          // short idle window, and manual STOP must clear only after sustained known end.
          followTelecom = true;
        }
        CallAttributionFrame.Envelope snapshot = new CallAttributionFrame.Envelope(
            result.mode, result.ownerUid, result.known, audioAt, proof);
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
    finally { audio.close(); telecom.close(); }
  }
  @Override public void close() {
    Thread previous;
    synchronized (lock) {
      closed = true; enabled = false; generation++; pending.set(null);
      previous = worker; lock.notifyAll();
    }
    audio.close(); telecom.close();
    if (previous != null) previous.interrupt();
  }
}
