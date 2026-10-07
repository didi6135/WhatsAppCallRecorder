package com.codaki.usbaudio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Physical devices require --metadata-only; synthetic capture is restricted to owned emulators. */
public final class RuntimeAudioProbe {
  private static final int RATE = 16000;
  private static final String[] PERMISSIONS = {"RECORD_AUDIO", "MODIFY_AUDIO_ROUTING", "CAPTURE_VOICE_COMMUNICATION_OUTPUT"};
  private static final JSONArray errors = new JSONArray();
  private RuntimeAudioProbe() { }

  public static void main(String[] args) throws Exception {
    boolean metadataOnly = args.length == 1 && "--metadata-only".equals(args[0]);
    JSONObject result = new JSONObject().put("sdk", Build.VERSION.SDK_INT).put("android", Build.VERSION.RELEASE)
        .put("abis", new JSONArray(Build.SUPPORTED_ABIS)).put("uid", Process.myUid())
        .put("metadataOnly", metadataOnly).put("syntheticHz", metadataOnly ? JSONObject.NULL : 750)
        .put("realWhatsAppValidation", false).put("microphoneAudibilityTested", false);
    String stage = "guard";
    Object capture = null;
    AudioRecord output = null, microphone = null;
    AudioTrack track = null;
    Field shellField = null;
    Object previousContext = null;
    boolean contextChanged = false;
    try {
      if (args.length != 0 && !metadataOnly) throw new IllegalArgumentException();
      if (Process.myUid() != 2000) throw new SecurityException();
      if (!metadataOnly && !"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) throw new SecurityException();
      stage = "permission_metadata";
      Context system = systemContext();
      JSONObject grants = new JSONObject();
      for (String permission : PERMISSIONS) grants.put(permission,
          system.getPackageManager().checkPermission("android.permission." + permission, "com.android.shell") == PackageManager.PERMISSION_GRANTED);
      result.put("shellGrants", grants);
      if (!ShellAudioCompatibility.isCandidateSdk(Build.VERSION.SDK_INT)) {
        result.put("status", "intentional_unsupported_sdk");
      } else {
        stage = "shell_context";
        Context shell = ShellAudioContext.create();
        stage = "runtime_preflight";
        ShellAudioCompatibility.requireRuntime(Build.VERSION.SDK_INT, Process.myUid(),
            permission -> shell.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            AudioCaptureApi::requireAvailable);
        result.put("preflight", "passed");
        // Exact production metadata gate only: no helper context mutation or audio resources.
        if (metadataOnly) { result.put("status", "metadata_only_passed"); return; }
        shellField = UsbAudioHelper.class.getDeclaredField("shellContext"); shellField.setAccessible(true);
        previousContext = shellField.get(null); shellField.set(null, shell); contextChanged = true;
        JSONObject out = new JSONObject(), mic = new JSONObject();
        result.put("output", out).put("microphone", mic);
        try {
          capture = invoke(UsbAudioHelper.class, null, "outputRecorder");
          Field recorder = capture.getClass().getDeclaredField("recorder"); recorder.setAccessible(true);
          output = (AudioRecord) recorder.get(capture);
          out.put("initialized", output.getState() == AudioRecord.STATE_INITIALIZED);
          if (output.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
          output.startRecording(); out.put("recording", output.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
          if (output.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
        } catch (Throwable error) { failure("output_create_start", error); }
        try {
          microphone = (AudioRecord) invoke(UsbAudioHelper.class, null, "microphoneRecorder");
          mic.put("initialized", microphone.getState() == AudioRecord.STATE_INITIALIZED);
          if (microphone.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
          microphone.startRecording(); mic.put("recording", microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
          if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
        } catch (Throwable error) { failure("microphone_create_start", error); }
        stage = "synthetic_track";
        short[] tone = new short[RATE * 2];
        for (int i = 0; i < tone.length; i++) tone[i] = (short) (6000 * Math.sin(2 * Math.PI * 750 * i / RATE));
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(tone.length * 2).build();
        result.put("syntheticInitialState", track.getState());
        // MODE_STATIC becomes initialized only after its first successful write.
        if (track.getState() == AudioTrack.STATE_UNINITIALIZED) throw new IllegalStateException();
        int written = track.write(tone, 0, tone.length, AudioTrack.WRITE_NON_BLOCKING);
        result.put("syntheticWrittenFrames", written).put("syntheticStateAfterWrite", track.getState());
        if (written != tone.length || track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException();
        java.util.Arrays.fill(tone, (short) 0);
        track.play();
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) throw new IllegalStateException();
        stage = "nonblocking_read";
        short[] samples = new short[2048];
        int outputFrames = 0, nonzero = 0, peak = 0, microphoneFrames = 0;
        double toneCosine = 0, toneSine = 0, outputEnergy = 0;
        int outputRead = 0, microphoneRead = 0;
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < deadline) {
          if (output != null && output.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            outputRead = output.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
            if (outputRead < 0) { out.put("readError", outputRead); break; }
            for (int i = 0; i < outputRead; i++) {
              int sample = samples[i], value = Math.abs(sample);
              if (value != 0) nonzero++;
              peak = Math.max(peak, value);
              double phase = 2 * Math.PI * 750 * (outputFrames + i) / RATE;
              toneCosine += sample * Math.cos(phase);
              toneSine += sample * Math.sin(phase);
              outputEnergy += (double) sample * sample;
            }
            outputFrames += outputRead;
            java.util.Arrays.fill(samples, (short) 0);
          }
          if (microphone != null && microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            microphoneRead = microphone.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
            if (microphoneRead < 0) { mic.put("readError", microphoneRead); break; }
            microphoneFrames += microphoneRead; java.util.Arrays.fill(samples, (short) 0);
          }
          SystemClock.sleep(10);
        }
        out.put("readFrames", outputFrames).put("nonzeroSamples", nonzero).put("peak", peak).put("lastRead", outputRead);
        double toneEnergyFraction = outputFrames > 0 && outputEnergy > 0
            ? 2 * (toneCosine * toneCosine + toneSine * toneSine) / (outputFrames * outputEnergy) : 0;
        out.put("synthetic750HzEnergyFraction", toneEnergyFraction);
        mic.put("readFrames", microphoneFrames).put("lastRead", microphoneRead).put("hostInputRequiredDisabled", true);
        result.put("status", errors.length() == 0 && outputFrames > 0 && nonzero > 0 && toneEnergyFraction >= 0.25 && microphoneFrames > 0
            && !out.has("readError") && !mic.has("readError") ? "synthetic_capture_passed" : "capture_failed");
      }
    } catch (Throwable error) { failure(stage, error); result.put("status", "probe_failed"); }
    finally {
      int beforeCleanup = errors.length();
      if (track != null) {
        try { if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) track.stop(); }
        catch (Throwable error) { failure("track_cleanup_stop", error); }
        finally { try { track.release(); } catch (Throwable error) { failure("track_cleanup_release", error); } }
      }
      release(output, "output_cleanup"); release(microphone, "microphone_cleanup");
      if (capture != null) try { invoke(capture.getClass(), capture, "unregister"); } catch (Throwable error) { failure("policy_cleanup", error); }
      if (contextChanged) try { shellField.set(null, previousContext); } catch (Throwable error) { failure("context_cleanup", error); }
      result.put("errors", errors).put("cleanupPassed", errors.length() == beforeCleanup);
      if (errors.length() > 0 && "synthetic_capture_passed".equals(result.optString("status"))) result.put("status", "cleanup_failed");
      System.out.println("WA_RUNTIME_AUDIO_PROBE " + result.toString());
      System.exit(0);
    }
  }
  private static Context systemContext() throws Exception {
    if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
    Class<?> owner = Class.forName("android.app.ActivityThread");
    Object thread = owner.getDeclaredMethod("currentActivityThread").invoke(null);
    if (thread == null) thread = owner.getDeclaredMethod("systemMain").invoke(null);
    return (Context) owner.getDeclaredMethod("getSystemContext").invoke(thread);
  }
  private static Object invoke(Class<?> owner, Object target, String name) throws Exception {
    Method method = owner.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
  }
  private static void release(AudioRecord recorder, String stage) {
    if (recorder == null) return;
    try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
    catch (Throwable error) { failure(stage + "_stop", error); }
    finally { try { recorder.release(); } catch (Throwable error) { failure(stage + "_release", error); } }
  }
  private static void failure(String stage, Throwable error) {
    while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
    try { errors.put(new JSONObject().put("stage", stage).put("causeClass", error.getClass().getSimpleName())); }
    catch (Exception ignored) { }
  }
}
