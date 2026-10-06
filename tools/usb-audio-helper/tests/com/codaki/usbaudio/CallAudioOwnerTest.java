package com.codaki.usbaudio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CallAudioOwnerTest {
  private static int passed;
  private static final String SAMSUNG = "Audio mode: \n  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=42, mUid=10544\n";
  private static final String AOSP = "Audio mode: \n- Requested mode = MODE_IN_COMMUNICATION\n- Actual mode = MODE_IN_COMMUNICATION\n- Mode owner: \n  - Mode: MODE_IN_COMMUNICATION\n  - Binder: android.os.BinderProxy@0000\n  - Pid: 42\n  - Uid: 10544\n  - Package: com.whatsapp\n  - Privileged: false\n  - Active: true\n    Playback active: true\n    Recording active: true\n- Mode owner stack: \n  Requester # 1:\n  - Mode: MODE_IN_COMMUNICATION\n  - Pid: 99\n  - Uid: 11000\n";

  public static void main(String[] args) throws Exception {
    state("Samsung current owner", SAMSUNG, 3, 10544);
    state("Samsung normal owner", SAMSUNG.replace("MODE_IN_COMMUNICATION, mPid=42, mUid=10544", "MODE_NORMAL, mPid=0, mUid=0"), 0, 0);
    state("AOSP current excludes owner stack", AOSP, 3, 10544);
    state("AOSP none normal", "Audio mode:\n- Requested mode = MODE_NORMAL\n- Actual mode = MODE_NORMAL\n- Mode owner:\n   None\n- Mode owner stack:\n   Empty\n", 0, 0);
    state("CRLF current", SAMSUNG.replace("\n", "\r\n"), 3, 10544);
    state("owner line without final newline", SAMSUNG.trim(), 3, 10544);
    unknown("no current section", "Mode owner stack:\n" + SAMSUNG.substring(SAMSUNG.indexOf('\n') + 1));
    unknown("missing owner not recovered from stack", AOSP.replace("  - Uid: 10544\n", ""));
    unknown("missing owner not recovered from later section", "Audio mode:\n- Actual mode = MODE_IN_COMMUNICATION\n- Mode owner:\n  - Mode: MODE_IN_COMMUNICATION\n  - Pid: 42\nPlaybackActivityMonitor dump time:\n  - Uid: 10544\n");
    unknown("duplicate Samsung owner", SAMSUNG + SAMSUNG.substring(SAMSUNG.indexOf('\n') + 1));
    unknown("duplicate audio sections", SAMSUNG + SAMSUNG);
    unknown("duplicate AOSP UID", AOSP.replace("  - Uid: 10544\n", "  - Uid: 10544\n  - Uid: 11000\n"));
    unknown("mixed Samsung and AOSP owners", SAMSUNG + "- Mode owner:\n  - Mode: MODE_IN_COMMUNICATION\n  - Pid: 42\n  - Uid: 10544\n");
    unknown("mode transition inconsistent", AOSP.replace("- Actual mode = MODE_IN_COMMUNICATION", "- Actual mode = MODE_NORMAL"));
    unknown("requested mode inconsistent", AOSP.replace("- Requested mode = MODE_IN_COMMUNICATION", "- Requested mode = MODE_RINGTONE"));
    unknown("AOSP actual mode required", AOSP.replace("- Actual mode = MODE_IN_COMMUNICATION\n", ""));
    unknown("negative Samsung UID", SAMSUNG.replace("mUid=10544", "mUid=-1"));
    unknown("overflow Samsung UID", SAMSUNG.replace("mUid=10544", "mUid=2147483648"));
    unknown("unknown audio mode", SAMSUNG.replace("MODE_IN_COMMUNICATION", "MODE_NEW_UNKNOWN"));
    unknown("zero UID communication not coherent", SAMSUNG.replace("mUid=10544", "mUid=0"));
    unknown("zero PID communication not coherent", SAMSUNG.replace("mPid=42", "mPid=0"));
    unknown("trailing Samsung ambiguity", SAMSUNG.replace("mUid=10544", "mUid=10544, mUid=11000"));
    unknown("None inconsistent", "Audio mode:\n- Requested mode = MODE_IN_COMMUNICATION\n- Actual mode = MODE_IN_COMMUNICATION\n- Mode owner:\n  None\n");
    unknown("owner not pulled from history", "Audio mode:\n- Actual mode = MODE_IN_COMMUNICATION\n- Mode owner:\n- Mode owner stack:\n" + SAMSUNG.substring(SAMSUNG.indexOf('\n') + 1));
    unknown("NUL contamination", SAMSUNG + "\0");
    unknown("bounded total dump", SAMSUNG + repeat('x', CallAudioOwnerParser.MAX_DUMP_BYTES));
    unknown("bounded line", SAMSUNG + repeat('x', 4097));
    fragmented();
    byteLimit();
    probeSuccessful();
    probeOversized();
    probeTimeout();
    probeCancellation();
    monitorArming();
    monitorInFlightShutdown(false);
    monitorInFlightShutdown(true);
    monitorOriginalObservationTime();
    monitorRearmObservationTime();
    System.out.println("Call audio owner tests passed: " + passed);
  }

  private static void fragmented() {
    CallAudioOwnerParser parser = new CallAudioOwnerParser();
    byte[] bytes = AOSP.getBytes(StandardCharsets.UTF_8);
    for (int offset = 0; offset < bytes.length; offset += 3)
      require(parser.consume(bytes, offset, Math.min(3, bytes.length - offset)), "fragmented consume");
    checkState("fragmented current owner", parser.finish(), 3, 10544); passed++;
  }
  private static void byteLimit() {
    CallAudioOwnerParser parser = new CallAudioOwnerParser();
    byte[] line = "ignored metadata\n".getBytes(StandardCharsets.UTF_8);
    int remaining = CallAudioOwnerParser.MAX_DUMP_BYTES;
    while (remaining > 0) {
      int count = Math.min(remaining, line.length);
      require(parser.consume(line, 0, count), "exact byte limit allowed"); remaining -= count;
    }
    require(!parser.consume(new byte[] {'\n'}, 0, 1), "byte limit stops reader");
    require(!parser.finish().known, "oversize never known"); passed++;
  }

  private static void probeSuccessful() {
    FakeProcess process = new FakeProcess(SAMSUNG.getBytes(StandardCharsets.UTF_8), false);
    try (CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> process)) {
      checkState("fixed probe parses metadata", probe.sample(), 3, 10544);
    }
    require(process.destroyed, "process disposed"); passed++;
  }
  private static void probeOversized() {
    byte[] oversized = (SAMSUNG + repeat('x', CallAudioOwnerParser.MAX_DUMP_BYTES)).getBytes(StandardCharsets.UTF_8);
    FakeProcess process = new FakeProcess(oversized, false);
    try (CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> process)) {
      require(!probe.sample().known, "oversize probe unknown");
    }
    require(process.destroyed, "oversize process terminated"); passed++;
  }
  private static void probeTimeout() {
    FakeProcess process = new FakeProcess(new byte[0], true);
    long started = System.nanoTime();
    try (CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> process)) {
      require(!probe.sample().known, "timeout unknown");
    }
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    require(elapsed >= 650 && elapsed < 1500, "timeout bounded, elapsed=" + elapsed);
    require(process.destroyed, "timeout process terminated"); passed++;
  }
  private static void probeCancellation() throws Exception {
    FakeProcess process = new FakeProcess(new byte[0], true);
    CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> process);
    CountDownLatch done = new CountDownLatch(1);
    Thread thread = new Thread(() -> {
      require(!probe.sample().known, "cancelled sample unknown"); done.countDown();
    });
    thread.start();
    require(process.waiting.await(1, TimeUnit.SECONDS), "dump started");
    long started = System.nanoTime(); probe.cancel();
    require(done.await(500, TimeUnit.MILLISECONDS), "cancel stops dump promptly");
    require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500, "cancel deadline");
    require(process.destroyed, "cancel process terminated"); probe.close(); passed++;
  }
  private static void monitorArming() throws Exception {
    AtomicInteger starts = new AtomicInteger();
    CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> {
      starts.incrementAndGet(); return new FakeProcess(SAMSUNG.getBytes(StandardCharsets.UTF_8), false);
    });
    CallAudioOwnerMonitor monitor = new CallAudioOwnerMonitor(probe);
    Thread.sleep(80);
    require(starts.get() == 0 && monitor.takePending() == null, "unarmed no probe or metadata");
    monitor.enable();
    checkState("armed fresh sample", awaitSample(monitor), 3, 10544);
    int afterFirst = starts.get();
    monitor.enable(); Thread.sleep(100);
    require(starts.get() == afterFirst, "ON idempotent cadence");
    monitor.disable();
    require(monitor.takePending() == null, "OFF clears metadata");
    Thread.sleep(1100);
    require(starts.get() == afterFirst, "OFF stops further probes");
    monitor.enable(); checkState("rearmed fresh sample", awaitSample(monitor), 3, 10544);
    require(starts.get() == afterFirst + 1, "rearm exactly fresh probe");
    monitor.close();
    require(monitor.takePending() == null, "close clears metadata");
    monitor.enable(); Thread.sleep(50);
    require(starts.get() == afterFirst + 1, "closed never rearmed"); passed++;
  }
  private static CallAudioOwnerParser.State awaitSample(CallAudioOwnerMonitor monitor) throws Exception {
    return awaitSnapshot(monitor).state;
  }
  private static CallAudioOwnerMonitor.Snapshot awaitSnapshot(CallAudioOwnerMonitor monitor) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      CallAudioOwnerMonitor.Snapshot state = monitor.takePending();
      if (state != null) return state;
      Thread.sleep(10);
    }
    throw new AssertionError("monitor sample timeout");
  }

  private static void monitorOriginalObservationTime() throws Exception {
    AtomicLong clock = new AtomicLong(1000);
    CountDownLatch sampled = new CountDownLatch(1);
    CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> new FakeProcess(SAMSUNG.getBytes(StandardCharsets.UTF_8), false));
    CallAudioOwnerMonitor monitor = new CallAudioOwnerMonitor(probe, () -> {
      long observed = clock.get(); sampled.countDown(); return observed;
    });
    monitor.enable();
    require(sampled.await(1, TimeUnit.SECONDS), "probe observation timestamp captured");
    // Simulate a delayed writer without waiting for a second producer sample.
    clock.set(4500); Thread.sleep(40);
    CallAudioOwnerMonitor.Snapshot snapshot = awaitSnapshot(monitor);
    checkState("delayed snapshot numeric state", snapshot.state, 3, 10544);
    require(snapshot.observedAtMs == 1000, "draining cannot freshen old observation");
    clock.set(8000);
    require(snapshot.observedAtMs == 1000, "retained envelope immutable after later clock changes");
    monitor.close(); passed++;
  }

  private static void monitorRearmObservationTime() throws Exception {
    AtomicLong clock = new AtomicLong(1000);
    CountDownLatch sampled = new CountDownLatch(1);
    CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> new FakeProcess(SAMSUNG.getBytes(StandardCharsets.UTF_8), false));
    CallAudioOwnerMonitor monitor = new CallAudioOwnerMonitor(probe, () -> {
      long observed = clock.get(); sampled.countDown(); return observed;
    });
    monitor.enable();
    require(sampled.await(1, TimeUnit.SECONDS), "old observation produced"); Thread.sleep(40);
    monitor.disable(); clock.set(9000); monitor.enable();
    CallAudioOwnerMonitor.Snapshot snapshot = awaitSnapshot(monitor);
    require(snapshot.observedAtMs == 9000, "rearm clears old envelope and uses fresh observation");
    checkState("fresh rearm numeric state", snapshot.state, 3, 10544);
    monitor.close(); passed++;
  }

  private static void monitorInFlightShutdown(boolean permanent) throws Exception {
    AtomicInteger starts = new AtomicInteger();
    FakeProcess hanging = new FakeProcess(new byte[0], true);
    CallAudioOwnerProbe probe = new CallAudioOwnerProbe(() -> starts.incrementAndGet() == 1
        ? hanging : new FakeProcess(SAMSUNG.getBytes(StandardCharsets.UTF_8), false));
    CallAudioOwnerMonitor monitor = new CallAudioOwnerMonitor(probe);
    monitor.enable();
    require(hanging.waiting.await(1, TimeUnit.SECONDS), "armed in-flight probe");
    long started = System.nanoTime();
    if (permanent) monitor.close(); else monitor.disable();
    require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 100, "shutdown never waits for dump");
    require(hanging.destroyed && monitor.takePending() == null, "shutdown cancels probe without stale metadata");
    Thread.sleep(80);
    require(monitor.takePending() == null, "cancel result never published");
    monitor.enable();
    if (permanent) {
      Thread.sleep(80); require(starts.get() == 1, "closed in-flight monitor remains closed");
    } else {
      checkState("OFF/ON discards prior generation", awaitSample(monitor), 3, 10544);
      require(starts.get() == 2, "OFF/ON fresh process");
    }
    monitor.close(); passed++;
  }

  private static final class FakeProcess extends Process {
    final CountDownLatch waiting = new CountDownLatch(1), exit = new CountDownLatch(1);
    final InputStream input;
    final PipedOutputStream pipe;
    volatile boolean destroyed;
    FakeProcess(byte[] bytes, boolean hanging) {
      try {
        if (hanging) { PipedInputStream stream = new PipedInputStream(); pipe = new PipedOutputStream(stream); input = stream; }
        else { input = new ByteArrayInputStream(bytes); pipe = null; exit.countDown(); }
      } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override public InputStream getInputStream() { return input; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    @Override public int waitFor() throws InterruptedException { waiting.countDown(); exit.await(); return destroyed ? 1 : 0; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { waiting.countDown(); return exit.await(timeout, unit); }
    @Override public int exitValue() { if (exit.getCount() != 0) throw new IllegalThreadStateException(); return destroyed ? 1 : 0; }
    @Override public void destroy() { destroyed = true; exit.countDown(); if (pipe != null) try { pipe.close(); } catch (Exception ignored) { } }
    @Override public Process destroyForcibly() { destroy(); return this; }
  }

  private static void state(String name, String input, int mode, int uid) { checkState(name, CallAudioOwnerParser.parse(input), mode, uid); passed++; }
  private static void unknown(String name, String input) { CallAudioOwnerParser.State state = CallAudioOwnerParser.parse(input); require(!state.known && state.mode == -1 && state.ownerUid == -1, name); passed++; }
  private static void checkState(String name, CallAudioOwnerParser.State state, int mode, int uid) {
    require(state != null && state.known && state.mode == mode && state.ownerUid == uid, name);
  }
  private static String repeat(char value, int count) { char[] result = new char[count]; java.util.Arrays.fill(result, value); return new String(result); }
  private static void require(boolean truth, String message) { if (!truth) throw new AssertionError(message); }
}
