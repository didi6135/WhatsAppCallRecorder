package com.codaki.usbaudio;

import java.util.HashMap;
import java.util.Map;

/** Framework stand-ins throw if constructed or invoked: preflight must remain metadata only. */
public final class AudioCaptureApiTest {
  private static int passed;
  public static void main(String[] args) {
    AudioCaptureApi.requireAvailable(resolver(model())); passed++;
    for (String name : model().keySet()) {
      Map<String, Class<?>> missing = model(); missing.remove(name);
      rejects(missing);
    }
    rejects(replace("android.media.audiopolicy.AudioMixingRule$Builder", MissingMixRule.class));
    rejects(replace("android.media.audiopolicy.AudioMix", WrongRoute.class));
    rejects(replace("android.media.AudioManager", NonStaticManager.class));
    rejects(replace("android.media.AudioManager", WrongRegisterResult.class));
    rejects(replace("android.media.AudioRecord$Builder", MissingMicContext.class));
    rejects(replace("android.media.audiopolicy.AudioPolicy", MissingSink.class));
    System.out.println("Audio capture API preflight tests passed: " + passed);
  }
  private static AudioCaptureApi.ClassResolver resolver(Map<String, Class<?>> classes) {
    return name -> { Class<?> value = classes.get(name); if (value == null) throw new ClassNotFoundException("synthetic"); return value; };
  }
  private static Map<String, Class<?>> replace(String name, Class<?> value) {
    Map<String, Class<?>> classes = model(); classes.put(name, value); return classes;
  }
  private static void rejects(Map<String, Class<?>> classes) {
    try { AudioCaptureApi.requireAvailable(resolver(classes)); throw new AssertionError("Missing API accepted"); }
    catch (UnsupportedOperationException expected) {
      if (!expected.getMessage().equals("Required shell audio API is unavailable on this device.")) throw new AssertionError("Fixed diagnostic required");
    }
    passed++;
  }
  private static Map<String, Class<?>> model() {
    Map<String, Class<?>> classes = new HashMap<>();
    classes.put("android.content.Context", FakeContext.class);
    classes.put("android.media.AudioFormat", FakeFormat.class);
    classes.put("android.media.audiopolicy.AudioMixingRule", FakeRule.class);
    classes.put("android.media.audiopolicy.AudioMixingRule$Builder", FakeRuleBuilder.class);
    classes.put("android.media.audiopolicy.AudioMix", FakeMix.class);
    classes.put("android.media.audiopolicy.AudioMix$Builder", FakeMixBuilder.class);
    classes.put("android.media.audiopolicy.AudioPolicy", FakePolicy.class);
    classes.put("android.media.audiopolicy.AudioPolicy$Builder", FakePolicyBuilder.class);
    classes.put("android.media.AudioManager", FakeManager.class);
    classes.put("android.media.AudioRecord$Builder", FakeMicBuilder.class);
    return classes;
  }
  private static Object forbidden() { throw new AssertionError("Preflight created or invoked an audio resource"); }
  public static final class FakeContext { }
  public static final class FakeFormat { }
  public static final class FakeRule {
    public static final int MIX_ROLE_PLAYERS = 0, RULE_MATCH_ATTRIBUTE_USAGE = 1;
  }
  public static final class FakeRuleBuilder {
    public FakeRuleBuilder() { forbidden(); }
    public Object setTargetMixRole(int role) { return forbidden(); }
    public Object addMixRule(int rule, Object value) { return forbidden(); }
    public Object build() { return forbidden(); }
  }
  public static final class FakeMix { public static final int ROUTE_FLAG_LOOP_BACK_RENDER = 3; }
  public static final class FakeMixBuilder {
    public FakeMixBuilder(FakeRule rule) { forbidden(); }
    public Object setFormat(FakeFormat format) { return forbidden(); }
    public Object setRouteFlags(int flags) { return forbidden(); }
    public Object build() { return forbidden(); }
  }
  public static final class FakePolicy { public Object createAudioRecordSink(FakeMix mix) { return forbidden(); } }
  public static final class FakePolicyBuilder {
    public FakePolicyBuilder(FakeContext context) { forbidden(); }
    public Object addMix(FakeMix mix) { return forbidden(); }
    public Object build() { return forbidden(); }
  }
  public static final class FakeManager {
    static int registerAudioPolicyStatic(FakePolicy policy) { forbidden(); return 0; }
    static void unregisterAudioPolicyAsyncStatic(FakePolicy policy) { forbidden(); }
  }
  public static final class FakeMicBuilder { Object setContext(FakeContext context) { return forbidden(); } }
  public static final class MissingMixRule {
    public MissingMixRule() { forbidden(); }
    public Object setTargetMixRole(int role) { return forbidden(); }
    public Object build() { return forbidden(); }
  }
  public static final class WrongRoute { public static final int ROUTE_FLAG_LOOP_BACK_RENDER = 2; }
  public static final class NonStaticManager {
    int registerAudioPolicyStatic(FakePolicy policy) { forbidden(); return 0; }
    static void unregisterAudioPolicyAsyncStatic(FakePolicy policy) { forbidden(); }
  }
  public static final class WrongRegisterResult {
    static Object registerAudioPolicyStatic(FakePolicy policy) { return forbidden(); }
    static void unregisterAudioPolicyAsyncStatic(FakePolicy policy) { forbidden(); }
  }
  public static final class MissingMicContext { }
  public static final class MissingSink { }
}
