package com.didi4164.WhatsAppCallRecorder

/** Only these app-owned messages cross the RN boundary, never HTTP bodies or token-bearing URLs. */
object DriveBackupErrors {
  fun message(code: String): String = when (code) {
    "CONFIGURATION_REQUIRED" -> "חיבור Google Drive עדיין לא הוגדר לגרסה הזו של האפליקציה."
    "AUTH_REQUIRED" -> "נדרש אישור מחדש לחשבון Google. פתחו את חיבור החשבון כדי להמשיך."
    "ACCOUNT_CHANGED" -> "חשבון Google אינו תואם לחשבון שנבחר. חברו שוב את החשבון הרצוי."
    "FOLDER_UNAVAILABLE" -> "התיקייה אינה זמינה לכתיבה. בחרו תיקייה אחרת או חדשו את ההרשאה."
    "NETWORK" -> "החיבור לרשת לא הצליח. ההקלטות נשמרו בטלפון וההעלאה תנסה שוב."
    "RATE_LIMIT" -> "Google עצרה זמנית את ההעלאות. ננסה שוב בהמשך."
    "STORAGE_FULL" -> "אין מספיק מקום ב-Google Drive. פנו מקום ואז נסו שוב."
    "LOCAL_FILE_MISSING" -> "קובץ ההקלטה כבר אינו נמצא בטלפון. הוא לא נוצר מחדש."
    "LOCAL_FILE_CHANGED" -> "קובץ ההקלטה השתנה. ההעלאה נעצרה כדי לשמור על התאמה לקובץ."
    "REMOTE_MISMATCH" -> "הקובץ ב-Drive אינו תואם להקלטה. הוא לא סומן כגיבוי שהושלם."
    "RETRY_LIMIT" -> "ההעלאה נעצרה אחרי מספר ניסיונות. אפשר לנסות שוב; ההקלטה נשארה בטלפון."
    "LOCAL_QUEUE_UNAVAILABLE" -> "מידע הגיבוי המקומי אינו זמין. הגיבוי נעצר כדי למנוע העלאות כפולות."
    "FOREGROUND_REQUIRED" -> "פתחו את האפליקציה כדי לשנות את חיבור Google Drive."
    "RECORDING_BUSY" -> "עצרו את ההקלטה לפני שינוי חיבור Google Drive."
    "CONNECTION_BUSY" -> "חיבור Google כבר מתבצע. המתינו לסיום."
    "NOT_CONNECTED" -> "בחרו חשבון ותיקייה ב-Google Drive לפני הפעלת הגיבוי."
    else -> "העלאת הגיבוי לא הושלמה. ההקלטות נשמרו בטלפון. אפשר לנסות שוב."
  }
}
