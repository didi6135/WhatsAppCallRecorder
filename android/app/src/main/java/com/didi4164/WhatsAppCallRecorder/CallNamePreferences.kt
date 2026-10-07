package com.didi4164.WhatsAppCallRecorder

import android.content.Context

/** Explicit per-install opt-in, separate from automatic recording and cloud backup preferences. */
object CallNamePreferences {
  private const val PREFS = "call_display_names"
  private const val ENABLED = "enabled"

  fun isEnabled(context: Context): Boolean = runCatching {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
  }.getOrDefault(false)

  @Synchronized fun setEnabled(context: Context, enabled: Boolean): Boolean {
    val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val previous = preferences.getBoolean(ENABLED, false)
    if (previous == enabled) return true
    val saved = runCatching { preferences.edit().putBoolean(ENABLED, enabled).commit() }.getOrDefault(false)
    if (!saved) runCatching { preferences.edit().putBoolean(ENABLED, previous).commit() }
    return saved
  }
}
