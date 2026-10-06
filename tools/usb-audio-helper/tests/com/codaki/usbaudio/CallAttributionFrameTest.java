package com.codaki.usbaudio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Synthetic payload tests only; no Android, Telecom dump, call ID or private data. */
public final class CallAttributionFrameTest {
  private static int passed;
  private static final long NOW = 10000;

  public static void main(String[] args) throws Exception {
    nullProofRoundTrip();
    exactProofRoundTrip();
    businessAndOtherRoundTrip();
    ambiguousCountsRoundTrip();
    emptyAndUnknownRoundTrip();
    distinctStaleTimestampsPreserved();
    goldenWireLayout();
    nextFrameUnconsumed();
    boundaryValues();
    invalidAudioBounds();
    invalidProofBounds();
    invalidAttribution();
    noncanonicalEmpty();
    noncanonicalUnknown();
    futureTimestamps();
    negativeReceiveClock();
    futureArithmeticSaturates();
    noncanonicalBooleans();
    everyTruncatedPrefixRejected();
    writeRejectsBeforeProducingBytes();
    System.out.println("Call attribution frame tests passed: " + passed);
  }

  private static CallAttributionFrame.Proof proof() {
    return new CallAttributionFrame.Proof(true, 1, 0, 1, 1, true, true, true, 9001);
  }

  private static CallAttributionFrame.Envelope envelope(CallAttributionFrame.Proof proof) {
    return new CallAttributionFrame.Envelope(3, 10544, true, 9017, proof);
  }

  private static void nullProofRoundTrip() throws Exception {
    CallAttributionFrame.Envelope decoded = roundTrip(envelope(null), NOW);
    require(decoded.mode == 3 && decoded.ownerUid == 10544 && decoded.known, "audio fields retained");
    require(decoded.observedAtMs == 9017 && decoded.telecom == null, "optional proof absent");
    require(encode(envelope(null)).length == 18, "payload without proof is 18 bytes");
    passed++;
  }

  private static void exactProofRoundTrip() throws Exception {
    CallAttributionFrame.Envelope expected = envelope(proof());
    assertEnvelope(expected, roundTrip(expected, NOW));
    require(encode(expected).length == 46, "payload with proof is 46 bytes");
    passed++;
  }

  private static void businessAndOtherRoundTrip() throws Exception {
    for (int packageCode : new int[] {2, 3}) {
      CallAttributionFrame.Envelope expected = envelope(new CallAttributionFrame.Proof(
          true, packageCode, 10, 2, 1, false, true, true, 8000));
      assertEnvelope(expected, roundTrip(expected, NOW));
    }
    passed++;
  }

  private static void ambiguousCountsRoundTrip() throws Exception {
    for (boolean foregroundMatched : new boolean[] {false, true}) {
      CallAttributionFrame.Envelope expected = envelope(new CallAttributionFrame.Proof(
          true, 0, -1, 2, 1, foregroundMatched, false, false, 8000));
      assertEnvelope(expected, roundTrip(expected, NOW));
    }
    CallAttributionFrame.Envelope structuralOnly = envelope(new CallAttributionFrame.Proof(
        true, 0, -1, 1, 1, true, true, true, 8000));
    assertEnvelope(structuralOnly, roundTrip(structuralOnly, NOW));
    passed++;
  }

  private static void emptyAndUnknownRoundTrip() throws Exception {
    for (boolean known : new boolean[] {false, true}) {
      CallAttributionFrame.Envelope expected = new CallAttributionFrame.Envelope(-1, -1, false, 0,
          new CallAttributionFrame.Proof(known, 0, -1, known ? 0 : -1, known ? 0 : -1, false, false, false, 0));
      assertEnvelope(expected, roundTrip(expected, NOW));
    }
    passed++;
  }

  private static void distinctStaleTimestampsPreserved() throws Exception {
    CallAttributionFrame.Envelope expected = new CallAttributionFrame.Envelope(3, 10544, true, 1300,
        new CallAttributionFrame.Proof(true, 1, 0, 1, 1, true, true, true, 1100));
    CallAttributionFrame.Envelope decoded = roundTrip(expected, 1000000);
    require(decoded.observedAtMs == 1300 && decoded.telecom.observedAtMs == 1100,
        "transport does not freshen either stale producer timestamp");
    passed++;
  }

  private static void goldenWireLayout() throws Exception {
    // Fixed golden payload: no frame-type byte, Java big-endian primitives in the agreed order.
    byte[] expected = new byte[] {
        0, 0, 0, 3, 0, 0, 41, 48, 1, 0, 0, 0, 0, 0, 0, 35, 57, 1,
        1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 1,
        0, 0, 0, 0, 0, 0, 35, 41
    };
    require(Arrays.equals(expected, encode(envelope(proof()))), "golden field order and primitive widths");
    assertEnvelope(envelope(proof()), decode(expected, NOW));
    passed++;
  }

