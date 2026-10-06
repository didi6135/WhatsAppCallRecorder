package com.didi4164.WhatsAppCallRecorder;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class BootstrapHandoffSignalTest {
  @Test public void acceptsHandoffAcrossArbitraryPipeReads() {
    BootstrapHandoffSignal signal = new BootstrapHandoffSignal();
    feed(signal, "BOOTSTRAP_UID=2000\nBOOTSTRAP_DET");
    feed(signal, "ACHED_HANDOFF_OK");
    assertFalse(signal.isHandedOff());
    feed(signal, "\n");
    assertTrue(signal.isHandedOff());
  }

  @Test public void doesNotAcceptAnErrorMentioningTheMarker() {
    BootstrapHandoffSignal signal = new BootstrapHandoffSignal();
    feed(signal, "BOOTSTRAP_FAILED expected BOOTSTRAP_DETACHED_HANDOFF_OK\n");
    assertFalse(signal.isHandedOff());
  }

  @Test public void doesNotJoinCharactersAcrossAnEmbeddedCarriageReturn() {
    BootstrapHandoffSignal signal = new BootstrapHandoffSignal();
    feed(signal, "BOOTSTRAP_DETACHED\r_HANDOFF_OK\n");
    assertFalse(signal.isHandedOff());
  }

  @Test public void discardsOversizedLinesWithoutLosingTheNextCompleteSignal() {
    BootstrapHandoffSignal signal = new BootstrapHandoffSignal();
    byte[] noise = new byte[2000];
    java.util.Arrays.fill(noise, (byte) 'x');
    signal.accept(noise, 0, noise.length);
    feed(signal, "BOOTSTRAP_DETACHED_HANDOFF_OK\n");
    assertFalse(signal.isHandedOff());
    feed(signal, "BOOTSTRAP_DETACHED_HANDOFF_OK\r\n");
    assertTrue(signal.isHandedOff());
  }

  @Test public void processesOnlyTheSuppliedSlice() {
    BootstrapHandoffSignal signal = new BootstrapHandoffSignal();
    byte[] bytes = "xBOOTSTRAP_DETACHED_HANDOFF_OK\ny".getBytes(StandardCharsets.US_ASCII);
    signal.accept(bytes, 1, bytes.length - 2);
    assertTrue(signal.isHandedOff());
  }

  private static void feed(BootstrapHandoffSignal signal, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    signal.accept(bytes, 0, bytes.length);
  }
}
