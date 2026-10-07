package com.didi4164.WhatsAppCallRecorder;

/** Fixed setup diagnostics only; no exception text, endpoints, identities or runtime decisions. */
public final class WirelessActivationFailure {
  public final String errorCode, hebrewMessage, englishMessage;
  private WirelessActivationFailure(String code, String hebrew, String english) {
    errorCode = code; hebrewMessage = hebrew; englishMessage = english;
  }
  public static WirelessActivationFailure forStage(String stage) {
    if (stage == null) return null;
    switch (stage) {
      case "DISCOVERY": return new WirelessActivationFailure("ACTIVATION_DISCOVERY_FAILED",
          "חיבור ההפעלה לא נמצא. ודאו שניפוי באגים אלחוטי פעיל. אפשר להזין ידנית את יציאת החיבור שמוצגת בהגדרות.",
          "The activation connection was not found. Enable Wireless debugging. You can enter the connection port shown in Android Settings manually.");
      case "ADB_CONNECTION": return new WirelessActivationFailure("ACTIVATION_ADB_CONNECTION_FAILED",
          "לא ניתן להתחבר להפעלה. ודאו שניפוי באגים אלחוטי פעיל ושהצימוד הושלם; אם האישור הוסר, בצעו צימוד מחדש.",
          "The activation connection failed. Check Wireless debugging and pairing. Pair again if authorization was removed.");
      case "BOOTSTRAP": return new WirelessActivationFailure("ACTIVATION_BOOTSTRAP_FAILED",
          "רכיב ההקלטה לא התחיל. חזרו למסך האפליקציה ונסו להפעיל אותו שוב. אם זה חוזר, אפשר להשתמש בהקלטת מיקרופון.",
          "The recording component did not start. Return to the app and activate it again. If this persists, microphone recording is available.");
      case "AUTHENTICATION": return new WirelessActivationFailure("ACTIVATION_AUTHENTICATION_FAILED",
          "רכיב ההקלטה לא אישר שהוא מוכן בטלפון הזה. נסו הפעלה מחדש מתוך האפליקציה. אם זה חוזר, אפשר להשתמש בהקלטת מיקרופון.",
          "The recording component did not confirm readiness on this phone. Activate it again from the app. If this persists, microphone recording is available.");
      case "HANDOFF": return new WirelessActivationFailure("ACTIVATION_HANDOFF_FAILED",
          "העברת השליטה לרכיב ההקלטה לא הושלמה. חזרו לאפליקציה ונסו להפעיל שוב.",
          "Ownership transfer to the recording component did not finish. Return to the app and activate it again.");
      case "DETACH": return new WirelessActivationFailure("ACTIVATION_DETACH_FAILED",
          "סגירת חיבור ההפעלה לא הושלמה. חזרו לאפליקציה ונסו להפעיל שוב.",
          "Closing the activation connection did not finish. Return to the app and activate it again.");
      case "LIVENESS": return new WirelessActivationFailure("ACTIVATION_LIVENESS_FAILED",
          "רכיב ההקלטה נעצר אחרי ההפעלה. הפעילו אותו שוב כשהאפליקציה פתוחה. אם זה חוזר, בדקו מגבלות סוללה ורקע של המכשיר.",
          "The recording component stopped after activation. Activate it again while the app is open. If this persists, check this phone's battery and background restrictions.");
      case "READINESS_OWNER": return new WirelessActivationFailure("ACTIVATION_READINESS_OWNER_FAILED",
          "לא ניתן להשאיר את רכיב ההקלטה פעיל ברקע. חזרו לאפליקציה, ודאו שההתראות מותרות ונסו שוב.",
          "The recording component could not stay active in the background. Return to the app, check notification access and try again.");
      default: return null;
    }
  }
}
