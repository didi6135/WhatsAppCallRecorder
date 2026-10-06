package com.codaki.usbaudio;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, streaming extraction of the current owner only; no dump text is retained. */
public final class CallAudioOwnerParser {
  public static final int MAX_DUMP_BYTES = 1024 * 1024;
  private static final int MAX_LINE_BYTES = 4096;
  private static final Pattern SAMSUNG = Pattern.compile(
      "mAudioModeOwner:\\s*AudioModeInfo:\\s*mMode=(MODE_[A-Z_]+),\\s*mPid=([0-9]+),\\s*mUid=([0-9]+)");

  public static final class State {
    public static final State UNKNOWN = new State(-1, -1, false);
    public final int mode, ownerUid;
    public final boolean known;
    private State(int mode, int ownerUid, boolean known) {
      this.mode = mode; this.ownerUid = ownerUid; this.known = known;
    }
  }

  private final StringBuilder line = new StringBuilder();
  private int byteCount, sectionCount, ownerCount;
  private int ownerMode = -1, ownerUid = -1, ownerPid = -1, actualMode = -1, requestedMode = -1;
  private boolean invalid, inSection, inAospOwner, aosp, ownerNone, sawMode, sawUid, sawPid;

  /** False means the bounded reader must terminate the dump immediately. */
  public boolean consume(byte[] bytes, int offset, int length) {
    if (invalid) return false;
    if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
    if (length > MAX_DUMP_BYTES - byteCount) { invalid = true; line.setLength(0); return false; }
    byteCount += length;
    for (int i = offset; i < offset + length; i++) {
      int value = bytes[i] & 255;
      if (value == 0) { invalid = true; line.setLength(0); return false; }
      if (value == '\n') { acceptLine(line.toString()); line.setLength(0); }
      else if (value != '\r') {
        if (line.length() >= MAX_LINE_BYTES) { invalid = true; line.setLength(0); return false; }
        line.append((char)value);
      }
      if (invalid) { line.setLength(0); return false; }
    }
    return true;
  }

  public State finish() {
    if (!invalid && line.length() != 0) acceptLine(line.toString());
    line.setLength(0);
    if (invalid || sectionCount != 1 || ownerCount != 1) return State.UNKNOWN;
    if (ownerNone) {
      return actualMode == 0 && (requestedMode < 0 || requestedMode == 0)
          ? new State(0, 0, true) : State.UNKNOWN;
    }
    if (!sawMode || !sawUid || !sawPid || ownerMode < 0 || ownerUid < 0 || ownerPid < 0) return State.UNKNOWN;
    if (aosp && actualMode < 0) return State.UNKNOWN;
    if ((actualMode >= 0 && actualMode != ownerMode)
        || (requestedMode >= 0 && requestedMode != ownerMode)) return State.UNKNOWN;
    if ((ownerUid == 0 || ownerPid == 0) && (ownerMode != 0 || ownerUid != 0 || ownerPid != 0)) return State.UNKNOWN;
    return new State(ownerMode, ownerUid, true);
  }

  public static State parse(String dump) {
    if (dump == null || dump.length() > MAX_DUMP_BYTES) return State.UNKNOWN;
    byte[] bytes = dump.getBytes(StandardCharsets.UTF_8);
    CallAudioOwnerParser parser = new CallAudioOwnerParser();
    if (!parser.consume(bytes, 0, bytes.length)) return State.UNKNOWN;
    return parser.finish();
  }

  private void acceptLine(String original) {
    String value = original.trim();
    if (value.equals("Audio mode:")) {
      if (++sectionCount != 1) { invalid = true; return; }
      inSection = true; return;
    }
    if (!inSection) return;
    if (value.equals("- Mode owner stack:") || value.startsWith("mModeOwnerStack:")
        || value.startsWith("mSetModeDeathHandlers:")) {
      inSection = false; inAospOwner = false; return;
    }
    if (value.startsWith("- Requested mode =")) {
      if (requestedMode >= 0) { invalid = true; return; }
      requestedMode = mode(value.substring(value.indexOf('=') + 1).trim());
      if (requestedMode < 0) invalid = true;
      return;
    }
    if (value.startsWith("- Actual mode =")) {
      if (actualMode >= 0) { invalid = true; return; }
      actualMode = mode(value.substring(value.indexOf('=') + 1).trim());
      if (actualMode < 0) invalid = true;
      return;
    }
    if (value.equals("- Mode owner:")) {
      if (++ownerCount != 1) { invalid = true; return; }
      aosp = true; inAospOwner = true; return;
    }
    if (value.startsWith("mAudioModeOwner:")) {
      Matcher matcher = SAMSUNG.matcher(value);
      if (++ownerCount != 1 || !matcher.matches()) { invalid = true; return; }
      ownerMode = mode(matcher.group(1)); ownerPid = integer(matcher.group(2)); ownerUid = integer(matcher.group(3));
      sawMode = sawUid = sawPid = true;
      if (ownerMode < 0 || ownerPid < 0 || ownerUid < 0) invalid = true;
      return;
    }
    if (inAospOwner) {
      if (value.equals("None")) {
        if (ownerNone || sawMode || sawUid || sawPid) invalid = true;
        ownerNone = true; return;
      }
      if (value.startsWith("- Mode:")) {
        if (ownerNone || sawMode) { invalid = true; return; }
        sawMode = true; ownerMode = mode(value.substring(7).trim());
        if (ownerMode < 0) invalid = true;
        return;
      }
      if (value.startsWith("- Uid:")) {
        if (ownerNone || sawUid) { invalid = true; return; }
        sawUid = true; ownerUid = integer(value.substring(6).trim());
        if (ownerUid < 0) invalid = true;
        return;
      }
      if (value.startsWith("- Pid:")) {
        if (ownerNone || sawPid) { invalid = true; return; }
        sawPid = true; ownerPid = integer(value.substring(6).trim());
        if (ownerPid < 0) invalid = true;
        return;
      }
    }
    // A subsequent top-level section cannot provide a missing current UID.
    if (!original.isEmpty() && !Character.isWhitespace(original.charAt(0))
        && !value.startsWith("- ") && !value.isEmpty()) {
      inSection = false; inAospOwner = false;
    }
  }

  private static int integer(String value) {
    if (!value.matches("[0-9]+")) return -1;
    try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return -1; }
  }
  private static int mode(String value) {
    switch (value) {
      case "MODE_NORMAL": return 0;
      case "MODE_RINGTONE": return 1;
      case "MODE_IN_CALL": return 2;
      case "MODE_IN_COMMUNICATION": return 3;
      case "MODE_CALL_SCREENING": return 4;
      case "MODE_CALL_REDIRECT": return 5;
      case "MODE_COMMUNICATION_REDIRECT": return 6;
      default: return -1;
    }
  }
}