  private static void nextFrameUnconsumed() throws Exception {
    for (CallAttributionFrame.Proof proof : new CallAttributionFrame.Proof[] {null, proof()}) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      CallAttributionFrame.write(output, envelope(proof));
      output.writeByte(5);
      DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
      assertEnvelope(envelope(proof), CallAttributionFrame.read(input, NOW));
      require(input.readUnsignedByte() == 5 && input.available() == 0, "codec consumes payload only");
    }
    passed++;
  }

  private static void boundaryValues() throws Exception {
    assertEnvelope(new CallAttributionFrame.Envelope(0, 0, true, 0, null),
        roundTrip(new CallAttributionFrame.Envelope(0, 0, true, 0, null), NOW));
    CallAttributionFrame.Envelope upper = new CallAttributionFrame.Envelope(6, Integer.MAX_VALUE, true, NOW + 1000,
        new CallAttributionFrame.Proof(true, 3, 21474, 128, 128, true, true, true, NOW + 1000));
    assertEnvelope(upper, roundTrip(upper, NOW));
    assertEnvelope(new CallAttributionFrame.Envelope(3, 10544, false, 0, null),
        roundTrip(new CallAttributionFrame.Envelope(3, 10544, false, 0, null), NOW));
    passed++;
  }

  private static void invalidAudioBounds() throws Exception {
    reject(new CallAttributionFrame.Envelope(-2, 10544, false, 1, null));
    reject(new CallAttributionFrame.Envelope(7, 10544, false, 1, null));
    reject(new CallAttributionFrame.Envelope(3, -2, false, 1, null));
    reject(new CallAttributionFrame.Envelope(-1, 10544, true, 1, null));
    reject(new CallAttributionFrame.Envelope(3, -1, true, 1, null));
    reject(new CallAttributionFrame.Envelope(3, 10544, true, -1, null));
    passed++;
  }

  private static void invalidProofBounds() throws Exception {
    rejectProof(true, -1, 0, 1, 1, false, false, false, 1);
    rejectProof(true, 4, 0, 1, 1, false, false, false, 1);
    rejectProof(true, 1, -2, 1, 1, false, false, false, 1);
    rejectProof(true, 1, 21475, 1, 1, false, false, false, 1);
    rejectProof(true, 1, 0, -1, 0, false, false, false, 1);
    rejectProof(true, 1, 0, 129, 0, false, false, false, 1);
    rejectProof(true, 1, 0, 1, -1, false, false, false, 1);
    rejectProof(true, 1, 0, 1, 2, false, false, false, 1);
    rejectProof(true, 1, 0, 1, 1, false, false, false, -1);
    passed++;
  }

  private static void invalidAttribution() throws Exception {
    rejectProof(true, 1, -1, 1, 1, false, false, false, 1);
    rejectProof(true, 0, 0, 1, 1, false, false, false, 1);
    passed++;
  }

  private static void noncanonicalEmpty() throws Exception {
    rejectProof(true, 1, -1, 0, 0, false, false, false, 1);
    rejectProof(true, 0, 0, 0, 0, false, false, false, 1);
    rejectProof(true, 0, -1, 0, 1, false, false, false, 1);
    rejectProof(true, 0, -1, 0, 0, true, false, false, 1);
    rejectProof(true, 0, -1, 0, 0, false, true, false, 1);
    rejectProof(true, 0, -1, 0, 0, false, false, true, 1);
    passed++;
  }

  private static void noncanonicalUnknown() throws Exception {
    rejectProof(false, 1, -1, -1, -1, false, false, false, 1);
    rejectProof(false, 0, 0, -1, -1, false, false, false, 1);
    rejectProof(false, 0, -1, 0, 0, false, false, false, 1);
    rejectProof(false, 0, -1, 0, -1, false, false, false, 1);
    rejectProof(false, 0, -1, -1, 0, false, false, false, 1);
    rejectProof(false, 0, -1, 1, 1, false, false, false, 1);
    rejectProof(false, 0, -1, -1, -1, true, false, false, 1);
    rejectProof(false, 0, -1, -1, -1, false, true, false, 1);
    rejectProof(false, 0, -1, -1, -1, false, false, true, 1);
    passed++;
  }

  private static void futureTimestamps() throws Exception {
    expectReadFailure(raw(new CallAttributionFrame.Envelope(3, 10544, true, NOW + 1001, null)), NOW);
    expectReadFailure(raw(envelope(new CallAttributionFrame.Proof(true, 1, 0, 1, 1, true, true, true, NOW + 1001))), NOW);
    passed++;
  }

  private static void negativeReceiveClock() throws Exception {
    expectReadFailure(encode(envelope(null)), -1);
    passed++;
  }

  private static void futureArithmeticSaturates() throws Exception {
    CallAttributionFrame.Envelope upper = new CallAttributionFrame.Envelope(3, 10544, true, Long.MAX_VALUE,
        new CallAttributionFrame.Proof(true, 1, 0, 1, 1, true, true, true, Long.MAX_VALUE));
    assertEnvelope(upper, roundTrip(upper, Long.MAX_VALUE - 500));
    passed++;
  }

  private static void noncanonicalBooleans() throws Exception {
    byte[] valid = encode(envelope(proof()));
    for (int offset : new int[] {8, 17, 18, 35, 36, 37}) {
      byte[] malformed = valid.clone();
      malformed[offset] = 2;
      expectReadFailure(malformed, NOW);
      malformed[offset] = (byte) 255;
      expectReadFailure(malformed, NOW);
    }
    passed++;
  }

  private static void everyTruncatedPrefixRejected() throws Exception {
    for (CallAttributionFrame.Proof proof : new CallAttributionFrame.Proof[] {null, proof()}) {
      byte[] payload = encode(envelope(proof));
      for (int length = 0; length < payload.length; length++)
        expectReadFailure(Arrays.copyOf(payload, length), NOW);
    }
    passed++;
  }

  private static void writeRejectsBeforeProducingBytes() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      CallAttributionFrame.write(new DataOutputStream(bytes), new CallAttributionFrame.Envelope(7, 0, true, 1, null));
      throw new AssertionError("invalid writer envelope accepted");
    } catch (IOException expected) { require(bytes.size() == 0, "invalid shape produces no partial payload"); }
    try {
      CallAttributionFrame.write(new DataOutputStream(bytes), null);
      throw new AssertionError("null writer envelope accepted");
    } catch (IOException expected) { require(bytes.size() == 0, "null shape produces no partial payload"); }
    passed++;
  }

  private static void rejectProof(boolean known, int packageCode, int userId, int liveCalls, int activeCalls,
      boolean foreground, boolean selfManaged, boolean voip, long observedAtMs) throws Exception {
    reject(envelope(new CallAttributionFrame.Proof(known, packageCode, userId, liveCalls, activeCalls,
        foreground, selfManaged, voip, observedAtMs)));
  }

  private static void reject(CallAttributionFrame.Envelope value) throws Exception {
    expectReadFailure(raw(value), NOW);
    try { encode(value); throw new AssertionError("invalid writer shape accepted"); }
    catch (IOException expected) { }
  }

  private static byte[] encode(CallAttributionFrame.Envelope value) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CallAttributionFrame.write(new DataOutputStream(bytes), value);
    return bytes.toByteArray();
  }

  private static byte[] raw(CallAttributionFrame.Envelope value) throws Exception {
    // Bypass codec validation to exercise malformed incoming payloads independently.
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    output.writeInt(value.mode); output.writeInt(value.ownerUid); output.writeBoolean(value.known);
    output.writeLong(value.observedAtMs); output.writeBoolean(value.telecom != null);
    if (value.telecom != null) {
      CallAttributionFrame.Proof proof = value.telecom;
      output.writeBoolean(proof.known); output.writeInt(proof.packageCode); output.writeInt(proof.userId);
      output.writeInt(proof.liveCalls); output.writeInt(proof.activeCalls);
      output.writeBoolean(proof.foregroundMatched); output.writeBoolean(proof.selfManaged); output.writeBoolean(proof.voip);
      output.writeLong(proof.observedAtMs);
    }
    return bytes.toByteArray();
  }

  private static CallAttributionFrame.Envelope decode(byte[] bytes, long nowMs) throws IOException {
    return CallAttributionFrame.read(new DataInputStream(new ByteArrayInputStream(bytes)), nowMs);
  }

  private static CallAttributionFrame.Envelope roundTrip(CallAttributionFrame.Envelope value, long nowMs) throws Exception {
    return decode(encode(value), nowMs);
  }

  private static void expectReadFailure(byte[] bytes, long nowMs) throws Exception {
    try { decode(bytes, nowMs); throw new AssertionError("malformed input accepted"); }
    catch (IOException expected) { }
  }

  private static void assertEnvelope(CallAttributionFrame.Envelope expected, CallAttributionFrame.Envelope actual) {
    require(expected.mode == actual.mode && expected.ownerUid == actual.ownerUid && expected.known == actual.known
        && expected.observedAtMs == actual.observedAtMs, "envelope round trip");
    if (expected.telecom == null) { require(actual.telecom == null, "optional proof absent"); return; }
    CallAttributionFrame.Proof a = expected.telecom, b = actual.telecom;
    require(b != null && a.known == b.known && a.packageCode == b.packageCode && a.userId == b.userId
        && a.liveCalls == b.liveCalls && a.activeCalls == b.activeCalls && a.foregroundMatched == b.foregroundMatched
        && a.selfManaged == b.selfManaged && a.voip == b.voip && a.observedAtMs == b.observedAtMs, "proof round trip");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
