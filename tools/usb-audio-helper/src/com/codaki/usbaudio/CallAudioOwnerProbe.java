package com.codaki.usbaudio;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs a single fixed read-only audio dump, bounded independently of PCM processing. */
public final class CallAudioOwnerProbe implements AutoCloseable {
  private static final long TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(800);
  interface ProcessStarter { Process start() throws Exception; }
  private final ProcessStarter starter;
  private final Object lock = new Object();
  private Process active;
  private boolean closed;
  private long generation;

  public CallAudioOwnerProbe() {
    this(() -> new ProcessBuilder("/system/bin/dumpsys", "audio").redirectErrorStream(true).start());
  }
  CallAudioOwnerProbe(ProcessStarter starter) { this.starter = starter; }

  public CallAudioOwnerParser.State sample() {
    final long epoch;
    synchronized (lock) {
      if (closed || active != null) return CallAudioOwnerParser.State.UNKNOWN;
      epoch = generation;
    }
    long deadline = System.nanoTime() + TIMEOUT_NS;
    Process process = null;
    Thread reader = null;
    try {
      process = starter.start();
      synchronized (lock) {
        if (closed || epoch != generation || active != null) return CallAudioOwnerParser.State.UNKNOWN;
        active = process;
      }
      final Process dump = process;
      AtomicReference<CallAudioOwnerParser.State> result = new AtomicReference<>();
      reader = new Thread(() -> {
        CallAudioOwnerParser parser = new CallAudioOwnerParser();
        try (InputStream input = dump.getInputStream()) {
          byte[] chunk = new byte[8192];
          int count;
          while ((count = input.read(chunk)) != -1) {
            if (!parser.consume(chunk, 0, count)) { dump.destroyForcibly(); return; }
          }
          result.set(parser.finish());
        } catch (Exception ignored) { /* Unknown is the only failure result; no dump or owner text is logged. */ }
      }, "CallAudioOwnerDump");
      reader.setDaemon(true); reader.start();
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS) || process.exitValue() != 0)
        return CallAudioOwnerParser.State.UNKNOWN;
      remaining = deadline - System.nanoTime();
      if (remaining > 0) reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
      synchronized (lock) {
        if (closed || epoch != generation || reader.isAlive()) return CallAudioOwnerParser.State.UNKNOWN;
      }
      CallAudioOwnerParser.State parsed = result.get();
      return parsed == null ? CallAudioOwnerParser.State.UNKNOWN : parsed;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); return CallAudioOwnerParser.State.UNKNOWN;
    } catch (Exception ignored) {
      return CallAudioOwnerParser.State.UNKNOWN;
    } finally {
      if (process != null) {
        process.destroyForcibly();
        try { process.getOutputStream().close(); } catch (Exception ignored) { }
        synchronized (lock) { if (active == process) active = null; }
      }
      if (reader != null && reader.isAlive()) reader.interrupt();
    }
  }

  /** Cancels an in-flight dump without waiting on the dump or reader thread. */
  public void cancel() {
    Process process;
    synchronized (lock) { generation++; process = active; }
    if (process != null) process.destroyForcibly();
  }

  @Override public void close() {
    synchronized (lock) { closed = true; }
    cancel();
  }
}
