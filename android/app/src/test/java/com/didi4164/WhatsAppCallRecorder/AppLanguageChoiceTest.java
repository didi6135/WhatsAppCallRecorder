package com.didi4164.WhatsAppCallRecorder;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppLanguageChoiceTest {
  @Test public void deviceLanguagesIncludeHebrewAliasAndRegions() {
    for (String value : new String[]{"he", "he-IL", "HE_il", "iw-IL"}) assertEquals("he", AppLanguageChoice.fromDevice(value));
    for (String value : new String[]{null, "", "en-US", "fr-FR", "hebrew", "iwish"}) assertEquals("en", AppLanguageChoice.fromDevice(value));
  }
  @Test public void savedSelectionWinsAfterRestart() {
    assertEquals("en", AppLanguageChoice.effective("en", "he-IL"));
    assertEquals("he", AppLanguageChoice.effective("he", "en-US"));
    assertEquals("he", AppLanguageChoice.effective("unknown", "iw-IL"));
  }
  @Test public void onlySupportedPreferencesAreAccepted() {
    assertTrue(AppLanguageChoice.valid("he")); assertTrue(AppLanguageChoice.valid("en"));
    assertFalse(AppLanguageChoice.valid("HE")); assertFalse(AppLanguageChoice.valid(null));
    assertEquals("English", AppLanguageChoice.choose("en", "Hebrew", "English"));
    assertEquals("Hebrew", AppLanguageChoice.choose("he", "Hebrew", "English"));
  }
}
