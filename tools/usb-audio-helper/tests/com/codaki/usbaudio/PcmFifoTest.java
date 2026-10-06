package com.codaki.usbaudio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic recording cases. Run without Android, a phone, or audio access. */
public final class PcmFifoTest {
  private static int passed;

  public static void main(String[] args) throws Exception {
    delayedFragmentsRemainInOrder();
    intentionalStartupPaddingIsNotMissingAudio();
    deadlineHoleExpiresOnlyTheLateInterval();
    delayedBatchesCatchUpWithoutShiftingSpeech();
    wrappedQueueKeepsTheFinalTail();
    overflowNeverOverwritesPreviouslyCapturedSamples();
    stopCutoffDrainsEveryCapturedSample();
    emptyStopDoesNotInventAudioOrGaps();
    inFlightPacketAndBufferedTailBothBelongToStop();
    independentTrackCutoffsRetainBothTails();
    rateMismatchIsVisibleAsBacklogAndPreservedAtStop();
    invalidRangesDoNotChangeRecordingCounters();
    System.out.println("PASS: " + passed + " deterministic PCM FIFO recording cases.");
  }

  private static void delayedFragmentsRemainInOrder() throws Exception {
    PcmFifo fifo = new PcmFifo(8);
    fifo.offer(new short[] {10, 11}, 2);
    fifo.offer(new short[] {12, 13, 14}, 3);
    short[] out = new short[5];
    equal(0, fifo.read(out, 0, 5), "fragments arriving before a deadline must not create a hole");
    samples(new short[] {10, 11, 12, 13, 14}, out, "delayed fragment order");
    equal(0, fifo.snapshot().missingFrames, "no missing frames");
    passed++;
  }

  private static void intentionalStartupPaddingIsNotMissingAudio() throws Exception {
    PcmFifo microphone = new PcmFifo(8);
    microphone.offer(new short[] {51, 52, 53}, 3);
    short[] aligned = new short[6];
    // Three initial zeros align a microphone that began later. They are not read
    // from its FIFO and are not expected microphone samples.
    equal(0, microphone.read(aligned, 3, 3), "known startup padding is not a deadline shortage");
    samples(new short[] {0, 0, 0, 51, 52, 53}, aligned, "startup alignment");
    equal(3, microphone.snapshot().consumedFrames, "only actual microphone timeline advances");
    equal(0, microphone.snapshot().missingFrames, "padding must not hide or invent loss");
    passed++;
  }

