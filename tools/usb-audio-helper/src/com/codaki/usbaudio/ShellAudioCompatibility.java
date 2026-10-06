package com.codaki.usbaudio;

/** Shared app/helper gate. A candidate version never proves device capture capability. */
public final class ShellAudioCompatibility {
  public static final int MIN_CANDIDATE_SDK = 34;
  private ShellAudioCompatibility() { }
  public interface PermissionCheck { boolean granted(String permission); }

  public static boolean isCandidateSdk(int sdk) { return sdk >= MIN_CANDIDATE_SDK; }
  // Android16 retains its existing behavior. Real-device evidence is specific to the tested
  // Samsung; version status alone makes no promise about another model or audio route.
  public static boolean isExperimentalSdk(int sdk) { return isCandidateSdk(sdk) && sdk != 36; }
  public static void requireCandidateSdk(int sdk) {
    if (!isCandidateSdk(sdk)) throw new UnsupportedOperationException("Shell call capture requires Android14/API34 or newer.");
  }

  /** Metadata-only preflight: real UID and current OS grants, then exact API accessibility.
   * AudioService registration, recorder initialization and read failures remain authoritative
   * at START. No permission flag is invented and no audio resource is created here. */
  public static void requireRuntime(int sdk, int uid, PermissionCheck permissions, Runnable verifyApis) {
    requireCandidateSdk(sdk);
    if (uid != 2000) throw new SecurityException("Authorized shell UID2000 is required.");
    if (permissions == null || verifyApis == null) throw new NullPointerException();
    for (String name : new String[] {"RECORD_AUDIO", "MODIFY_AUDIO_ROUTING", "CAPTURE_VOICE_COMMUNICATION_OUTPUT"}) {
      if (!permissions.granted("android.permission." + name))
        throw new SecurityException("Shell permission unavailable: " + name);
    }
    verifyApis.run();
  }
}
