package com.codaki.usbaudio;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Checks the exact existing capture API without constructing a mix, policy or recorder. */
final class AudioCaptureApi {
  interface ClassResolver { Class<?> resolve(String name) throws ClassNotFoundException; }
  private AudioCaptureApi() { }
  static void requireAvailable() { requireAvailable(name -> Class.forName(name, false, AudioCaptureApi.class.getClassLoader())); }

  static void requireAvailable(ClassResolver classes) {
    try {
      Class<?> context = classes.resolve("android.content.Context");
      Class<?> format = classes.resolve("android.media.AudioFormat");
      Class<?> rule = classes.resolve("android.media.audiopolicy.AudioMixingRule");
      constant(rule, "MIX_ROLE_PLAYERS", 0); constant(rule, "RULE_MATCH_ATTRIBUTE_USAGE", 1);
      Class<?> ruleBuilder = classes.resolve("android.media.audiopolicy.AudioMixingRule$Builder");
      ruleBuilder.getConstructor();
      ruleBuilder.getMethod("setTargetMixRole", int.class);
      ruleBuilder.getMethod("addMixRule", int.class, Object.class);
      ruleBuilder.getMethod("build");
      Class<?> mix = classes.resolve("android.media.audiopolicy.AudioMix");
      constant(mix, "ROUTE_FLAG_LOOP_BACK_RENDER", 3);
      Class<?> mixBuilder = classes.resolve("android.media.audiopolicy.AudioMix$Builder");
      mixBuilder.getConstructor(rule);
      mixBuilder.getMethod("setFormat", format);
      mixBuilder.getMethod("setRouteFlags", int.class);
      mixBuilder.getMethod("build");
      Class<?> policy = classes.resolve("android.media.audiopolicy.AudioPolicy");
      Class<?> policyBuilder = classes.resolve("android.media.audiopolicy.AudioPolicy$Builder");
      policyBuilder.getConstructor(context);
      policyBuilder.getMethod("addMix", mix);
      policyBuilder.getMethod("build");
      policy.getMethod("createAudioRecordSink", mix);
      Class<?> manager = classes.resolve("android.media.AudioManager");
      Method register = accessible(manager, "registerAudioPolicyStatic", policy);
      Method unregister = accessible(manager, "unregisterAudioPolicyAsyncStatic", policy);
      if (!Modifier.isStatic(register.getModifiers()) || register.getReturnType() != int.class
          || !Modifier.isStatic(unregister.getModifiers())) throw new NoSuchMethodException();
      accessible(classes.resolve("android.media.AudioRecord$Builder"), "setContext", context);
    } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {
      throw new UnsupportedOperationException("Required shell audio API is unavailable on this device.", unavailable);
    }
  }
  private static Method accessible(Class<?> owner, String name, Class<?> argument) throws NoSuchMethodException {
    Method method = owner.getDeclaredMethod(name, argument); method.setAccessible(true); return method;
  }
  private static void constant(Class<?> owner, String name, int expected) throws ReflectiveOperationException {
    Field field = owner.getField(name);
    if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class || field.getInt(null) != expected)
      throw new NoSuchFieldException();
  }
}
