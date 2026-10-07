package com.didi4164.WhatsAppCallRecorder;

/** CommonStatusCodes classification; an internal error must not imply missing user consent. */
public final class DriveGoogleStatus {
  private DriveGoogleStatus() { }
  public static String code(Integer status) {
    if (status == null) return "AUTH_REQUIRED";
    switch (status) {
      case 10: return "CONFIGURATION_REQUIRED";
      case 7: return "NETWORK";
      case 8: return "GOOGLE_INTERNAL_ERROR";
      default: return "AUTH_REQUIRED";
    }
  }
  public static boolean retryable(Integer status) { return status != null && (status == 7 || status == 8); }
  public static boolean needsConsent(Integer status) { return "AUTH_REQUIRED".equals(code(status)); }
}
