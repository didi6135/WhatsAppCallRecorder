package com.codaki.usbaudio;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Fixed-size, additive frame-6 payload shared by the shell helper and Android receiver.
 * The caller owns the frame-type byte; this codec consumes only its 18/46-byte payload.
 * No strings, call IDs, account IDs, phone numbers or dump text cross this boundary.
 */
public final class CallAttributionFrame {
  public static final int PACKAGE_UNKNOWN = 0;
  public static final int PACKAGE_WHATSAPP = 1;
  public static final int PACKAGE_BUSINESS = 2;
  public static final int PACKAGE_OTHER = 3;
  public static final int MAX_USER_ID = 21474;
  public static final int MAX_CALLS = 128;
  private static final long MAX_FUTURE_MS = 1000;

  public static final class Envelope {
    public final int mode;
    public final int ownerUid;
    public final boolean known;
    public final long observedAtMs;
    public final Proof telecom;

    public Envelope(int mode, int ownerUid, boolean known, long observedAtMs, Proof telecom) {
      this.mode = mode;
      this.ownerUid = ownerUid;
      this.known = known;
      this.observedAtMs = observedAtMs;
      this.telecom = telecom;
    }
  }

  public static final class Proof {
    public final boolean known;
    public final int packageCode;
    public final int userId;
    public final int liveCalls;
    public final int activeCalls;
    public final boolean foregroundMatched;
    public final boolean selfManaged;
    public final boolean voip;
    public final long observedAtMs;

    public Proof(boolean known, int packageCode, int userId, int liveCalls, int activeCalls,
        boolean foregroundMatched, boolean selfManaged, boolean voip, long observedAtMs) {
      this.known = known;
      this.packageCode = packageCode;
      this.userId = userId;
      this.liveCalls = liveCalls;
      this.activeCalls = activeCalls;
      this.foregroundMatched = foregroundMatched;
      this.selfManaged = selfManaged;
      this.voip = voip;
      this.observedAtMs = observedAtMs;
    }
  }

  private CallAttributionFrame() { }

  /** Writes validated producer fields verbatim, without a frame-type byte or stream flush. */
  public static void write(DataOutputStream output, Envelope envelope) throws IOException {
    validateShape(envelope);
    output.writeInt(envelope.mode);
    output.writeInt(envelope.ownerUid);
    output.writeBoolean(envelope.known);
    output.writeLong(envelope.observedAtMs);
    output.writeBoolean(envelope.telecom != null);
    if (envelope.telecom != null) {
      Proof proof = envelope.telecom;
      output.writeBoolean(proof.known);
      output.writeInt(proof.packageCode);
      output.writeInt(proof.userId);
      output.writeInt(proof.liveCalls);
      output.writeInt(proof.activeCalls);
      output.writeBoolean(proof.foregroundMatched);
      output.writeBoolean(proof.selfManaged);
      output.writeBoolean(proof.voip);
      output.writeLong(proof.observedAtMs);
    }
  }

  /**
   * Reads one fixed payload, rejecting malformed fields and impossible producer times.
   * Stale observations remain stale; freshness/attribution authorization belongs to policy.
   */
  public static Envelope read(DataInputStream input, long nowMs) throws IOException {
    if (nowMs < 0) throw invalid("receive time");
    int mode = input.readInt();
    int ownerUid = input.readInt();
    boolean known = readFlag(input);
    long observedAtMs = input.readLong();
    Proof proof = null;
    if (readFlag(input)) {
      proof = new Proof(readFlag(input), input.readInt(), input.readInt(), input.readInt(), input.readInt(),
          readFlag(input), readFlag(input), readFlag(input), input.readLong());
    }
    Envelope envelope = new Envelope(mode, ownerUid, known, observedAtMs, proof);
    validateShape(envelope);
    long latest = nowMs > Long.MAX_VALUE - MAX_FUTURE_MS ? Long.MAX_VALUE : nowMs + MAX_FUTURE_MS;
    if (envelope.observedAtMs > latest || (proof != null && proof.observedAtMs > latest))
      throw invalid("producer time");
    return envelope;
  }

  private static boolean readFlag(DataInputStream input) throws IOException {
    int value = input.readUnsignedByte();
    if (value > 1) throw invalid("boolean");
    return value == 1;
  }

  private static void validateShape(Envelope envelope) throws IOException {
    if (envelope == null) throw invalid("envelope");
    if (envelope.mode < -1 || envelope.mode > 6 || envelope.ownerUid < -1)
      throw invalid("audio bounds");
    if (envelope.known && (envelope.mode < 0 || envelope.ownerUid < 0))
      throw invalid("known audio");
    if (envelope.observedAtMs < 0) throw invalid("audio time");
    if (envelope.telecom == null) return;
    Proof proof = envelope.telecom;
    if (proof.packageCode < PACKAGE_UNKNOWN || proof.packageCode > PACKAGE_OTHER
        || proof.userId < -1 || proof.userId > MAX_USER_ID
        || proof.liveCalls < -1 || proof.liveCalls > MAX_CALLS
        || proof.activeCalls < -1 || proof.activeCalls > MAX_CALLS)
      throw invalid("proof bounds");
    if (proof.observedAtMs < 0) throw invalid("proof time");
    if (!proof.known) {
      if (proof.packageCode != PACKAGE_UNKNOWN || proof.userId != -1
          || proof.liveCalls != -1 || proof.activeCalls != -1
          || proof.foregroundMatched || proof.selfManaged || proof.voip)
        throw invalid("unknown proof");
      return;
    }
    if (proof.liveCalls < 0 || proof.activeCalls < 0 || proof.activeCalls > proof.liveCalls)
      throw invalid("known proof counts");
    if (proof.liveCalls == 0) {
      if (proof.packageCode != PACKAGE_UNKNOWN || proof.userId != -1
          || proof.liveCalls != 0 || proof.activeCalls != 0
          || proof.foregroundMatched || proof.selfManaged || proof.voip)
        throw invalid("empty proof");
      return;
    }
    if (proof.packageCode == PACKAGE_UNKNOWN) {
      // A coherent snapshot may retain structural flags/counts without a readable
      // account or unique app attribution. Policy must reject package 0 for START.
      if (proof.userId != -1)
        throw invalid("ambiguous proof");
    } else if (proof.userId < 0) {
      throw invalid("attributed user");
    }
  }

  private static IOException invalid(String field) {
    return new IOException("Invalid call attribution " + field);
  }
}
