package com.codaki.usbaudio;

/** Exact captured-sample and terminal-alignment regressions; no Android mocks. */
public final class PcmStopTailTest {
  private static int tests;
  public static void main(String[] args) throws Exception {
    lateMicrophoneFillsItsSuffix();
    lateOutputFillsItsSuffix();
    preservesExcessForNextTailPacket();
    retainsTrueLiveGapFlags();
    preservesMicrophoneStartupPadding();
    terminalPaddingDoesNotInventMissingFrames();
    equalShortChannelsRemainShort();
    refusesSecondEmission();
    requiresBothFrozenBeforeConsuming();
    System.out.println("PcmStopTail: " + tests + " tests passed");
  }

  private static void lateMicrophoneFillsItsSuffix() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 320), 320), b = take(right, sequence(2000, 17), 17);
    right.offer(sequence(2017, 64), 64); left.freeze(); right.freeze();
    PcmStopTail tail = new PcmStopTail(a, 320, b, 17, 0);
    byte[] pcm = tail.complete(left, right);
    equal(1280, pcm.length, "stereo length");
    for (int i = 0; i < 320; i++) {
      equal(1000 + i, sample(pcm, i, 0), "output sample");
      equal(i < 81 ? 2000 + i : 0, sample(pcm, i, 1), "mic late sample position");
    }
    equal(320, left.snapshot().deliveredFrames, "left once");
    equal(81, right.snapshot().deliveredFrames, "right once");
    equal(0, right.size(), "no duplicate tail"); tests++;
  }

  private static void lateOutputFillsItsSuffix() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 17), 17), b = take(right, sequence(2000, 320), 320);
    left.offer(sequence(1017, 64), 64); left.freeze(); right.freeze();
    byte[] pcm = new PcmStopTail(a, 17, b, 320, 0).complete(left, right);
    for (int i = 0; i < 320; i++) {
      equal(i < 81 ? 1000 + i : 0, sample(pcm, i, 0), "output late sample position");
      equal(2000 + i, sample(pcm, i, 1), "mic sample");
    }
    equal(81, left.snapshot().deliveredFrames, "left once"); tests++;
  }

  private static void preservesExcessForNextTailPacket() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 320), 320), b = take(right, sequence(2000, 17), 17);
    right.offer(sequence(2017, 400), 400); left.freeze(); right.freeze();
    byte[] pcm = new PcmStopTail(a, 320, b, 17, 0).complete(left, right);
    equal(2319, sample(pcm, 319, 1), "first packet ends at logical320");
    equal(97, right.size(), "preserved excess");
    short[] rest = new short[200]; equal(97, right.drain(rest, 0, 200), "remaining tail");
    for (int i = 0; i < 97; i++) equal(2320 + i, rest[i], "remaining sample position");
    equal(417, right.snapshot().deliveredFrames, "all captured once"); tests++;
  }

  private static void retainsTrueLiveGapFlags() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 290), 320), b = take(right, sequence(2000, 17), 17);
    right.offer(sequence(2017, 64), 64); left.freeze(); right.freeze();
    PcmStopTail tail = new PcmStopTail(a, 320, b, 17, 4 | 2);
    tail.complete(left, right);
    equal(6, tail.flags, "true live gap and policy silence retained");
    equal(30, left.snapshot().missingFrames, "real missing retained");
    equal(0, right.snapshot().missingFrames, "no fake terminal miss"); tests++;
  }

  private static void terminalPaddingDoesNotInventMissingFrames() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 320), 320), b = take(right, sequence(2000, 17), 17);
    left.freeze(); right.freeze(); PcmStopTail tail = new PcmStopTail(a, 320, b, 17, 0);
    byte[] pcm = tail.complete(left, right);
    for (int i = 17; i < 320; i++) equal(0, sample(pcm, i, 1), "terminal zero padding");
    equal(0, tail.flags, "no terminal gap flag");
    equal(17, right.snapshot().consumedFrames, "no invented cursor advance");
    equal(0, right.snapshot().missingFrames, "no invented missing"); tests++;
  }

  private static void preservesMicrophoneStartupPadding() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 320), 320), b = new short[320];
    right.offer(sequence(2000, 17), 17); right.read(b, 10, 17);
    right.offer(sequence(2017, 64), 64); left.freeze(); right.freeze();
    byte[] pcm = new PcmStopTail(a, 320, b, 27, 0).complete(left, right);
    for (int i = 0; i < 320; i++) equal(i >= 10 && i < 91 ? 2000 + i - 10 : 0,
        sample(pcm, i, 1), "startup offset plus late suffix");
    equal(81, right.snapshot().deliveredFrames, "startup zeros are not produced mic samples"); tests++;
  }

  private static void equalShortChannelsRemainShort() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    short[] a = take(left, sequence(1000, 17), 17), b = take(right, sequence(2000, 17), 17);
    left.freeze(); right.freeze();
    equal(68, new PcmStopTail(a, 17, b, 17, 0).complete(left, right).length, "short final packet"); tests++;
  }

  private static void refusesSecondEmission() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024); left.freeze(); right.freeze();
    PcmStopTail tail = new PcmStopTail(new short[320], 0, new short[320], 0, 0);
    tail.complete(left, right);
    try { tail.complete(left, right); throw new AssertionError("second emission allowed"); }
    catch (IllegalStateException expected) { } tests++;
  }

  private static void requiresBothFrozenBeforeConsuming() throws Exception {
    PcmFifo left = new PcmFifo(1024), right = new PcmFifo(1024);
    left.offer(sequence(1000, 64), 64); left.freeze();
    PcmStopTail tail = new PcmStopTail(new short[320], 0, new short[320], 17, 0);
    try { tail.complete(left, right); throw new AssertionError("unfrozen FIFO accepted"); }
    catch (IllegalStateException expected) { }
    equal(64, left.size(), "no partial consumption on validation failure"); tests++;
  }

  private static short[] take(PcmFifo fifo, short[] values, int count) throws Exception {
    fifo.offer(values, values.length); short[] result = new short[320]; fifo.read(result, 0, count); return result;
  }
  private static short[] sequence(int start, int length) {
    short[] values = new short[length]; for (int i = 0; i < length; i++) values[i] = (short)(start + i); return values;
  }
  private static int sample(byte[] pcm, int frame, int channel) {
    int at = frame * 4 + channel * 2; return (short)((pcm[at] & 255) | (pcm[at + 1] << 8));
  }
  private static void equal(long expected, long actual, String message) {
    if (expected != actual) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
  }
}
