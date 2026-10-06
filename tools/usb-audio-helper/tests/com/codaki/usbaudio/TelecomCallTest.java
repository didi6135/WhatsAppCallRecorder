package com.codaki.usbaudio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic metadata only. No real identifiers, account details, or device access. */
public final class TelecomCallTest {
  private static int passed;
  private static final String WA = row("TC@1", "ACTIVE", "com.whatsapp", 0, "[ self_mng]", true);
  private static final String BUSINESS = row("TC@2", "ACTIVE", "com.whatsapp.w4b", 10, "[ self_mng]", true);
  private static final String OTHER = row("TC@3", "ACTIVE", "org.example.other", 0, "[]", false);
  private static final String VALID = dump(WA, WA);
  private static final String SAMSUNG_HEADER = "telecom: samsung/synthetic-product/synthetic-device:16/SYNTHETIC.1.2/SYNTHETIC:user/release-keys, 16, /system/priv-app/Telecom, 123[ SYSTEM ], 456, 789\n";

  public static void main(String[] args) throws Exception {
    state("unique WhatsApp", VALID, 1, 0, 1, 1, true, true, true);
    state("Business user 10", dump(BUSINESS, BUSINESS), 2, 10, 1, 1, true, true, true);
    state("largest supported Android user", VALID.replace("UserHandle{0}", "UserHandle{21474}"), 1, 21474, 1, 1, true, true, true);
    state("other app remains counted", dump(OTHER, OTHER), 3, 0, 1, 1, true, false, false);
    state("empty current set", dump("", "null"), 0, -1, 0, 0, false, false, false);
    state("empty CRLF", dump("", "null").replace("\n", "\r\n"), 0, -1, 0, 0, false, false, false);
    state("CRLF active", VALID.replace("\n", "\r\n"), 1, 0, 1, 1, true, true, true);
    state("terminal section without final newline", VALID.trim(), 1, 0, 1, 1, true, true, true);
    state("verified Samsung wrapper preamble", SAMSUNG_HEADER + VALID, 1, 0, 1, 1, true, true, true);
    state("Samsung preamble with empty current set", SAMSUNG_HEADER + dump("", "null"), 0, -1, 0, 0, false, false, false);
    state("inactive foreground absent", dump(WA.replace("state=ACTIVE", "state=ON_HOLD"), "null"), 1, 0, 1, 0, false, true, true);
    state("two active apps", dump(WA + "\n" + OTHER, WA), 0, -1, 2, 2, true, false, false);
    state("active plus held app", dump(WA + "\n" + OTHER.replace("state=ACTIVE", "state=ON_HOLD"), WA), 0, -1, 2, 1, true, false, false);
    state("non self managed", dump(WA.replace("[ self_mng]", "[ HD]"), WA.replace("[ self_mng]", "[ HD]")), 1, 0, 1, 1, true, false, true);
    state("non VoIP", dump(WA.replace("voip=true", "voip=false"), WA.replace("voip=true", "voip=false")), 1, 0, 1, 1, true, true, false);
    state("null foreground cannot authorize active", dump(WA, "null"), 1, 0, 1, 1, false, true, true);
    state("properties parsed as exact tokens", dump(WA.replace("[ self_mng]", "[ self_mng HD wifi]"), WA.replace("[ self_mng]", "[ self_mng HD wifi]")), 1, 0, 1, 1, true, true, true);
    state("null account cannot attribute", dump(WA.replace(account("com.whatsapp", 0), "null"), WA.replace(account("com.whatsapp", 0), "null")), 0, -1, 1, 1, true, true, true);
    for (String callState : new String[] {"NEW", "CONNECTING", "SELECT_PHONE_ACCOUNT", "DIALING", "RINGING", "ON_HOLD", "DISCONNECTING", "PULLING", "ANSWERED", "AUDIO_PROCESSING", "SIMULATED_RINGING"}) {
      String inactive = WA.replace("state=ACTIVE", "state=" + callState);
      state("non active current state", dump(inactive, inactive), 1, 0, 1, 0, true, true, true);
    }
    for (String callState : new String[] {"DISCONNECTED", "ABORTED"})
      state("terminal current rows are not live", dump(WA.replace("state=ACTIVE", "state=" + callState), "null"), 0, -1, 0, 0, false, false, false);

    unknown("null input", null);
    unknown("no manager", VALID.replace("CallsManager:\n", ""));
    unknown("unrecognized preamble", "Events:\n" + VALID);
    unknown("Samsung preamble only", SAMSUNG_HEADER);
    unknown("duplicate Samsung preamble", SAMSUNG_HEADER + SAMSUNG_HEADER + VALID);
    unknown("unknown platform preamble", SAMSUNG_HEADER.replace("samsung/", "unknown/") + VALID);
    unknown("unknown Samsung release field", SAMSUNG_HEADER.replace(", 16,", ", 17,") + VALID);
    unknown("unknown Samsung Android version", SAMSUNG_HEADER.replace(":16/", ":17/") + VALID);
    unknown("unknown service path", SAMSUNG_HEADER.replace("/system/priv-app/Telecom", "/data/app/Telecom") + VALID);
    unknown("oversize Samsung field", SAMSUNG_HEADER.replace("synthetic-product", repeat('x', 65)) + VALID);
    unknown("Samsung preamble cannot follow history", "Events:\n" + SAMSUNG_HEADER + VALID);
    unknown("history cannot supply current set", "Events:\n  " + WA + "\n" + VALID);
    unknown("missing current set", VALID.replace("  mCalls:\n", ""));
    unknown("duplicate current row", dump(WA + "\n" + WA, WA));
    unknown("same ID across packages", dump(WA + "\n" + OTHER.replace("TC@3", "TC@1"), WA));
    unknown("duplicate current header", VALID.replace("  mCallAudioManager:", "  mCalls:\n  mCallAudioManager:"));
    unknown("duplicate manager", VALID.replace("  mCallAudioManager:", "CallsManager:\n  mCallAudioManager:"));
    unknown("missing foreground label", VALID.replace("    Foreground call:\n", ""));
    unknown("missing foreground row", VALID.replace("    " + WA + "\n    CallAudioModeStateMachine:", "    CallAudioModeStateMachine:"));
    unknown("duplicate foreground", VALID.replace("    CallAudioModeStateMachine:", "    Foreground call:\n    " + WA + "\n    CallAudioModeStateMachine:"));
    unknown("inline foreground not recognized", VALID.replace("    Foreground call:\n    ", "    Foreground call: "));
    unknown("unrecognized audio structure", VALID.replace("    Ringing calls:", "    New call collection:"));
    unknown("pending call ambiguity", VALID.replace("  mCallAudioManager:", "  mPendingCall:TC@pending\n  mCallAudioManager:"));
    unknown("missing state machine boundary", VALID.replace("    CallAudioModeStateMachine:\n", ""));
    unknown("other manager section not a boundary", VALID.replace("    CallAudioModeStateMachine:", "  mTtyManager:"));
    unknown("misindented current row", VALID.replace("    " + WA + "\n  mCallAudioManager:", "  " + WA + "\n  mCallAudioManager:"));
    unknown("misindented foreground", VALID.replace("    Foreground call:", "      Foreground call:"));
    unknown("foreground ID mismatch", dump(WA, WA.replace("TC@1", "TC@9")));
    unknown("foreground package mismatch", dump(WA, WA.replace("com.whatsapp/", "com.whatsapp.w4b/")));
    unknown("foreground user mismatch", dump(WA, WA.replace("UserHandle{0}", "UserHandle{10}")));
    unknown("foreground state mismatch", dump(WA, WA.replace("state=ACTIVE", "state=ON_HOLD")));
    unknown("foreground properties mismatch", dump(WA, WA.replace("[ self_mng]", "[]")));
    unknown("foreground VoIP mismatch", dump(WA, WA.replace("voip=true", "voip=false")));
    unknown("empty set with historical foreground", dump("", WA));
    unknown("terminal set with foreground is not empty proof", dump(WA.replace("state=ACTIVE", "state=DISCONNECTED"), WA.replace("state=ACTIVE", "state=DISCONNECTED")));
    unknown("unknown state", VALID.replace("state=ACTIVE", "state=FUTURE_STATE"));
    unknown("unknown properties", VALID.replace("[ self_mng]", "[ self_mng future_prop]"));
    unknown("partial marker cannot become self managed", VALID.replace("[ self_mng]", "[ not_self_mng]"));
    unknown("unknown video descriptor", VALID.replace("vidst=A", "vidst=VIDEO"));
    unknown("overflow user", VALID.replace("UserHandle{0}", "UserHandle{2147483648}"));
    unknown("unsupported Android user fails closed", VALID.replace("UserHandle{0}", "UserHandle{21475}"));
    unknown("negative user", VALID.replace("UserHandle{0}", "UserHandle{-1}"));
    unknown("malformed component", VALID.replace("ComponentInfo{com.whatsapp/", "ComponentInfo{com.whatsapp"));
    unknown("duplicate scalar", VALID.replace("state=ACTIVE", "state=ACTIVE, state=ON_HOLD"));
    unknown("trailing ambiguous data", dump(WA + " extra", WA));
    unknown("oversize ID", VALID.replace("TC@1", repeat('x', 129)));
    unknown("oversize account", VALID.replace("synthetic-account", repeat('x', 257)));
    unknown("oversize handle", VALID.replace("synthetic-handle", repeat('x', 257)));
    unknown("NUL contamination", VALID + "\0");
    unknown("bounded line", VALID + repeat('x', TelecomCallParser.MAX_LINE_BYTES + 1));
    unknown("bounded bytes", VALID + repeat('x', TelecomCallParser.MAX_DUMP_BYTES));
    unknown("bounded lines", VALID + repeatLines(TelecomCallParser.MAX_DUMP_LINES));
    String manyRows = "";
    for (int i = 0; i <= TelecomCallParser.MAX_CURRENT_CALLS; i++) manyRows += WA.replace("TC@1", "TC@" + i) + "\n";
    unknown("bounded current rows", dump(manyRows.trim(), "null"));
    state("lookalike package is OTHER", dump(WA.replace("com.whatsapp/", "com.whatsapp.fake/"), WA.replace("com.whatsapp/", "com.whatsapp.fake/")), 3, 0, 1, 1, true, true, true);
    state("later history cannot supply or override attribution", VALID + "  mTtyManager:\nEvents:\nCallsManager:\n  mCalls:\n    " + OTHER + "\nForeground call:\n" + OTHER + "\n", 1, 0, 1, 1, true, true, true);
    state("audio collections are not current rows", VALID.replace("    All calls:\n", "    All calls:\n      TC@history\n").replace("    Holding calls:\n", "    Holding calls:\n      TC@held\n"), 1, 0, 1, 1, true, true, true);
    state("verified Samsung optional audio collection", VALID.replace("    Foreground call:\n", "    AudioProcessing calls:\n      TC@processing\n    Foreground call:\n"), 1, 0, 1, 1, true, true, true);
    state("Samsung audio collection cannot invent a live call", dump("", "null").replace("    Foreground call:\n", "    AudioProcessing calls:\n      TC@processing\n    Foreground call:\n"), 0, -1, 0, 0, false, false, false);
    unknown("duplicate optional Samsung collection", VALID.replace("    Foreground call:\n", "    AudioProcessing calls:\n    AudioProcessing calls:\n    Foreground call:\n"));
    unknown("reordered optional Samsung collection", VALID.replace("    Holding calls:\n", "    AudioProcessing calls:\n    Holding calls:\n"));
    unknown("optional collection after foreground", VALID.replace("    CallAudioModeStateMachine:\n", "    AudioProcessing calls:\n    CallAudioModeStateMachine:\n"));
    fragmented();
    partials();
    immutablePublicState();
    probeSuccess();
    probeFailureStatus();
    probePartial();
    probeOversize();
    probeTimeout();
    probeCompletePrefixStillHanging();
    probeSlowStartup();
    probeStalledStartupBoundedWorkers();
    probeCancel(false);
    probeCancel(true);
    probeConcurrent();
    probeInterrupted();
    probeRecovery();
    diagnosticCodes();
    diagnosticRows();
    diagnosticRowCharacterScopes();
    samsungCurrentTail();
    versionBoundGrammar();
    System.out.println("Telecom call tests passed: " + passed);
  }

