package com.codaki.usbaudio;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Fixed entry point. Secrets travel through transient pipes; the detached app socket owns recording. */
public final class ShellAudioBootstrap {
  private ShellAudioBootstrap() { }

  public static void main(String[] args) {
    byte[] key = new byte[32];
    int exitCode = 0;
    boolean detachedWorker = false;
    try {
      if (Process.myUid() != 2000) throw new SecurityException("ADB shell UID is required.");
      int appUid = -1, port = -1;
      boolean spawn = false, detached = false;
      for (String arg : args) {
        if (arg.startsWith("--app-uid=")) appUid = Integer.parseInt(arg.substring(10));
        else if (arg.startsWith("--port=")) port = Integer.parseInt(arg.substring(7));
        else if (arg.equals("--spawn-detached")) spawn = true;
        else if (arg.equals("--detached")) { detached = true; detachedWorker = true; }
        else throw new IllegalArgumentException("Unsupported bootstrap argument.");
      }
      if (args.length != 3 || spawn == detached || appUid < 10000 || port < 1024 || port > 65535) {
        throw new IllegalArgumentException("Invalid fixed bootstrap metadata.");
      }
      if (detached) verifyIndependentSession();
      final BootstrapHandoff seed = new BootstrapHandoff();
      Thread seedDeadline = deadline("BootstrapInputDeadline", 8000L, () -> {
        if (seed.cancelIfPending()) { System.err.println("BOOTSTRAP_INPUT_TIMEOUT"); Runtime.getRuntime().halt(2); }
      });
      DataInputStream input = new DataInputStream(System.in);
      input.readFully(key); seed.complete(); seedDeadline.interrupt();
      if (spawn) spawnDetached(appUid, port, key, input);
      else runDetached(appUid, port, key, input);
    } catch (Throwable error) {
      String failure = "BOOTSTRAP_FAILED type=" + error.getClass().getSimpleName();
      if (detachedWorker) android.util.Log.e("UsbAudioHelper", failure); else System.err.println(failure);
      UsbAudioHelper.stopDriver();
      exitCode = 1;
    } finally {
      Arrays.fill(key, (byte) 0);
      UsbAudioHelper.stopDriver();
    }
    // Framework Binder threads must not keep a disconnected shell process alive.
    System.exit(exitCode);
  }

  private static void spawnDetached(int appUid, int port, byte[] key, DataInputStream owner) throws Exception {
    String inherited = System.getenv("CLASSPATH");
    if (inherited == null || inherited.length() > 2048 || inherited.indexOf(':') >= 0 || inherited.indexOf('\n') >= 0) {
      throw new SecurityException("A single installed APK classpath is required.");
    }
    File apk = new File(inherited).getCanonicalFile();
    if (!apk.getPath().startsWith("/data/app/") || !apk.getPath().endsWith("/base.apk") || !apk.isFile() || !apk.canRead()) {
      throw new SecurityException("The detached worker must run from the installed APK.");
    }
    ProcessBuilder builder = new ProcessBuilder("/system/bin/setsid", "/system/bin/app_process", "/",
        ShellAudioBootstrap.class.getName(), "--detached", "--app-uid=" + appUid, "--port=" + port);
    builder.environment().put("CLASSPATH", apk.getPath());
    builder.redirectOutput(new File("/dev/null")); builder.redirectError(new File("/dev/null"));
    final java.lang.Process child = builder.start();
    final BootstrapHandoff handoff = new BootstrapHandoff();
    Thread handoffDeadline = deadline("BootstrapHandoffDeadline", 12000L, () -> {
      if (handoff.cancelIfPending()) { destroyChild(child); System.err.println("BOOTSTRAP_HANDOFF_TIMEOUT"); Runtime.getRuntime().halt(2); }
    });
    try {
      child.getOutputStream().write(key); child.getOutputStream().flush();
      Arrays.fill(key, (byte) 0);
      System.out.println("BOOTSTRAP_DETACHED_SPAWNED uid=" + Process.myUid()); System.out.flush();
      BootstrapHandoff.requireAck(owner.read());
      if (!child.isAlive()) throw new IllegalStateException("Detached worker exited before ownership transfer.");
      // Keep this pipe open until the authenticated app authorizes the handoff.
      // The child independently expires if this parent dies before relaying ACK.
      child.getOutputStream().write(1); child.getOutputStream().flush(); child.getOutputStream().close();
      handoff.complete();
      System.out.println("BOOTSTRAP_DETACHED_HANDOFF_OK"); System.out.flush();
    } finally {
      handoffDeadline.interrupt();
      if (!handoff.isComplete()) destroyChild(child);
      try { child.getOutputStream().close(); } catch (Exception ignored) { }
    }
  }

  private static void runDetached(int appUid, int port, byte[] key, DataInputStream parent) throws Exception {
    UsbAudioHelper.useAndroidLogging();
    android.util.Log.i("UsbAudioHelper", "BOOTSTRAP_DETACHED_WORKER uid=" + Process.myUid() + " pid=" + Process.myPid()
        + " processGroup=" + Process.myPid() + " session=" + Process.myPid());
    final BootstrapHandoff handoff = new BootstrapHandoff();
    Thread startupDeadline = deadline("DetachedStartupDeadline", 20000L, () -> {
      if (handoff.cancelIfPending()) Runtime.getRuntime().halt(2);
    });
    try {
      UsbAudioHelper.runWithPairing(appUid, port, key, () -> {
        try {
          BootstrapHandoff.requireAck(parent.read());
          // Replace the seed pipe with /dev/null. ADB shutdown can no longer
          // revoke the worker; its authenticated loopback EOF still does.
          FileDescriptor devNull = Os.open("/dev/null", OsConstants.O_RDONLY, 0);
          try { Os.dup2(devNull, 0); } finally { Os.close(devNull); }
          handoff.complete(); startupDeadline.interrupt(); Arrays.fill(key, (byte) 0);
          android.util.Log.i("UsbAudioHelper", "BOOTSTRAP_DETACHED_ACTIVE uid=" + Process.myUid() + " pid=" + Process.myPid());
        } catch (Exception failed) { throw new IllegalStateException("Detached ownership transfer failed.", failed); }
      });
    } finally { startupDeadline.interrupt(); }
  }

  private static void verifyIndependentSession() throws Exception {
    File stat = new File("/proc/self/stat");
    if (stat.length() > 4096) throw new SecurityException("Invalid process metadata size.");
    byte[] data = Files.readAllBytes(stat.toPath());
    if (data.length > 4096) throw new SecurityException("Invalid process metadata size.");
    BootstrapHandoff.requireIndependentSession(new String(data, StandardCharsets.US_ASCII), Process.myPid());
  }

  private static Thread deadline(String name, long duration, Runnable expired) {
    Thread thread = new Thread(() -> {
      try { Thread.sleep(duration); expired.run(); } catch (InterruptedException completed) { }
    }, name);
    thread.setDaemon(true); thread.start(); return thread;
  }

  private static void destroyChild(java.lang.Process child) {
    try {
      child.destroy();
      if (!child.waitFor(1000L, TimeUnit.MILLISECONDS)) { child.destroyForcibly(); child.waitFor(1000L, TimeUnit.MILLISECONDS); }
    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
    catch (Exception failed) { child.destroyForcibly(); }
  }
}
