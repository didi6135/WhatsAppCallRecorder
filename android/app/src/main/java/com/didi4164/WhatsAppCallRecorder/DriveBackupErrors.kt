package com.didi4164.WhatsAppCallRecorder

/** Only these app-owned messages cross the RN boundary, never HTTP bodies or token-bearing URLs. */
object DriveBackupErrors {
  fun message(code: String): String = when (code) {
    "GOOGLE_INTERNAL_ERROR" -> AppText.choose("Google לא השלים את האישור בגלל שגיאה פנימית. נסו שוב; אם הכשל חוזר, יש לבדוק את הגדרת האפליקציה אצל Google.", "Google could not complete authorization because of an internal error. Try again; if it continues, the app's Google configuration needs checking.")
    "CONFIGURATION_REQUIRED" -> AppText.choose("חיבור Google Drive עדיין לא הוגדר לגרסה הזו של האפליקציה.", "Google Drive is not configured for this app version.")
    "AUTH_REQUIRED" -> AppText.choose("נדרש אישור מחדש לחשבון Google. פתחו את חיבור החשבון כדי להמשיך.", "Google authorization is required again. Connect the account to continue.")
    "ACCOUNT_CHANGED" -> AppText.choose("חשבון Google אינו תואם לחשבון שנבחר. חברו שוב את החשבון הרצוי.", "The Google account does not match the selected account. Connect the intended account again.")
    "FOLDER_UNAVAILABLE" -> AppText.choose("התיקייה אינה זמינה לכתיבה. בחרו תיקייה אחרת או חדשו את ההרשאה.", "The folder is not writable. Select another folder or renew access.")
    "NETWORK" -> AppText.choose("החיבור לרשת לא הצליח. ההקלטות נשמרו בטלפון וההעלאה תנסה שוב.", "The network connection failed. Local recordings remain available and upload will retry.")
    "RATE_LIMIT" -> AppText.choose("Google עצרה זמנית את ההעלאות. ננסה שוב בהמשך.", "Google temporarily limited uploads. Upload will retry later.")
    "STORAGE_FULL" -> AppText.choose("אין מספיק מקום ב-Google Drive. פנו מקום ואז נסו שוב.", "There is not enough free space in Google Drive. Free some space and retry.")
    "LOCAL_FILE_MISSING" -> AppText.choose("קובץ ההקלטה כבר אינו נמצא בטלפון. הוא לא נוצר מחדש.", "The recording file is no longer on this phone. It was not recreated.")
    "LOCAL_FILE_CHANGED" -> AppText.choose("קובץ ההקלטה השתנה. ההעלאה נעצרה כדי לשמור על התאמה לקובץ.", "The recording file changed. Upload stopped to preserve file consistency.")
    "REMOTE_MISMATCH" -> AppText.choose("הקובץ ב-Drive אינו תואם להקלטה. הוא לא סומן כגיבוי שהושלם.", "The Drive file does not match the recording. It was not marked as backed up.")
    "RETRY_LIMIT" -> AppText.choose("ההעלאה נעצרה אחרי מספר ניסיונות. אפשר לנסות שוב; ההקלטה נשארה בטלפון.", "Upload stopped after several attempts. You can retry; the local recording remains available.")
    "LOCAL_QUEUE_UNAVAILABLE" -> AppText.choose("מידע הגיבוי המקומי אינו זמין. הגיבוי נעצר כדי למנוע העלאות כפולות.", "Local backup information is unavailable. Backup stopped to prevent duplicate uploads.")
    "FOREGROUND_REQUIRED" -> AppText.choose("פתחו את האפליקציה כדי לשנות את חיבור Google Drive.", "Open the app to change the Google Drive connection.")
    "RECORDING_BUSY" -> AppText.choose("עצרו את ההקלטה לפני שינוי חיבור Google Drive.", "Stop recording before changing the Google Drive connection.")
    "CONNECTION_BUSY" -> AppText.choose("חיבור Google כבר מתבצע. המתינו לסיום.", "Google connection is already in progress. Wait for it to finish.")
    "NOT_CONNECTED" -> AppText.choose("בחרו חשבון ותיקייה ב-Google Drive לפני הפעלת הגיבוי.", "Select a Google account and Drive folder before enabling backup.")
    else -> AppText.choose("העלאת הגיבוי לא הושלמה. ההקלטות נשמרו בטלפון. אפשר לנסות שוב.", "Backup upload did not finish. Local recordings remain available. You can retry.")
  }
}