  private static void versionBoundGrammar() {
    // AOSP 14/15/16 use the same current Call.toString and dump boundary. OEM support
    // remains a candidate: only this strict wrapper/row structure is recognized.
    for (int sdk : new int[] {34, 35, 36}) {
      String release = Integer.toString(sdk - 20);
      String wrapper = SAMSUNG_HEADER.replace(":16/", ":" + release + "/").replace(", 16,", ", " + release + ",");
      checkState("version AOSP current call", TelecomCallParser.parse(VALID, sdk), 1, 0, 1, 1, true, true, true);
      checkState("version Business", TelecomCallParser.parse(dump(BUSINESS, BUSINESS), sdk), 2, 10, 1, 1, true, true, true);
      checkState("version empty", TelecomCallParser.parse(dump("", "null"), sdk), 0, -1, 0, 0, false, false, false);
      checkState("release bound wrapper", TelecomCallParser.parse(wrapper + VALID, sdk), 1, 0, 1, 1, true, true, true);
      for (int other : new int[] {34, 35, 36}) if (other != sdk) {
        require(!TelecomCallParser.parse(wrapper + VALID, other).known, "different device release rejected"); passed++;
      }
      require(!TelecomCallParser.parse(wrapper.replace(", " + release + ",", ", 13,") + VALID, sdk).known, "wrapper release fields must agree"); passed++;
      require(!TelecomCallParser.parse(VALID.replace("state=ACTIVE", "state=FUTURE"), sdk).known, "candidate still rejects unknown state"); passed++;
      try (TelecomCallProbe probe = new TelecomCallProbe(sdk, () -> new FakeProcess(wrapper + VALID, false, 0))) {
        checkState("probe receives actual SDK", probe.sample(), 1, 0, 1, 1, true, true, true);
      }
    }
    require(!TelecomCallParser.parse(VALID, 33).known, "unsupported SDK cannot supply proof"); passed++;
    require(!TelecomCallParser.parse(SAMSUNG_HEADER + VALID, 37).known, "unknown OEM release not borrowed from16"); passed++;
  }

