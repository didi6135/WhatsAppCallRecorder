package com.didi4164.WhatsAppCallRecorder;

/** Recognizes the launcher's complete, tokenless ownership-handoff line. */
final class BootstrapHandoffSignal {
  private static final String READY = "BOOTSTRAP_DETACHED_HANDOFF_OK";
  private static final int MAX_LINE_BYTES = 1024;
  private final StringBuilder line = new StringBuilder();
  private boolean discarded;
  private volatile boolean handedOff;

  void accept(byte[] bytes, int offset, int count) {
    for (int index = offset; index < offset + count; index++) {
      int next = bytes[index] & 255;
      if (next == '\n') {
        if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
        if (!discarded && READY.contentEquals(line)) handedOff = true;
        line.setLength(0);
        discarded = false;
      } else if (!discarded) {
        if (line.length() == MAX_LINE_BYTES) {
          line.setLength(0);
          discarded = true;
        } else line.append((char) next);
      }
    }
  }

  boolean isHandedOff() { return handedOff; }
}
