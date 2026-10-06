package com.didi4164.WhatsAppCallRecorder;

import android.app.PendingIntent;
import android.os.Build;
import android.os.Bundle;

/** Reads only whether a structural action contains an opaque PendingIntent; never invokes it. */
public final class NotificationActionPresence {
  private NotificationActionPresence() { }

  @SuppressWarnings("deprecation")
  public static boolean hasPendingIntent(Bundle extras, String key) {
    if (extras == null) return false;
    // AndroidX CallStyle writes these keys even when the action value is null.
    if (Build.VERSION.SDK_INT >= 33)
      return extras.getParcelable(key, PendingIntent.class) != null;
    return extras.getParcelable(key) instanceof PendingIntent;
  }
}
