package com.didi4164.WhatsAppCallRecorder;

import java.util.Locale;

/** Locale selection only; never changes an Activity, capture service or system setting. */
public final class AppLanguageChoice {
  private AppLanguageChoice() { }
  public static boolean valid(String language) { return "he".equals(language) || "en".equals(language); }
  public static String fromDevice(String locale) {
    String value = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    String base = value.split("[-_]", 2)[0];
    return "he".equals(base) || "iw".equals(base) ? "he" : "en";
  }
  public static String effective(String saved, String device) { return valid(saved) ? saved : fromDevice(device); }
  public static String choose(String language, String hebrew, String english) { return "he".equals(language) ? hebrew : english; }
}