  private static void deadlineHoleExpiresOnlyTheLateInterval() throws Exception {
    PcmFifo fifo = new PcmFifo(8);
    fifo.offer(new short[] {11, 12}, 2);
    short[] first = new short[] {99, 99, 99, 99, 99};
    equal(3, fifo.read(first, 0, 5), "three unavailable frames must be reported");
    samples(new short[] {11, 12, 0, 0, 0}, first, "missing interval must be silent even in reused buffers");
    equal(3, fifo.offer(new short[] {13, 14, 15, 16, 17}, 5), "late frames for the padded interval expire");
    short[] next = new short[2];
    equal(0, fifo.read(next, 0, 2), "future interval is available");
    samples(new short[] {16, 17}, next, "future speech must stay at its original sample position");
    equal(3, fifo.snapshot().missingFrames, "exact shortage count");
    equal(3, fifo.snapshot().staleFrames, "exact later expiration count");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void delayedBatchesCatchUpWithoutShiftingSpeech() throws Exception {
    PcmFifo fifo = new PcmFifo(8);
    equal(5, fifo.read(new short[5], 0, 5), "entire first interval missing");
    equal(3, fifo.offer(new short[] {0, 1, 2}, 3), "first late batch expires");
    equal(2, fifo.offer(new short[] {3, 4, 5, 6}, 4), "only remaining late portion expires");
    short[] next = new short[2];
    equal(0, fifo.read(next, 0, 2), "catch-up leaves future samples");
    samples(new short[] {5, 6}, next, "catch-up must not shift future speech");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void wrappedQueueKeepsTheFinalTail() throws Exception {
    PcmFifo fifo = new PcmFifo(5);
    fifo.offer(new short[] {1, 2, 3, 4}, 4);
    fifo.read(new short[3], 0, 3);
    fifo.offer(new short[] {5, 6, 7, 8}, 4);
    fifo.freeze();
    short[] tail = new short[5];
    equal(5, fifo.drain(tail, 0, tail.length), "wrapped tail frame count");
    samples(new short[] {4, 5, 6, 7, 8}, tail, "ring wrap must preserve sample order at STOP");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void overflowNeverOverwritesPreviouslyCapturedSamples() throws Exception {
    PcmFifo fifo = new PcmFifo(4);
    fifo.offer(new short[] {1, 2, 3, 4}, 4);
    try {
      fifo.offer(new short[] {5, 6}, 2);
      throw new AssertionError("overflow must reject capture explicitly");
    } catch (IOException expected) {
      equal(2, fifo.snapshot().overflowFrames, "unqueueable audio must be counted");
    }
    fifo.freeze();
    short[] previous = new short[4];
    fifo.drain(previous, 0, 4);
    samples(new short[] {1, 2, 3, 4}, previous, "overflow must not overwrite existing capture");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void stopCutoffDrainsEveryCapturedSample() throws Exception {
    PcmFifo fifo = new PcmFifo(1000);
    fifo.offer(sequence(0, 750), 750);
    fifo.read(new short[320], 0, 320);
    PcmFifo.Snapshot cutoff = fifo.freeze();
    equal(750, cutoff.cutoffProducedFrames, "exclusive captured cutoff");
    equal(430, cutoff.cutoffQueuedFrames, "tail that still belongs to this recording");
    equal(1, fifo.offer(new short[] {999}, 1), "samples arriving after a frozen cutoff are excluded");
    List<Short> tail = drainAll(fifo, 128);
    list(sequence(320, 430), tail, "STOP must drain partial final blocks without dropping the tail");
    equal(750, fifo.snapshot().deliveredFrames, "all captured samples through cutoff delivered");
    equal(1, fifo.snapshot().afterCutoffFrames, "post-cutoff exclusion is explicit");
    equal(0, fifo.snapshot().missingFrames, "a partial final block is not a gap");
    equal(750, fifo.freeze().cutoffProducedFrames, "cutoff remains stable on repeated STOP");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void emptyStopDoesNotInventAudioOrGaps() {
    PcmFifo fifo = new PcmFifo(4);
    fifo.read(new short[3], 0, 3);
    fifo.freeze();
    short[] out = new short[] {22, 23};
    equal(0, fifo.drain(out, 0, 2), "empty stopped FIFO has no final samples");
    samples(new short[] {22, 23}, out, "drain reports absence rather than inventing a block");
    equal(3, fifo.snapshot().missingFrames, "STOP does not add new deadline shortages");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void inFlightPacketAndBufferedTailBothBelongToStop() throws Exception {
    PcmFifo fifo = new PcmFifo(1200);
    fifo.offer(sequence(0, 1000), 1000);
    short[] packetAlreadyRead = new short[320];
    fifo.read(packetAlreadyRead, 0, packetAlreadyRead.length);
    // STOP arrives before the caller has emitted this private packet. It must
    // emit that packet as well as the FIFO tail; freeze cannot restore it.
    fifo.freeze();
    List<Short> emitted = new ArrayList<>();
    for (short value : packetAlreadyRead) emitted.add(value);
    emitted.addAll(drainAll(fifo, 320));
    list(sequence(0, 1000), emitted, "in-flight packet plus frozen tail must be lossless");
    equal(1000, fifo.snapshot().deliveredFrames, "packet and tail accounted exactly once");
    balanced(fifo.snapshot());
    passed++;
  }

  private static void independentTrackCutoffsRetainBothTails() throws Exception {
    PcmFifo output = new PcmFifo(8);
    PcmFifo microphone = new PcmFifo(8);
    output.offer(sequence(10, 7), 7);
    microphone.offer(sequence(50, 3), 3);
    output.freeze(); microphone.freeze();
    list(sequence(10, 7), drainAll(output, 4), "longer output cutoff retains its complete tail");
    list(sequence(50, 3), drainAll(microphone, 4), "shorter microphone cutoff has only its actual samples");
    equal(0, microphone.snapshot().missingFrames, "consumer must distinguish final alignment from lost live audio");
    balanced(output.snapshot()); balanced(microphone.snapshot());
    passed++;
  }

  private static void rateMismatchIsVisibleAsBacklogAndPreservedAtStop() throws Exception {
    final int rate = 16000;
    PcmFifo fasterOutput = new PcmFifo(rate * 2);
    short[] producedSecond = new short[rate + 16]; // +1000 ppm relative clock.
    Arrays.fill(producedSecond, (short) 7);
    for (int seconds = 0; seconds < 600; seconds++) {
      fasterOutput.offer(producedSecond, producedSecond.length);
      for (int packet = 0; packet < 50; packet++) {
        equal(0, fasterOutput.read(new short[320], 0, 320), "rate mismatch need not cause a missing-frame flag");
      }
    }
    PcmFifo.Snapshot cutoff = fasterOutput.freeze();
    equal(9600, cutoff.cutoffQueuedFrames, "ten-minute 1000ppm backlog is 600ms and must be visible");
    equal(0, cutoff.missingFrames, "gap flags alone cannot establish clock alignment");
    equal(9600, drainAll(fasterOutput, 320).size(), "STOP must preserve accumulated capture rather than discard backlog");
    equal(600L * (rate + 16), fasterOutput.snapshot().deliveredFrames, "all faster-track samples retained");
    balanced(fasterOutput.snapshot());
    passed++;
  }

  private static void invalidRangesDoNotChangeRecordingCounters() throws Exception {
    PcmFifo fifo = new PcmFifo(4);
    try {
      fifo.read(new short[2], 1, 2);
      throw new AssertionError("invalid output range must reject");
    } catch (IndexOutOfBoundsException expected) { }
    try {
      fifo.offer(new short[2], 3);
      throw new AssertionError("invalid input range must reject");
    } catch (IndexOutOfBoundsException expected) { }
    equal(0, fifo.snapshot().producedFrames, "invalid ranges cannot advance producer cursor");
    equal(0, fifo.snapshot().consumedFrames, "invalid ranges cannot advance consumer cursor");
    passed++;
  }

  private static List<Short> drainAll(PcmFifo fifo, int block) {
    List<Short> result = new ArrayList<>();
    short[] out = new short[block];
    int count;
    while ((count = fifo.drain(out, 0, block)) > 0) {
      for (int i = 0; i < count; i++) result.add(out[i]);
    }
    return result;
  }

  private static short[] sequence(int first, int count) {
    short[] values = new short[count];
    for (int i = 0; i < count; i++) values[i] = (short) (first + i);
    return values;
  }

  private static void balanced(PcmFifo.Snapshot snapshot) {
    equal(snapshot.producedFrames,
        snapshot.deliveredFrames + snapshot.staleFrames + snapshot.overflowFrames + snapshot.queuedFrames,
        "every captured frame must be delivered, explicitly excluded, or still queued");
    equal(snapshot.consumedFrames, snapshot.deliveredFrames + snapshot.missingFrames,
        "consumer cursor includes actual audio and reported holes");
  }

  private static void samples(short[] expected, short[] actual, String reason) {
    if (!Arrays.equals(expected, actual)) throw new AssertionError(reason + ": " + Arrays.toString(actual));
  }

  private static void list(short[] expected, List<Short> actual, String reason) {
    equal(expected.length, actual.size(), reason + " length");
    for (int i = 0; i < expected.length; i++) equal(expected[i], actual.get(i), reason + " sample " + i);
  }

  private static void equal(long expected, long actual, String reason) {
    if (expected != actual) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
  }
}
