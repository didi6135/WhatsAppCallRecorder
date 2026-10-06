package com.codaki.usbaudio;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A fixed read-only Telecom metadata dump, bounded from process startup through exit. */
public final class TelecomCallProbe implements AutoCloseable {
  private static final long TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(800);
  interface ProcessStarter { Process start() throws Exception; }
  private final ProcessStarter starter;
  private final int sdk;
  private final Object lock = new Object();
  private Attempt active;
  private boolean closed;

  private static final class Attempt {
    final long deadline = System.nanoTime() + TIMEOUT_NS;
    final CountDownLatch done = new CountDownLatch(1);
    volatile Process process;
    volatile boolean cancelled;
    boolean workerDone, samplingDone;
    volatile TelecomCallParser.State result = TelecomCallParser.State.UNKNOWN;
  }

  public TelecomCallProbe() {
    this(36);
  }
  public TelecomCallProbe(int sdk) {
    this(sdk, () -> new ProcessBuilder("/system/bin/dumpsys", "telecom").redirectErrorStream(true).start());
  }
  TelecomCallProbe(ProcessStarter starter) { this(36, starter); }
  TelecomCallProbe(int sdk, ProcessStarter starter) { this.sdk = sdk; this.starter = starter; }

  public TelecomCallParser.State sample() {
    final Attempt attempt;
    synchronized (lock) {
      if (closed || active != null) return TelecomCallParser.State.UNKNOWN;
      active = attempt = new Attempt();
    }
    Thread worker = new Thread(() -> run(attempt), "TelecomCurrentCallDump");
    worker.setDaemon(true);
    try {
      worker.start();
      long remaining = attempt.deadline - System.nanoTime();
      if (remaining <= 0 || !attempt.done.await(remaining, TimeUnit.NANOSECONDS))
        return TelecomCallParser.State.UNKNOWN;
      synchronized (lock) {
        return closed || attempt.cancelled || System.nanoTime() >= attempt.deadline
            ? TelecomCallParser.State.UNKNOWN : attempt.result;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); return TelecomCallParser.State.UNKNOWN;
    } finally {
      synchronized (lock) {
        attempt.samplingDone = true; attempt.cancelled = true;
        if (attempt.workerDone && active == attempt) active = null;
      }
      dispose(attempt.process);
    }
  }

  private void run(Attempt attempt) {
    Process process = null;
    try {
      process = starter.start();
      synchronized (lock) {
        if (closed || active != attempt || attempt.cancelled || System.nanoTime() >= attempt.deadline) return;
        attempt.process = process;
      }
      TelecomCallParser parser = new TelecomCallParser(sdk);
      try (InputStream input = process.getInputStream()) {
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
          if (attempt.cancelled || System.nanoTime() >= attempt.deadline
              || !parser.consume(chunk, 0, count)) return;
        }
      }
      long remaining = attempt.deadline - System.nanoTime();
      if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS) || process.exitValue() != 0) return;
      TelecomCallParser.State parsed = parser.finish();
      synchronized (lock) {
        if (!closed && active == attempt && !attempt.cancelled && System.nanoTime() < attempt.deadline)
          attempt.result = parsed;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
      // Failure is exclusively UNKNOWN; dump text and temporary identifiers are never logged.
    } finally {
      dispose(process);
      synchronized (lock) {
        attempt.workerDone = true;
        if (attempt.samplingDone && active == attempt) active = null;
      }
      attempt.done.countDown();
    }
  }

  /** Cancels promptly, including startup attempts which may return a process later. */
  public void cancel() {
    Attempt attempt;
    synchronized (lock) {
      attempt = active;
      if (attempt != null) attempt.cancelled = true;
    }
    if (attempt != null) { dispose(attempt.process); attempt.done.countDown(); }
  }
  @Override public void close() {
    synchronized (lock) { closed = true; }
    cancel();
  }
  private static void dispose(Process process) {
    if (process == null) return;
    try { process.destroyForcibly(); } catch (Exception ignored) { }
    try { process.getInputStream().close(); } catch (Exception ignored) { }
    try { process.getErrorStream().close(); } catch (Exception ignored) { }
    try { process.getOutputStream().close(); } catch (Exception ignored) { }
  }
}
