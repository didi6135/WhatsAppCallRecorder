package com.didi4164.WhatsAppCallRecorder;

import java.util.concurrent.atomic.AtomicInteger;

/** One non-reused Activity request ID and cancellable local mutation gate per authorization attempt. */
public final class DriveConnectionAttempt {
  private static final AtomicInteger NEXT = new AtomicInteger(48101);
  public final int requestCode;
  private boolean active = true;
  public interface Work<T> { T run(); }
  public DriveConnectionAttempt() {
    requestCode = NEXT.getAndUpdate(value -> value < 65536 ? value + 1 : value);
    // Android's legacy Activity request codes are 16-bit. Fail closed instead of reusing a stale ID.
    if (requestCode > 65535) throw new IllegalStateException("CONNECTION_BUSY");
  }
  public synchronized boolean isActive() { return active; }
  public synchronized boolean matches(int returnedCode) { return active && requestCode == returnedCode; }
  public synchronized void cancel() { active = false; }
  public synchronized <T> T mutate(Work<T> work) {
    if (!active) throw new IllegalStateException("FOREGROUND_REQUIRED");
    return work.run();
  }
  public synchronized void complete(Runnable commit) {
    if (!active) throw new IllegalStateException("FOREGROUND_REQUIRED");
    commit.run();
    active = false;
  }
}
