package com.codaki.usbaudio;

/** Regression for the prepopulated systemMain Application used by contextless AudioRecord. */
public final class ShellApplicationPolicyTest {
  public static void main(String[] args) {
    check(ShellAudioContext.replaceInitialApplication("android"), "systemMain Android Application must be replaced");
    check(ShellAudioContext.replaceInitialApplication(null), "absent Application must be initialized");
    check(!ShellAudioContext.replaceInitialApplication("com.android.shell"), "correct shell Application must be preserved");
    try {
      ShellAudioContext.replaceInitialApplication("com.didi4164.WhatsAppCallRecorder.standalone");
      throw new AssertionError("Unexpected RN Application must be rejected");
    } catch (SecurityException expected) { }
    System.out.println("PASS: systemMain attribution replacement, shell preservation, unexpected app rejection.");
  }
  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
