package com.codaki.usbaudio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Explicitly paired USB shell transport. Captures nothing while waiting for the app's START. */
public final class UsbAudioHelper {
  private static final int RATE = 16000;
  private static final int BLOCK_FRAMES = 320;
  private static final int FIFO_FRAMES = RATE * 2;
  private static final int FRAME_HEARTBEAT = 0, FRAME_PCM = 1, FRAME_STARTED = 2, FRAME_STOPPED = 3, FRAME_ERROR = 4, FRAME_CALL_ATTRIBUTION = 6;
  private static final String HANDSHAKE = "WA_USB_2";
  private static volatile boolean connected;
  private static volatile boolean driverRunning, driverStopRequested;
  private static volatile CaptureSession session;
  private static Socket socket;
  private static volatile long writeStartedAt;
  private static Context shellContext;
  private static volatile boolean androidLogging;
  private static volatile CallAttributionMonitor callAudioMonitor;

  public static void main(String[] args) {
    int exitCode = 0;
    try { run(args); }
    catch (Throwable error) { error.printStackTrace(System.err); exitCode = 1; }
    finally { disconnect(); }
    System.exit(exitCode);
  }

  private static void run(String[] args) throws Exception {
    String tokenPath = null;
    boolean deletePairing = false;
    int appUid = -1, port = -1;
    for (String arg : args) {
      if (arg.startsWith("--app-uid=")) appUid = Integer.parseInt(arg.substring(10));
      else if (arg.startsWith("--port=")) port = Integer.parseInt(arg.substring(7));
      else if (arg.startsWith("--token-file=")) tokenPath = arg.substring(13);
      else if (arg.equals("--delete-pairing-file")) deletePairing = true;
      else throw new IllegalArgumentException("Unknown argument: " + arg);
    }
    if (Process.myUid() != 2000) throw new SecurityException("Authorized ADB shell UID 2000 is required.");
    ShellAudioCompatibility.requireCandidateSdk(Build.VERSION.SDK_INT);
    if (appUid < 10000) throw new IllegalArgumentException("Expected installed app UID must be explicitly passed as --app-uid.");
    if (port < 1024 || port > 65535 || tokenPath == null) throw new IllegalArgumentException("Explicit --port and --token-file are required.");
    byte[] key = readPairing(tokenPath, appUid, port);
    try {
      if (deletePairing) Files.delete(new File(tokenPath).getCanonicalFile().toPath());
      runWithPairing(appUid, port, key);
    } finally { java.util.Arrays.fill(key, (byte)0); }
  }

  /** Blocking driver entry for a real authorized shell process; pairing stays in memory. */
  public static void runWithPairing(int appUid, int port, byte[] pairingKey) throws Exception {
    runWithPairing(appUid, port, pairingKey, null);
  }

  /** Optional startup handoff runs after HMAC authentication and before START or heartbeat processing. */
  public static void runWithPairing(int appUid, int port, byte[] pairingKey, Runnable authenticated) throws Exception {
    if (Process.myUid() != 2000) throw new SecurityException("Authorized shell UID 2000 is required.");
    ShellAudioCompatibility.requireCandidateSdk(Build.VERSION.SDK_INT);
    if (appUid < 10000 || port < 1024 || port > 65535 || pairingKey == null || pairingKey.length != 32) throw new IllegalArgumentException("Invalid authorized pairing metadata.");
    synchronized (UsbAudioHelper.class) {
      if (driverRunning) throw new IllegalStateException("Audio helper is already running.");
      driverRunning = true; driverStopRequested = false;
    }
    byte[] localKey = pairingKey.clone();
    try { runConnection(appUid, port, localKey, authenticated); }
    finally { java.util.Arrays.fill(localKey, (byte)0); disconnect(); driverRunning = false; }
  }

