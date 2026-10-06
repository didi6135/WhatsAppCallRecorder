package com.codaki.usbaudio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic version/permission checks; never loads Android or creates audio resources. */
public final class ShellAudioCompatibilityTest {
  private static int passed;
  public static void main(String[] args) {
    for (int sdk : new int[] {-1, 0, 24, 30, 31, 32, 33}) {
      require(!ShellAudioCompatibility.isCandidateSdk(sdk), "older route rejected");
      require(!ShellAudioCompatibility.isExperimentalSdk(sdk), "rejected is not experimental");
      expect(UnsupportedOperationException.class, () -> check(sdk, 2000, null, false));
    }
    for (int sdk : new int[] {34, 35, 36, 37}) {
      require(ShellAudioCompatibility.isCandidateSdk(sdk), "candidate route");
      require(ShellAudioCompatibility.isExperimentalSdk(sdk) == (sdk != 36), "version status");
      List<String> checked = new ArrayList<>();
      final int[] apiCalls = {0};
      ShellAudioCompatibility.requireRuntime(sdk, 2000, permission -> { checked.add(permission); return true; }, () -> apiCalls[0]++);
      require(checked.equals(Arrays.asList("android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_ROUTING", "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT")), "exact required grants");
      require(apiCalls[0] == 1, "metadata API preflight once");
      for (String missing : checked) expect(SecurityException.class, () -> check(sdk, 2000, missing, false));
      expect(UnsupportedOperationException.class, () -> check(sdk, 2000, null, true));
      for (int uid : new int[] {0, 1000, 10000, 102000}) expect(SecurityException.class, () -> check(sdk, uid, null, false));
    }
    require(ShellAudioCompatibility.isCandidateSdk(Integer.MAX_VALUE), "future retains previous candidate behavior");
    require(ShellAudioCompatibility.isExperimentalSdk(Integer.MAX_VALUE), "future is unverified");
    System.out.println("Shell audio compatibility tests passed: " + passed);
  }
  private static void check(int sdk, int uid, String missing, boolean missingApi) {
    ShellAudioCompatibility.requireRuntime(sdk, uid, permission -> !permission.equals(missing), () -> {
      if (missingApi) throw new UnsupportedOperationException("Missing synthetic API");
      if (sdk < 34 || uid != 2000 || missing != null) throw new AssertionError("preflight before version/UID/grants");
    });
  }
  private static void expect(Class<? extends Throwable> kind, Runnable operation) {
    try { operation.run(); throw new AssertionError("Expected " + kind.getSimpleName()); }
    catch (Throwable error) { if (!kind.isInstance(error)) throw new AssertionError("Wrong failure", error); }
    passed++;
  }
  private static void require(boolean result, String description) {
    if (!result) throw new AssertionError(description); passed++;
  }
}
