package com.didi4164.WhatsAppCallRecorder

import android.content.Context
import java.util.Locale

/** App-owned copy only. Changing language never activates or stops recording. */
object AppText {
  private const val PREFS = "wa_reco_language"
  @Volatile private var language = AppLanguageChoice.fromDevice(Locale.getDefault().toLanguageTag())
  fun initialize(context: Context) {
    language = AppLanguageChoice.effective(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", null), Locale.getDefault().toLanguageTag())
  }
  @Synchronized fun setLanguage(context: Context, requested: String) {
    require(AppLanguageChoice.valid(requested)) { "Unsupported app language" }
    check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", requested).commit()) { "Language preference could not be saved" }
    language = requested
  }
  fun choose(hebrew: String, english: String): String = AppLanguageChoice.choose(language, hebrew, english)
}
