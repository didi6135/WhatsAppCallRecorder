package com.codaki.usbaudio;

import java.io.IOException;

/** Exactly one ownership transfer; a timed-out startup cannot later become a detached helper. */
final class BootstrapHandoff {
  private boolean handedOff, cancelled;

  static void requireAck(int value) throws IOException {
    if (value != 1) throw new IOException("Detached ownership acknowledgement was not received.");
  }

  synchronized void complete() {
    if (cancelled || handedOff) throw new IllegalStateException("Detached ownership transfer is no longer pending.");
    handedOff = true;
  }

  synchronized boolean cancelIfPending() {
    if (handedOff || cancelled) return false;
    cancelled = true;
    return true;
  }

  synchronized boolean isComplete() { return handedOff; }

  static void requireIndependentSession(String stat, int expectedPid) {
    try {
      if (stat == null || stat.length() > 4096) throw new IllegalArgumentException();
      int firstSpace = stat.indexOf(' '), close = stat.lastIndexOf(')');
      if (firstSpace < 1 || close < firstSpace || close + 2 >= stat.length()) throw new IllegalArgumentException();
      int pid = Integer.parseInt(stat.substring(0, firstSpace));
      String[] fields = stat.substring(close + 2).trim().split("\\s+");
      if (fields.length < 4 || pid != expectedPid || Integer.parseInt(fields[2]) != expectedPid
          || Integer.parseInt(fields[3]) != expectedPid) throw new IllegalArgumentException();
    } catch (RuntimeException malformed) {
      throw new SecurityException("Detached worker must own its process group and session.");
    }
  }
}