  private static void fragmented() {
    byte[] bytes = VALID.getBytes(StandardCharsets.UTF_8);
    for (int size : new int[] {1, 3, 7, 8192}) {
      TelecomCallParser parser = new TelecomCallParser();
      for (int i = 0; i < bytes.length; i += size)
        require(parser.consume(bytes, i, Math.min(size, bytes.length - i)), "fragmented consume");
      checkState("fragmented active row", parser.finish(), 1, 0, 1, 1, true, true, true);
      require(parser.finish().known, "finish idempotent");
      require(!parser.consume(new byte[] {'x'}, 0, 1), "finished parser cannot be extended");
      require(!parser.finish().known, "post finish input cannot preserve proof");
    }
    passed++;
  }
  private static void partials() {
    int completeBoundary = VALID.indexOf("CallAudioModeStateMachine:") + "CallAudioModeStateMachine:".length();
    for (int i = 0; i < completeBoundary; i++) require(!TelecomCallParser.parse(VALID.substring(0, i)).known, "truncated current proof rejected");
    passed++;
  }
  private static void immutablePublicState() {
    for (Field field : TelecomCallParser.State.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      require(field.getType().isPrimitive() && Modifier.isFinal(field.getModifiers()), "state retains only immutable primitives");
    }
    passed++;
  }
  private static void probeSuccess() {
    FakeProcess process = new FakeProcess(VALID, false, 0);
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> process)) {
      checkState("successful metadata probe", probe.sample(), 1, 0, 1, 1, true, true, true);
    }
    require(process.destroyed, "process disposed"); passed++;
  }
  private static void probeFailureStatus() {
    FakeProcess process = new FakeProcess(VALID, false, 7);
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> process)) {
      require(!probe.sample().known, "nonzero process never supplies proof");
    }
    require(process.destroyed, "failed process disposed"); passed++;
  }
  private static void probePartial() {
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> new FakeProcess(VALID.substring(0, VALID.indexOf("    Foreground call:")), false, 0))) {
      require(!probe.sample().known, "successful process with partial dump unknown");
    }
    passed++;
  }
  private static void probeOversize() {
    FakeProcess process = new FakeProcess(VALID + repeat('x', TelecomCallParser.MAX_LINE_BYTES + 1), false, 0);
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> process)) {
      require(!probe.sample().known, "oversize probe rejected");
    }
    require(process.destroyed, "oversize process disposed"); passed++;
  }
  private static void probeTimeout() {
    FakeProcess process = new FakeProcess("", true, 0);
    long started = System.nanoTime();
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> process)) {
      require(!probe.sample().known, "hanging dump unknown");
    }
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    require(elapsed >= 650 && elapsed < 1500, "probe deadline bounded");
    require(process.destroyed, "timeout terminates process"); passed++;
  }
  private static void probeSlowStartup() throws Exception {
    FakeProcess process = new FakeProcess(VALID, false, 0);
    CountDownLatch late = new CountDownLatch(1);
    long started = System.nanoTime();
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> { Thread.sleep(1000); late.countDown(); return process; })) {
      require(!probe.sample().known, "late startup cannot produce fresh proof");
    }
    require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 950, "startup deadline bounded");
    require(late.await(1, TimeUnit.SECONDS), "late startup returns");
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
    while (!process.destroyed && System.nanoTime() < deadline) Thread.sleep(5);
    require(process.destroyed, "late process disposed after cancellation"); passed++;
  }
  private static void probeCompletePrefixStillHanging() {
    FakeProcess process = new FakeProcess(VALID, true, 0);
    try (TelecomCallProbe probe = new TelecomCallProbe(() -> process)) {
      require(!probe.sample().known, "complete section without EOF and successful process cannot authorize");
    }
    require(process.destroyed, "hanging complete prefix terminated"); passed++;
  }
  private static void probeStalledStartupBoundedWorkers() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
    FakeProcess process = new FakeProcess(VALID, false, 0);
    TelecomCallProbe probe = new TelecomCallProbe(() -> {
      starts.incrementAndGet(); release.await(); returned.countDown(); return process;
    });
    try {
      require(!probe.sample().known, "stalled startup deadline returns unknown");
      for (int i = 0; i < 10; i++) {
        probe.cancel(); require(!probe.sample().known, "stalled worker owns bounded slot");
      }
      require(starts.get() == 1, "repeated timeouts cannot accumulate startup workers");
      probe.close(); require(!probe.sample().known, "closed stalled probe cannot start another worker");
    } finally { release.countDown(); probe.close(); }
    require(returned.await(1, TimeUnit.SECONDS), "stalled startup eventually released");
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
    while (!process.destroyed && System.nanoTime() < deadline) Thread.sleep(5);
    require(process.destroyed, "late stalled process cannot survive close"); passed++;
  }
  private static void probeCancel(boolean permanent) throws Exception {
    FakeProcess process = new FakeProcess("", true, 0);
    AtomicInteger starts = new AtomicInteger();
    TelecomCallProbe probe = new TelecomCallProbe(() -> { starts.incrementAndGet(); return process; });
    AtomicReference<TelecomCallParser.State> result = new AtomicReference<>();
    Thread caller = new Thread(() -> result.set(probe.sample())); caller.start();
    require(process.reading.await(1, TimeUnit.SECONDS), "metadata read began");
    long started = System.nanoTime();
    if (permanent) probe.close(); else probe.cancel();
    require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 100, "cancel does not await worker");
    caller.join(500);
    require(!caller.isAlive() && result.get() != null && !result.get().known, "cancelled sample promptly returns unknown");
    require(process.destroyed, "cancel terminates process");
    if (permanent) { require(!probe.sample().known && starts.get() == 1, "closed probe never starts"); }
    probe.close(); passed++;
  }
  private static void probeConcurrent() throws Exception {
    FakeProcess process = new FakeProcess("", true, 0);
    AtomicInteger starts = new AtomicInteger();
    TelecomCallProbe probe = new TelecomCallProbe(() -> { starts.incrementAndGet(); return process; });
    Thread caller = new Thread(probe::sample); caller.start();
    require(process.reading.await(1, TimeUnit.SECONDS), "first sample owns process");
    require(!probe.sample().known && starts.get() == 1, "concurrent sample cannot create another process");
    probe.close(); caller.join(500); require(!caller.isAlive(), "concurrent probe cleaned up"); passed++;
  }
  private static void probeInterrupted() throws Exception {
    FakeProcess process = new FakeProcess("", true, 0);
    TelecomCallProbe probe = new TelecomCallProbe(() -> process);
    AtomicReference<TelecomCallParser.State> result = new AtomicReference<>();
    AtomicInteger retained = new AtomicInteger();
    Thread caller = new Thread(() -> { result.set(probe.sample()); if (Thread.currentThread().isInterrupted()) retained.incrementAndGet(); });
    caller.start(); require(process.reading.await(1, TimeUnit.SECONDS), "interrupt test reading"); caller.interrupt(); caller.join(500);
    require(!caller.isAlive() && result.get() != null && !result.get().known && retained.get() == 1, "interrupted caller retains interruption and no proof");
    require(process.destroyed, "interrupted process disposed"); probe.close(); passed++;
  }
  private static void probeRecovery() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    FakeProcess hanging = new FakeProcess("", true, 0);
    TelecomCallProbe probe = new TelecomCallProbe(() -> starts.incrementAndGet() == 1
        ? hanging : new FakeProcess(VALID, false, 0));
    AtomicReference<TelecomCallParser.State> old = new AtomicReference<>();
    Thread caller = new Thread(() -> old.set(probe.sample())); caller.start();
    require(hanging.reading.await(1, TimeUnit.SECONDS), "old generation reading");
    probe.cancel();
    caller.join(500);
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
    TelecomCallParser.State fresh;
    do { fresh = probe.sample(); if (!fresh.known) Thread.sleep(5); }
    while (!fresh.known && System.nanoTime() < deadline);
    checkState("fresh process after cancellation cleanup", fresh, 1, 0, 1, 1, true, true, true);
    require(!caller.isAlive() && old.get() != null && !old.get().known && starts.get() == 2, "old generation cannot publish fresh proof");
    probe.close(); passed++;
  }
  private static void diagnosticCodes() {
    for (int expected : new int[] {0, 10, 11, 12, 13, 100, 103, 200, 201}) {
      String input = VALID;
      if (expected == 10) input += repeat('x', TelecomCallParser.MAX_DUMP_BYTES);
      if (expected == 11) input += repeatLines(TelecomCallParser.MAX_DUMP_LINES);
      if (expected == 12) input += repeat('x', TelecomCallParser.MAX_LINE_BYTES + 1);
      if (expected == 13) input += "\0";
      if (expected == 100) input = "History:\n" + input;
      if (expected == 103) input = input.replace("    Ringing calls:", "    Unknown calls:");
      if (expected == 200) input = dump(WA, WA.replace("TC@1", "TC@9"));
      if (expected == 201) input = input.substring(0, input.indexOf("    Foreground call:"));
      TelecomCallParser parser = new TelecomCallParser();
      byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
      parser.consume(bytes, 0, bytes.length); parser.finish();
      require(parser.diagnosticFailureCode() == expected, "fixed diagnostic code without text");
    }
    passed++;
  }
  private static void diagnosticRows() {
    require(TelecomCallParser.diagnosticRowCode(WA) == 0, "known row diagnostic");
    require(TelecomCallParser.diagnosticRowCode(WA.replace("voip=true", "voip=false")) == 0, "false VoIP remains recognized metadata");
    require(TelecomCallParser.diagnosticRowCode(WA + ", unknown_extra=true") == 2, "unknown extra suffix remains rejected");
    require(TelecomCallParser.diagnosticRowTailFlags(WA) == (1 | 2 | 8 | 16), "true suffix fixed flags");
    require(TelecomCallParser.diagnosticRowTailFlags(WA.replace("voip=true", "voip=false")) == (1 | 4 | 8 | 16), "false suffix fixed flags");
    require(TelecomCallParser.diagnosticRowTailFlags(WA + ", unknown_extra=true") == (1 | 2 | 8 | 32), "diagnostic extra suffix never permits full grammar");
    require(TelecomCallParser.diagnosticRowTailFlags(WA.replace("vidst=A", "vidst=UNKNOWN")) == (1 | 2), "positive bool diagnostic despite failed body cannot authorize");
    require(TelecomCallParser.diagnosticRowTailFlags(WA.replace("voip=true", "voip=UNKNOWN")) == (1 | 8), "unknown VoIP bool never guesses");
    require(TelecomCallParser.diagnosticRowTailFlags(WA + ", voip=false") == (8 | 32), "duplicate VoIP marker does not guess bool");
    require(TelecomCallParser.diagnosticRowTailFlags(null) == 0, "no diagnostic text for null");
    TelecomCallParser parser = new TelecomCallParser();
    byte[] bytes = dump(WA + ", unknown_extra=true", WA).getBytes(StandardCharsets.UTF_8);
    require(!parser.consume(bytes, 0, bytes.length) && !parser.finish().known, "unknown row fails parsing");
    require(parser.diagnosticFailureCode() == 102 && parser.diagnosticRowFailureCode() == 2
        && parser.diagnosticRowTailFlags() == (1 | 2 | 8 | 32), "failed-row diagnostic retains only fixed ints");
    for (Field field : TelecomCallParser.class.getDeclaredFields())
      if (field.getName().equals("rowFailureCode") || field.getName().equals("rowTailFlags"))
        require(field.getType() == int.class, "diagnostics retain no failed-row strings");
    passed++;
  }
  private static void diagnosticRowCharacterScopes() {
    String opaque = WA.replace("synthetic-account", "synthetic-\u2022-account").replace("synthetic-handle", "synthetic-\u2022-handle");
    require(TelecomCallParser.diagnosticRowCharFlags(opaque) == (1 | 2 | 8), "opaque character scopes fixed bits");
    require(TelecomCallParser.diagnosticRowTailFlags(opaque) == (1 | 2 | 8 | 16), "opaque Unicode preserves diagnostic true bool");
    require(TelecomCallParser.diagnosticRowTailFlags(opaque.replace("voip=true", "voip=false")) == (1 | 4 | 8 | 16), "opaque Unicode preserves diagnostic false bool");
    require(TelecomCallParser.diagnosticRowCode(opaque) == 1, "opaque Unicode still fails parser acceptance");
    require(TelecomCallParser.diagnosticRowCharFlags(WA.replace("com.whatsapp/", "com.whats\u2022app/")) == (1 | 16), "Unicode package is critical");
    require(TelecomCallParser.diagnosticRowCharFlags(WA.replace("[ self_mng]", "[ self_\u2022mng]")) == (1 | 16), "Unicode property is critical");
    require(TelecomCallParser.diagnosticRowTailFlags(WA.replace("[ self_mng]", "[ self_\u2022mng]")) == 0, "critical Unicode cannot provide bool diagnostic");
    require(TelecomCallParser.diagnosticRowCharFlags(opaque.replace("synthetic-\u2022-handle", "synthetic-\u2022, state=ACTIVE")) == (1 | 16 | 32), "duplicate delimiter fails scope ambiguity");
    require(TelecomCallParser.diagnosticRowCharFlags(WA + "\0") == 64, "ASCII control diagnostic");
    require(TelecomCallParser.diagnosticRowCharFlags(WA + "\u007f") == 64, "DEL is ASCII control without nonASCII scope");
    TelecomCallParser parser = new TelecomCallParser();
    byte[] bytes = dump(opaque, opaque).getBytes(StandardCharsets.UTF_8);
    require(!parser.consume(bytes, 0, bytes.length) && !parser.finish().known, "UTF8 opaque data remains rejected");
    require(parser.diagnosticRowFailureCode() == 1 && parser.diagnosticRowCharFlags() == (1 | 2 | 8)
        && parser.diagnosticRowTailFlags() == (1 | 2 | 8 | 16), "failed UTF8 row retains only character scope bits");
    passed++;
  }
  private static void samsungCurrentTail() {
    String samsung = samsungRow(WA), business = samsungRow(BUSINESS);
    state("verified Samsung current WhatsApp tail", dump(samsung, samsung), 1, 0, 1, 1, true, true, true);
    state("verified Samsung Business tail", dump(business, business), 2, 10, 1, 1, true, true, true);
    state("Samsung false VoIP stays false", dump(samsung.replace("voip=true", "voip=false"), samsung.replace("voip=true", "voip=false")), 1, 0, 1, 1, true, true, false);
    unknown("Samsung duplicate long property", dump(samsung.replace("PROPERTY_SELF_MANAGED]", "PROPERTY_SELF_MANAGED PROPERTY_SELF_MANAGED]"), samsung));
    unknown("Samsung missing long self managed", dump(samsung.replace("Properties: PROPERTY_SELF_MANAGED", "Properties:"), samsung));
    unknown("Samsung mismatched short self managed", dump(samsung.replace("prop=[ self_mng]", "prop=[]"), samsung));
    unknown("Samsung duplicate long capability", dump(samsung.replace("CAPABILITY_HOLD CAPABILITY_SUPPORT_HOLD", "CAPABILITY_HOLD CAPABILITY_HOLD"), samsung));
    unknown("Samsung mismatched capabilities", dump(samsung.replace("CAPABILITY_SUPPORT_HOLD", "CAPABILITY_MUTE"), samsung));
    unknown("Samsung unknown long property", dump(samsung.replace("PROPERTY_SELF_MANAGED", "PROPERTY_FUTURE"), samsung));
    unknown("Samsung unknown long capability", dump(samsung.replace("CAPABILITY_HOLD", "CAPABILITY_FUTURE"), samsung));
    unknown("Samsung unknown suffix label", dump(samsung.replace("had_child(false)", "has_child(false)"), samsung));
    unknown("Samsung trailing data", dump(samsung + ", extra=false", samsung));
    unknown("Samsung missing critical suffix", dump(samsung.replace(", had_child(false)", ""), samsung));
    unknown("Samsung reordered suffix", dump(samsung.replace("conf(false), had_child(false)", "had_child(false), conf(false)"), samsung));
    unknown("Samsung non boolean suffix", dump(samsung.replace("had_child(false)", "had_child(0)"), samsung));
    unknown("Samsung unverified separator", dump(samsung.replace("truecaps=", "true caps="), samsung));
    unknown("Samsung reordered canonical tokens", dump(samsung.replace("CAPABILITY_HOLD CAPABILITY_SUPPORT_HOLD", "CAPABILITY_SUPPORT_HOLD CAPABILITY_HOLD"), samsung));
    require(TelecomCallParser.diagnosticRowCode(samsung) == 0, "Samsung recognized diagnostic grammar");
    passed++;
  }
  private static String samsungRow(String row) {
    return row.replace("cap=[ sup_hld mut]", "cap=[ hld sup_hld]")
        + "caps=[Capabilities: CAPABILITY_HOLD CAPABILITY_SUPPORT_HOLD], props=[Properties: PROPERTY_SELF_MANAGED], conf(false), had_child(false)";
  }

  private static final class FakeProcess extends Process {
    final CountDownLatch exit = new CountDownLatch(1), reading = new CountDownLatch(1);
    final InputStream input;
    final PipedOutputStream pipe;
    final int status;
    volatile boolean destroyed;
    FakeProcess(String dump, boolean hanging, int status) {
      this.status = status;
      try {
        final InputStream source;
        if (hanging) {
          PipedInputStream stream = new PipedInputStream(16384); pipe = new PipedOutputStream(stream);
          pipe.write(dump.getBytes(StandardCharsets.UTF_8)); source = stream;
        }
        else { source = new ByteArrayInputStream(dump.getBytes(StandardCharsets.UTF_8)); pipe = null; exit.countDown(); }
        input = new InputStream() {
          @Override public int read() throws java.io.IOException { reading.countDown(); return source.read(); }
          @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException { reading.countDown(); return source.read(bytes, offset, length); }
          @Override public void close() throws java.io.IOException { source.close(); }
        };
      } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override public InputStream getInputStream() { return input; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    @Override public int waitFor() throws InterruptedException { exit.await(); return exitValue(); }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return exit.await(timeout, unit); }
    @Override public int exitValue() { if (exit.getCount() != 0) throw new IllegalThreadStateException(); return destroyed ? 1 : status; }
    @Override public void destroy() { destroyed = true; exit.countDown(); if (pipe != null) try { pipe.close(); } catch (Exception ignored) { } }
    @Override public Process destroyForcibly() { destroy(); return this; }
  }
  private static String account(String pkg, int user) { return "ComponentInfo{" + pkg + "/org.example.SyntheticService}, synthetic-account, UserHandle{" + user + "}"; }
  private static String row(String id, String state, String pkg, int user, String prop, boolean voip) {
    return "[Call id=" + id + ", state=" + state + ", tpac=" + account(pkg, user) + ", cmgr=null, handle=synthetic-handle, vidst=A, childs(0), has_parent(false), cap=[ sup_hld mut], prop=" + prop + "], voip=" + voip;
  }
  private static String dump(String rows, String foreground) {
    return "CallsManager:\n  mCalls:\n" + (rows.isEmpty() ? "" : "    " + rows.replace("\n", "\n    ") + "\n") + "  mCallAudioManager:\n    All calls:\n    Active dialing, or connecting calls:\n    Ringing calls:\n    Holding calls:\n    Foreground call:\n    " + foreground + "\n    CallAudioModeStateMachine:\n";
  }
  private static void state(String name, String input, int pkg, int user, int live, int active, boolean fg, boolean self, boolean voip) {
    checkState(name, TelecomCallParser.parse(input), pkg, user, live, active, fg, self, voip); passed++;
  }
  private static void checkState(String name, TelecomCallParser.State value, int pkg, int user, int live, int active, boolean fg, boolean self, boolean voip) {
    require(value != null && value.known && value.packageCode == pkg && value.userId == user && value.liveCalls == live && value.activeCalls == active && value.foregroundMatched == fg && value.selfManaged == self && value.voip == voip, name);
  }
  private static void unknown(String name, String input) {
    TelecomCallParser.State value = TelecomCallParser.parse(input);
    require(!value.known && value.packageCode == 0 && value.userId == -1 && value.liveCalls == -1 && value.activeCalls == -1 && !value.foregroundMatched && !value.selfManaged && !value.voip, name); passed++;
  }
  private static String repeat(char value, int count) { char[] bytes = new char[count]; java.util.Arrays.fill(bytes, value); return new String(bytes); }
  private static String repeatLines(int count) { StringBuilder lines = new StringBuilder(); for (int i = 0; i < count; i++) lines.append('\n'); return lines.toString(); }
  private static void require(boolean truth, String name) { if (!truth) throw new AssertionError(name); }
}
