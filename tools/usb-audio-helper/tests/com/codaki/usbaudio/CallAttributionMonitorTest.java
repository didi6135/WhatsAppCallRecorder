package com.codaki.usbaudio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CallAttributionMonitorTest {
  private static int passed;
  private static final String ACTIVE = "[Call id=TC@synthetic, state=ACTIVE, tpac=ComponentInfo{com.whatsapp/Test}, synthetic-account, UserHandle{0}, cmgr=null, handle=tel:***, vidst=A, childs(0), has_parent(false), cap=[], prop=[ self_mng]], voip=true";
  public static void main(String[] args) throws Exception {
    Sources sources = new Sources();
    AtomicLong clock = new AtomicLong(1000);
    CallAttributionMonitor monitor = new CallAttributionMonitor(sources, sources.telecom(), clock::get);
    require(monitor.takePending() == null, "OFF is empty");
    monitor.enable();
    CallAttributionFrame.Envelope idle = await(monitor);
    require(idle.mode == 0 && idle.telecom == null && sources.telecomSamples.get() == 0,
        "ordinary idle does not probe Telecom");
    sources.audio.set(audio(3, 1000)); sources.calls.set(calls(ACTIVE, ACTIVE)); clock.set(2000);
    CallAttributionFrame.Envelope active = await(monitor);
    require(active.mode == 3 && active.ownerUid == 1000 && active.telecom != null &&
        active.telecom.known && active.telecom.packageCode == 1 && active.telecom.activeCalls == 1,
        "system owner emits separately verified proof");
    clock.set(9000);
    require(active.observedAtMs == 2000 && active.telecom.observedAtMs == 2000,
        "draining or clock changes never refresh either source");
    sources.audio.set(audio(0, 0)); sources.calls.set(calls(ACTIVE.replace("state=ACTIVE", "state=ON_HOLD"), "null"));
    CallAttributionFrame.Envelope held = await(monitor);
    require(held.mode == 0 && held.telecom != null && held.telecom.liveCalls == 1 && held.telecom.activeCalls == 0,
        "held call remains observed outside communication mode");
    sources.calls.set(calls("", "null"));
    CallAttributionFrame.Envelope ended = await(monitor);
    require(ended.telecom != null && ended.telecom.known && ended.telecom.liveCalls == 0,
        "end produces authoritative empty set");
    require(await(monitor).telecom != null, "zero proof persists for sustained suppression release");
    monitor.disable();
    require(monitor.takePending() == null && sources.cancelled.get() >= 2, "OFF clears slot and cancels both probes");
    monitor.enable();
    require(await(monitor).telecom == null, "new generation resets previous call-following state");
    monitor.close();
    require(monitor.takePending() == null && sources.closed.get() >= 2, "close clears and closes both probes");
    cancelledGeneration();
    independentTimes();
    System.out.println("Call attribution monitor: " + passed + " passed");
  }
  private static void cancelledGeneration() throws Exception {
    Sources sources = new Sources(); sources.audio.set(audio(3, 1000)); sources.calls.set(calls(ACTIVE, ACTIVE));
    CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
    CallAttributionMonitor.TelecomSource blocked = new CallAttributionMonitor.TelecomSource() {
      public TelecomCallParser.State sample() { entered.countDown(); try { released.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return sources.calls.get(); }
      public void cancel() { released.countDown(); }
      public void close() { released.countDown(); }
    };
    CallAttributionMonitor monitor = new CallAttributionMonitor(sources, blocked, () -> 1000);
    monitor.enable(); require(entered.await(2, TimeUnit.SECONDS), "probe started");
    monitor.disable(); sources.audio.set(audio(0, 0)); monitor.enable();
    require(await(monitor).telecom == null, "completed previous epoch cannot reappear after rearm");
    monitor.close();
  }
  private static void independentTimes() throws Exception {
    Sources sources = new Sources(); sources.audio.set(audio(3, 1000)); sources.calls.set(calls(ACTIVE, ACTIVE));
    AtomicInteger times = new AtomicInteger();
    CallAttributionMonitor monitor = new CallAttributionMonitor(sources, sources.telecom(), () -> times.incrementAndGet() * 1000L);
    monitor.enable(); CallAttributionFrame.Envelope result = await(monitor);
    require(result.observedAtMs == 1000 && result.telecom.observedAtMs == 2000,
        "independent probe completion times preserved atomically"); monitor.close();
  }
  private static CallAttributionFrame.Envelope await(CallAttributionMonitor monitor) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) { CallAttributionFrame.Envelope e = monitor.takePending(); if (e != null) return e; Thread.sleep(10); }
    throw new AssertionError("sample deadline");
  }
  private static CallAudioOwnerParser.State audio(int mode, int uid) {
    return CallAudioOwnerParser.parse("Audio mode:\n  mAudioModeOwner: AudioModeInfo: mMode=" +
        (mode == 3 ? "MODE_IN_COMMUNICATION, mPid=42" : "MODE_NORMAL, mPid=0") + ", mUid=" + uid + "\n");
  }
  private static TelecomCallParser.State calls(String rows, String foreground) {
    return TelecomCallParser.parse("CallsManager:\n  mCalls:\n" + (rows.isEmpty() ? "" : "    " + rows + "\n") +
        "  mCallAudioManager:\n    All calls:\n    Active dialing, or connecting calls:\n    Ringing calls:\n    Holding calls:\n    Foreground call:\n    " + foreground + "\n    CallAudioModeStateMachine:\n");
  }
  private static void require(boolean ok, String name) { if (!ok) throw new AssertionError(name); passed++; }
  private static final class Sources implements CallAttributionMonitor.AudioSource {
    final AtomicReference<CallAudioOwnerParser.State> audio = new AtomicReference<>(audio(0, 0));
    final AtomicReference<TelecomCallParser.State> calls = new AtomicReference<>(calls("", "null"));
    final AtomicInteger telecomSamples = new AtomicInteger(), cancelled = new AtomicInteger(), closed = new AtomicInteger();
    public CallAudioOwnerParser.State sample() { return audio.get(); }
    public void cancel() { cancelled.incrementAndGet(); }
    public void close() { closed.incrementAndGet(); }
    CallAttributionMonitor.TelecomSource telecom() { return new CallAttributionMonitor.TelecomSource() {
      public TelecomCallParser.State sample() { telecomSamples.incrementAndGet(); return calls.get(); }
      public void cancel() { cancelled.incrementAndGet(); }
      public void close() { closed.incrementAndGet(); }
    }; }
  }
}