  public static void stopDriver() { driverStopRequested = true; disconnect(); }
  public static boolean isDriverRunning() { return driverRunning; }
  public static void useAndroidLogging() { androidLogging = true; }
  private static void log(String message) { if (androidLogging) android.util.Log.i("UsbAudioHelper", message); else System.out.println(message); }
  private static void logError(String message) { if (androidLogging) android.util.Log.w("UsbAudioHelper", message); else System.err.println(message); }

  private static void runConnection(int appUid, int port, byte[] pairingKey, Runnable authenticated) throws Exception {
    shellContext = ShellAudioContext.create();
    ShellAudioCompatibility.requireRuntime(Build.VERSION.SDK_INT, Process.myUid(),
        permission -> shellContext.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
        AudioCaptureApi::requireAvailable);
    long connectDeadline = SystemClock.elapsedRealtime() + 60000;
    IOException lastConnectFailure = null;
    int connectAttempts = 0;
    while (!driverStopRequested && SystemClock.elapsedRealtime() < connectDeadline) {
      connectAttempts++;
      Socket attempt = new Socket();
      try {
        attempt.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1000);
        socket = attempt; break;
      } catch (IOException unavailable) {
        lastConnectFailure = unavailable;
        if (connectAttempts == 1) logError("USB_CONNECT_RETRY address=127.0.0.1 port=" + port + " error=" + unavailable);
        attempt.close(); Thread.sleep(500);
      }
    }
    if (driverStopRequested) return;
    if (socket == null) throw new IOException("App socket did not become available within 60 seconds; address=127.0.0.1 port="
        + port + " attempts=" + connectAttempts + " lastError=" + lastConnectFailure, lastConnectFailure);
    socket.setTcpNoDelay(true);
    socket.setSoTimeout(5000);
    DataInputStream commands = new DataInputStream(socket.getInputStream());
    DataOutputStream frames = new DataOutputStream(new BoundedOutputStream(socket.getOutputStream()));
    if (driverStopRequested) return;
    connected = true;
    Runtime.getRuntime().addShutdownHook(new Thread(UsbAudioHelper::disconnect, "UsbAudioShutdown"));
    Thread sendMonitor = new Thread(() -> {
      try {
        while (connected) {
          long started = writeStartedAt;
          if (started != 0 && SystemClock.elapsedRealtime() - started > 2000) {
            logError("USB_SEND_TIMEOUT"); disconnect(); return;
          }
          Thread.sleep(100);
        }
      } catch (InterruptedException ignored) { }
    }, "UsbSendDeadline");
    sendMonitor.setDaemon(true); sendMonitor.start();
    authenticate(commands, frames, pairingKey);
    java.util.Arrays.fill(pairingKey, (byte)0);
    socket.setSoTimeout(0);
    log("USB_HELPER_READY port=" + port + " appUid=" + appUid + " authenticated=true idle=true");
    if (authenticated != null) authenticated.run();
    final CallAttributionMonitor ownerMonitor = new CallAttributionMonitor(SystemClock::elapsedRealtime, Build.VERSION.SDK_INT);
    callAudioMonitor = ownerMonitor;
    BlockingQueue<String> queue = new LinkedBlockingQueue<>(8);
    Thread commandReader = new Thread(() -> {
      try {
        while (connected) {
          String command = commands.readUTF();
          if (command.equals("MONITOR_CALLS_ON")) { ownerMonitor.enable(); continue; }
          if (command.equals("MONITOR_CALLS_OFF")) { ownerMonitor.disable(); continue; }
          if (!command.equals("START") && !command.equals("STOP")) throw new IOException("Unknown app command.");
          if (!queue.offer(command)) throw new IOException("App command queue overflow.");
          if (command.equals("STOP")) {
            log("USB_STOP_RECEIVED");
            CaptureSession active = session; if (active != null) active.requestStop();
          }
        }
      } catch (EOFException closed) { log("USB_APP_DISCONNECTED"); }
      catch (Exception error) { if (connected) logError("USB_COMMAND_FAILED " + error); }
      finally { connected = false; ownerMonitor.close(); CaptureSession active = session; if (active != null) active.requestStop(); }
    }, "UsbAudioCommands");
    commandReader.start();
    long heartbeatAt = 0;
    try {
      while (connected) {
        CallAttributionFrame.Envelope owner = ownerMonitor.takePending();
        if (owner != null) {
          frames.writeByte(FRAME_CALL_ATTRIBUTION);
          CallAttributionFrame.write(frames, owner); frames.flush();
        }
        String command = queue.poll(session == null ? 100 : 0, TimeUnit.MILLISECONDS);
        if ("START".equals(command)) {
          if (session != null) { frames.writeByte(FRAME_STARTED); frames.flush(); }
          else {
            try {
              CaptureSession next = new CaptureSession(); session = next; next.start();
              frames.writeByte(FRAME_STARTED); frames.flush();
              log("USB_CAPTURE_STARTED");
            } catch (Exception error) {
              closeSession(); sendError(frames, error); frames.writeByte(FRAME_STOPPED); frames.flush();
            }
          }
        } else if ("STOP".equals(command)) {
          CaptureSession finishing = session;
          if (finishing != null) finishing.drainCutoff(frames);
          closeSession(); frames.writeByte(FRAME_STOPPED); frames.flush(); log("USB_CAPTURE_STOPPED");
        }
        CaptureSession active = session;
        if (active != null && !active.stopped) {
          try {
            Packet packet = active.nextPacket();
            // A packet already removed from the FIFO remains part of the cutoff.
            if (packet != null && connected) sendPacket(frames, packet);
          } catch (Exception error) {
            closeSession(); sendError(frames, error); frames.writeByte(FRAME_STOPPED); frames.flush();
          }
        } else if (SystemClock.elapsedRealtime() >= heartbeatAt) {
          frames.writeByte(FRAME_HEARTBEAT); frames.flush(); heartbeatAt = SystemClock.elapsedRealtime() + 1000;
        }
      }
    } finally {
      disconnect(); commandReader.interrupt(); commandReader.join(3000);
    }
  }

  private static void sendPacket(DataOutputStream frames, Packet packet) throws IOException {
    frames.writeByte(FRAME_PCM); frames.writeByte(packet.flags); frames.writeInt(packet.pcm.length); frames.write(packet.pcm); frames.flush();
  }

  private static byte[] readPairing(String tokenPath, int appUid, int port) throws Exception {
    File tokenFile = new File(tokenPath).getCanonicalFile();
    String allowed = new File("/data/local/tmp/codaki-audio-probe").getCanonicalPath() + File.separator;
    if (!tokenFile.getPath().startsWith(allowed)) throw new SecurityException("Pairing file must remain within the authorized staging directory.");
    StructStat stat = Os.stat(tokenFile.getPath());
    if (stat.st_uid != Process.myUid() || (stat.st_mode & 0077) != 0) throw new SecurityException("Pairing file must be owned by shell with no group/world permissions (chmod 600).");
    if (!tokenFile.isFile() || tokenFile.length() < 1 || tokenFile.length() > 4096) throw new IllegalArgumentException("Invalid pairing file size/type.");
    JSONObject pairing;
    try { pairing = new JSONObject(new String(Files.readAllBytes(tokenFile.toPath()), StandardCharsets.UTF_8)); }
    catch (Exception invalid) { throw new IllegalArgumentException("Invalid pairing file JSON."); }
    if (pairing.getInt("appUid") != appUid || pairing.getInt("port") != port || !HANDSHAKE.equals(pairing.getString("protocol"))) throw new SecurityException("Pairing metadata does not match the approved app/protocol/port.");
    return unhex(pairing.getString("token"));
  }

  private static void authenticate(DataInputStream commands, DataOutputStream frames, byte[] key) throws Exception {
    byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce); String clientNonce = hex(nonce);
    frames.writeUTF(HANDSHAKE); frames.writeUTF(clientNonce); frames.flush();
    String serverNonce = commands.readUTF(); unhex(serverNonce);
    byte[] serverProof = unhex(commands.readUTF());
    if (!MessageDigest.isEqual(serverProof, proof(key, "SERVER", clientNonce, serverNonce))) throw new SecurityException("Pairing server authentication failed.");
    frames.writeUTF(hex(proof(key, "CLIENT", clientNonce, serverNonce))); frames.flush();
  }

  private static byte[] proof(byte[] key, String role, String clientNonce, String serverNonce) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal((role + "|" + clientNonce + "|" + serverNonce).getBytes(StandardCharsets.UTF_8));
  }

  private static String hex(byte[] values) {
    char[] digits = "0123456789abcdef".toCharArray(), result = new char[values.length * 2];
    for (int i = 0; i < values.length; i++) { result[i*2] = digits[(values[i] & 255) >>> 4]; result[i*2+1] = digits[values[i] & 15]; }
    return new String(result);
  }

  private static byte[] unhex(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw new SecurityException("Invalid pairing key/nonce/proof format.");
    byte[] bytes = new byte[32];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)((Character.digit(value.charAt(i*2),16) << 4) | Character.digit(value.charAt(i*2+1),16));
    return bytes;
  }

  /** A blocked TCP write cannot leave capture running after its receiver stops draining. */
  private static final class BoundedOutputStream extends OutputStream {
    final OutputStream delegate;
    BoundedOutputStream(OutputStream delegate) { this.delegate = delegate; }
    @Override public void write(int value) throws IOException {
      writeStartedAt = SystemClock.elapsedRealtime();
      try { delegate.write(value); } finally { writeStartedAt = 0; }
    }
    @Override public void write(byte[] values, int offset, int length) throws IOException {
      writeStartedAt = SystemClock.elapsedRealtime();
      try { delegate.write(values, offset, length); } finally { writeStartedAt = 0; }
    }
    @Override public void flush() throws IOException { delegate.flush(); }
  }

  private static void sendError(DataOutputStream frames, Exception error) throws IOException {
    String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    if (message.length() > 600) message = message.substring(0, 600);
    frames.writeByte(FRAME_ERROR); frames.writeUTF(message); frames.flush(); logError("USB_CAPTURE_FAILED " + message);
  }

  private static Method method(Class<?> owner, String name, Class<?>... args) throws Exception {
    Method result = owner.getDeclaredMethod(name, args); result.setAccessible(true); return result;
  }

  private static synchronized void closeSession() { CaptureSession previous = session; session = null; if (previous != null) previous.close(); }
  private static synchronized void disconnect() {
    connected = false; CaptureSession active = session; if (active != null) active.requestStop();
    CallAttributionMonitor previousMonitor = callAudioMonitor; callAudioMonitor = null;
    if (previousMonitor != null) previousMonitor.close();
    Socket previousSocket = socket; socket = null;
    if (previousSocket != null) try { previousSocket.close(); } catch (IOException ignored) { }
    closeSession();
  }

  private static final class OutputCapture {
    final Object policy; final Class<?> policyClass; final AudioRecord recorder;
    OutputCapture(Object policy, Class<?> policyClass, AudioRecord recorder) { this.policy = policy; this.policyClass = policyClass; this.recorder = recorder; }
    void unregister() {
      log("USB_POLICY_UNREGISTER_BEGIN");
      try { method(AudioManager.class, "unregisterAudioPolicyAsyncStatic", policyClass).invoke(null, policy); }
      catch (Exception error) { throw new IllegalStateException("Could not unregister audio capture policy.", error); }
      log("USB_POLICY_UNREGISTER_COMPLETE");
    }
  }

  private static OutputCapture outputRecorder() throws Exception {
    Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
    Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
    Object builder = builderClass.getConstructor().newInstance();
    builderClass.getMethod("setTargetMixRole", int.class).invoke(builder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
    builderClass.getMethod("addMixRule", int.class, Object.class).invoke(builder, ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null),
        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build());
    // Voice permission flag is set only by AudioService. No privileged media opt-out override.
    Object rule = builderClass.getMethod("build").invoke(builder);
    Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
    Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
    Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
    mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder,
        new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build());
    mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null));
    Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);
    Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
    Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
    Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(shellContext);
    policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
    Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
    OutputCapture output = null;
    try {
      int result = (int) method(AudioManager.class, "registerAudioPolicyStatic", policyClass).invoke(null, policy);
      if (result != 0) throw new SecurityException("AudioService rejected voice output policy: " + result);
      AudioRecord recorder = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
      if (recorder == null) throw new IllegalStateException("No voice output capture sink.");
      output = new OutputCapture(policy, policyClass, recorder); return output;
    } finally {
      if (output == null) try { method(AudioManager.class, "unregisterAudioPolicyAsyncStatic", policyClass).invoke(null, policy); } catch (Exception ignored) { }
    }
  }

  private static AudioRecord microphoneRecorder() throws Exception {
    AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(8192);
    method(AudioRecord.Builder.class, "setContext", Context.class).invoke(builder, shellContext);
    return builder.build();
  }

  private static final class Packet { final int flags; final byte[] pcm; Packet(int flags, byte[] pcm) { this.flags = flags; this.pcm = pcm; } }
  private static final class ReadResult {
    final int frames, missing;
    ReadResult(int frames, int missing) { this.frames = frames; this.missing = missing; }
  }

  private static final class CaptureSession {
    final AtomicReference<Exception> failure = new AtomicReference<>();
    OutputCapture output;
    Channel left, right;
    volatile boolean stopped;
    boolean closed;
    int microphonePadding;
    PcmStopTail pendingStopPacket; // Sole frame-writer thread owns this packet.
    CaptureSession() throws Exception {
      try {
        output = outputRecorder(); left = new Channel("output", output.recorder, failure);
        right = new Channel("microphone", microphoneRecorder(), failure);
      } catch (Exception error) { close(); throw error; }
    }
    void start() throws Exception {
      try {
        left.start(); right.start();
        long offset = Math.max(0, (right.startedNanos - left.startedNanos) * RATE / 1000000000L);
        if (offset > FIFO_FRAMES) throw new IOException("Microphone startup exceeded the bounded alignment window.");
        microphonePadding = (int) offset;
      }
      catch (Exception error) { close(); throw error; }
    }
    void requestStop() { stopped = true; if (left != null) left.requestStop(); if (right != null) right.requestStop(); }
    void drainCutoff(DataOutputStream frames) throws Exception {
      requestStop();
      long deadline = SystemClock.elapsedRealtime() + 500;
      left.quiesce(deadline); right.quiesce(deadline);
      left.fifo.freeze(); right.fifo.freeze();
      if (pendingStopPacket != null) {
        PcmStopTail pending = pendingStopPacket; pendingStopPacket = null;
        byte[] completed = pending.complete(left.fifo, right.fifo);
        if (completed.length > 0 && connected) sendPacket(frames, new Packet(pending.flags, completed));
      }
      while (connected && (left.fifo.size() > 0 || right.fifo.size() > 0)) {
        // Two seconds of FIFO data require at most eight maximum-size packets.
        short[] a = new short[4096], b = new short[4096];
        int l = left.fifo.drain(a, 0, a.length);
        int padding = Math.min(microphonePadding, b.length); microphonePadding -= padding;
        int r = right.fifo.drain(b, padding, b.length - padding);
        int count = Math.max(l, padding + r);
        if (count > 0) sendPacket(frames, packet(a, b, count, (left.silenced ? 1 : 0) | (right.silenced ? 2 : 0)));
      }
      left.printCutoff(); right.printCutoff();
    }
    synchronized void close() {
      if (closed) return; closed = true;
      log("USB_CLEANUP_BEGIN");
      requestStop();
      AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
      Thread leftCleanup = cleanupThread("output", () -> { if (left != null) left.close(); }, cleanupFailure);
      Thread rightCleanup = cleanupThread("microphone", () -> { if (right != null) right.close(); }, cleanupFailure);
      long deadline = SystemClock.elapsedRealtime() + 2500;
      awaitCleanup(leftCleanup, deadline); awaitCleanup(rightCleanup, deadline);
      if (output != null) {
        OutputCapture previous = output; output = null;
        Thread policyCleanup = cleanupThread("policy", previous::unregister, cleanupFailure);
        awaitCleanup(policyCleanup, SystemClock.elapsedRealtime() + 1000);
      }
      if (cleanupFailure.get() != null) hardExit("Native audio cleanup failed: " + cleanupFailure.get().getClass().getSimpleName());
      log("USB_CLEANUP_COMPLETE");
    }
    Packet nextPacket() throws Exception {
      if (stopped) return null;
      Exception error = failure.get(); if (error != null) throw error;
      long waitDeadline = SystemClock.elapsedRealtime() + 200;
      short[] a = new short[BLOCK_FRAMES], b = new short[BLOCK_FRAMES];
      ReadResult leftRead = left.read(a, 0, BLOCK_FRAMES, waitDeadline);
      int startupPadding = Math.min(microphonePadding, BLOCK_FRAMES); microphonePadding -= startupPadding;
      ReadResult rightRead = right.read(b, startupPadding, BLOCK_FRAMES - startupPadding, waitDeadline);
      error = failure.get(); if (error != null) throw error;
      int flags = (left.silenced ? 1 : 0) | (right.silenced ? 2 : 0) | (leftRead.missing > 0 ? 4 : 0) | (rightRead.missing > 0 ? 8 : 0);
      int rightFrames = startupPadding + rightRead.frames;
      int count = Math.max(leftRead.frames, rightFrames);
      if (stopped && (leftRead.frames != rightFrames || count < BLOCK_FRAMES)) {
        // A native read may finish after STOP. Wait for that offer before
        // padding the short channel, so its samples keep their logical positions.
        if (pendingStopPacket != null) throw new IllegalStateException("A terminal packet is already pending.");
        pendingStopPacket = new PcmStopTail(a, leftRead.frames, b, rightFrames, flags);
        return null;
      }
      return count == 0 ? null : packet(a, b, count, flags);
    }
  }

  private static Packet packet(short[] left, short[] right, int count, int flags) {
    return new Packet(flags, PcmStopTail.interleave(left, right, count));
  }

  private static Thread cleanupThread(String name, Runnable action, AtomicReference<Throwable> failure) {
    Thread cleanup = new Thread(() -> {
      try { action.run(); }
      catch (Throwable error) { failure.compareAndSet(null, error); }
    }, "UsbCleanup-" + name);
    cleanup.setDaemon(true); cleanup.start(); return cleanup;
  }

  private static void awaitCleanup(Thread cleanup, long deadline) {
    try { cleanup.join(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); hardExit("Audio cleanup interrupted."); }
    if (cleanup.isAlive()) hardExit("Audio cleanup timed out at " + cleanup.getName());
  }

  private static void hardExit(String reason) {
    // A vendor Binder/native lock may not honor Java interruption. Process death
    // releases audio clients; never send STOPPED while their release is uncertain.
    logError("USB_CLEANUP_FATAL " + reason);
    Process.killProcess(Process.myPid());
    Runtime.getRuntime().halt(2);
  }

  /** Capture is independently drained; missing-frame deadlines advance the FIFO's logical cursor. */
  private static final class Channel {
    final String name;
    final AudioRecord recorder; final AtomicReference<Exception> failure;
    final PcmFifo fifo = new PcmFifo(FIFO_FRAMES);
    volatile boolean stopping, silenced;
    boolean released;
    volatile long lastSamplesAt, startedNanos;
    Thread reader;
    Channel(String name, AudioRecord recorder, AtomicReference<Exception> failure) { this.name = name; this.recorder = recorder; this.failure = failure; }
    void start() {
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Audio recorder was not initialized.");
      recorder.startRecording(); startedNanos = System.nanoTime(); lastSamplesAt = SystemClock.elapsedRealtime();
      if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Audio recorder did not start.");
      reader = new Thread(() -> {
        short[] samples = new short[1024];
        try {
          while (!stopping) {
            int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
            if (count < 0) { if (stopping) break; throw new IOException("AudioRecord.read failed: " + count); }
            android.media.AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
            silenced = configuration != null && configuration.isClientSilenced();
            if (count > 0) { offer(samples, count); lastSamplesAt = SystemClock.elapsedRealtime(); }
            else {
              if (SystemClock.elapsedRealtime() - lastSamplesAt > 2000) throw new IOException("No audio frames received for two seconds.");
              Thread.sleep(5);
            }
          }
        } catch (Exception error) { if (!stopping) failure.compareAndSet(null, error); }
        finally { synchronized (this) { notifyAll(); } }
      }, "UsbPcmReader-" + name); reader.start();
    }
    private synchronized void offer(short[] values, int count) throws IOException {
      fifo.offer(values, count);
      notifyAll();
    }
    synchronized ReadResult read(short[] target, int offset, int count, long deadline) throws InterruptedException {
      while (fifo.size() < count && !stopping && failure.get() == null && SystemClock.elapsedRealtime() < deadline) wait(Math.max(1, deadline - SystemClock.elapsedRealtime()));
      // STOP takes only actual queued samples; it does not invent a final gap.
      int requested = stopping ? Math.min(count, fifo.size()) : count;
      return new ReadResult(requested, fifo.read(target, offset, requested));
    }
    void quiesce(long deadline) {
      requestStop();
      if (reader != null && reader != Thread.currentThread()) {
        reader.interrupt();
        try { reader.join(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); hardExit("Audio cutoff interrupted."); }
        if (reader.isAlive()) hardExit("Audio reader did not quiesce before cutoff: " + name);
      }
    }
    void printCutoff() {
      PcmFifo.Snapshot s = fifo.snapshot();
      log("USB_PCM_CUTOFF track=" + name + " produced=" + s.producedFrames + " delivered=" + s.deliveredFrames
          + " missing=" + s.missingFrames + " stale=" + s.staleFrames + " overflow=" + s.overflowFrames
          + " queued=" + s.queuedFrames + " maxQueued=" + s.maxQueuedFrames + " cutoffProduced=" + s.cutoffProducedFrames
          + " cutoffQueued=" + s.cutoffQueuedFrames + " afterCutoff=" + s.afterCutoffFrames);
    }
    void requestStop() {
      stopping = true; synchronized (this) { notifyAll(); }
    }
    void close() {
      requestStop();
      log("USB_CHANNEL_CLOSE_BEGIN name=" + name);
      if (reader != null && reader != Thread.currentThread()) {
        reader.interrupt();
        try { reader.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (reader.isAlive()) logError("USB_READER_NOT_EXITED name=" + name);
      }
      synchronized (recorder) {
        if (!released) {
          released = true;
          // release() already calls stop() exactly once. The former stop path
          // called it repeatedly from the command reader and both close paths.
          log("USB_NATIVE_RELEASE_BEGIN name=" + name);
          recorder.release();
          log("USB_NATIVE_RELEASE_COMPLETE name=" + name);
        }
      }
      log("USB_CHANNEL_CLOSE_COMPLETE name=" + name);
    }
  }
}
