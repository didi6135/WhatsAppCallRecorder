import { NativeModules, Platform } from 'react-native';

export type DriveBackupPhase = 'disconnected' | 'ready' | 'uploading' | 'paused' | 'needsConsent' | 'error';
export interface DriveBackupStatus {
  enabled: boolean;
  connected: boolean;
  accountEmail: string | null;
  folderId: string | null;
  folderName: string | null;
  phase: DriveBackupPhase;
  queuedCount: number;
  uploadedCount: number;
  failedCount: number;
  uploadingId: string | null;
  uploadedBytes: number;
  totalBytes: number;
  errorCode: string | null;
  errorMessage: string | null;
  items?: Array<{ id: string; state: 'pending' | 'uploading' | 'uploaded' | 'failed'; errorCode?: string | null }>;
}

interface DriveBackupModule {
  getStatus(): Promise<unknown>;
  connect(): Promise<unknown>;
  chooseFolder(): Promise<unknown>;
  setEnabled(enabled: boolean): Promise<unknown>;
  retryPending(): Promise<unknown>;
  disconnect(): Promise<unknown>;
}

const messages: Record<string, string> = {
  CONFIGURATION_REQUIRED: 'חיבור Google Drive עדיין לא הוגדר עבור האפליקציה ב־Google Cloud. לא ניתן לגבות עד שההגדרה תושלם.',
  AUTH_REQUIRED: 'Google מבקש אישור מחדש לחשבון. חברו את החשבון שוב כדי להמשיך בגיבוי.',
  FOLDER_UNAVAILABLE: 'התיקייה אינה זמינה או שהגישה אליה השתנתה. בחרו תיקייה זמינה ואשרו את הגיבוי מחדש.',
  ACCOUNT_CHANGED: 'החשבון השתנה. חברו את החשבון הרצוי, בחרו תיקייה ואשרו מחדש את הגיבוי.',
  NETWORK: 'אין חיבור זמין ל־Google Drive. ההקלטות נשארות בטלפון; אפשר לנסות שוב כשהחיבור חוזר.',
  RATE_LIMIT: 'Google הגביל זמנית את ההעלאות. ההקלטות נשארות בטלפון וניתן לנסות שוב בהמשך.',
  STORAGE_FULL: 'אין מספיק מקום פנוי ב־Google Drive. פנו מקום בחשבון ונסו שוב.',
  LOCAL_FILE_MISSING: 'אחד הקבצים אינו זמין עוד בטלפון ולכן לא ניתן לגבות אותו.',
  LOCAL_FILE_CHANGED: 'אחד הקבצים השתנה בטלפון. הוא לא הועלה; בדקו את ההקלטה לפני ניסיון נוסף.',
  REMOTE_MISMATCH: 'לא ניתן לאמת את הקובץ שהועלה. הוא לא מסומן כמגובה; אפשר לנסות שוב.',
  RETRY_LIMIT: 'כמה ניסיונות גיבוי לא הושלמו. בדקו את החיבור והחשבון ואז נסו שוב.',
  LOCAL_QUEUE_UNAVAILABLE: 'לא ניתן לקרוא את רשימת הגיבוי בטלפון. ההקלטות המקומיות נשמרות; נסו שוב.',
  DRIVE_MODULE_UNAVAILABLE: 'גיבוי Google Drive אינו זמין בבנייה הזו של האפליקציה.',
  DRIVE_ANDROID_ONLY: 'החיבור הזה ל־Google Drive זמין כרגע בגרסת Android.',
  DRIVE_STATUS_INVALID: 'לא ניתן לאמת את מצב הגיבוי כרגע. נסו לבדוק שוב.',
  RECORDING_ACTIVE: 'אפשר לשנות את החשבון והתיקייה לאחר סיום ההקלטה.',
  RECORDING_BUSY: 'סיימו את ההקלטה הפעילה לפני שינוי החשבון או תיקיית הגיבוי.',
  FOREGROUND_REQUIRED: 'פתחו את האפליקציה על המסך וחזרו להגדרות הגיבוי כדי להמשיך.',
  CONNECTION_BUSY: 'חיבור או בחירת תיקייה כבר מתבצעים. סיימו את החלון של Google וחזרו לכאן.',
  NOT_CONNECTED: 'חברו חשבון Google ובחרו תיקיית גיבוי כדי להמשיך.',
  UPLOAD_FAILED: 'העלאת אחת ההקלטות לא הושלמה. הקובץ בטלפון נשמר; נסו לגבות שוב.',
  HTTP_ERROR: 'החיבור ל־Google Drive לא הושלם. בדקו את החיבור לחשבון ונסו שוב.',
  CREATE_CONFLICT: 'לא ניתן היה להשלים את יצירת קובץ הגיבוי. הוא אינו מסומן כמגובה; נסו שוב.',
};

export const driveErrorMessage = (code: string | null, nativeMessage?: string | null): string =>
  (code && messages[code]) || nativeMessage || 'הפעולה לא הושלמה. ההקלטות בטלפון נשמרות; נסו שוב.';

export class DriveBackupError extends Error {
  constructor(readonly code: string, message: string) { super(message); this.name = 'DriveBackupError'; }
}

const invalidStatus = (): never => { throw new DriveBackupError('DRIVE_STATUS_INVALID', messages.DRIVE_STATUS_INVALID); };
const textOrNull = (value: unknown): string | null => value === null ? null : typeof value === 'string' ? value : invalidStatus();
const count = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) && value >= 0 && Number.isInteger(value) ? value : invalidStatus();

// Copy only the public status contract. Native OAuth credentials never belong in UI state.
export function normalizeDriveBackupStatus(value: unknown): DriveBackupStatus {
  if (!value || typeof value !== 'object') return invalidStatus();
  const raw = value as Record<string, unknown>;
  const phases: DriveBackupPhase[] = ['disconnected', 'ready', 'uploading', 'paused', 'needsConsent', 'error'];
  if (typeof raw.enabled !== 'boolean' || typeof raw.connected !== 'boolean' || !phases.includes(raw.phase as DriveBackupPhase)) return invalidStatus();
  const status: DriveBackupStatus = {
    enabled: raw.enabled, connected: raw.connected, phase: raw.phase as DriveBackupPhase,
    accountEmail: textOrNull(raw.accountEmail), folderId: textOrNull(raw.folderId), folderName: textOrNull(raw.folderName),
    queuedCount: count(raw.queuedCount), uploadedCount: count(raw.uploadedCount), failedCount: count(raw.failedCount),
    uploadingId: textOrNull(raw.uploadingId), uploadedBytes: count(raw.uploadedBytes), totalBytes: count(raw.totalBytes),
    errorCode: textOrNull(raw.errorCode), errorMessage: textOrNull(raw.errorMessage),
  };
  if (raw.items !== undefined) {
    if (!Array.isArray(raw.items) || raw.items.length > 1000) return invalidStatus();
    status.items = raw.items.map((item: unknown) => {
      if (!item || typeof item !== 'object') return invalidStatus();
      const entry = item as Record<string, unknown>;
      if (typeof entry.id !== 'string' || !['pending', 'uploading', 'uploaded', 'failed'].includes(entry.state as string)) return invalidStatus();
      return { id: entry.id, state: entry.state as NonNullable<DriveBackupStatus['items']>[number]['state'], ...(entry.errorCode === undefined ? {} : { errorCode: textOrNull(entry.errorCode) }) };
    });
  }
  return status;
}

const moduleFor = (): DriveBackupModule => {
  if (Platform.OS !== 'android') throw new DriveBackupError('DRIVE_ANDROID_ONLY', messages.DRIVE_ANDROID_ONLY);
  const module = NativeModules.DriveBackup as DriveBackupModule | undefined;
  if (!module) throw new DriveBackupError('DRIVE_MODULE_UNAVAILABLE', messages.DRIVE_MODULE_UNAVAILABLE);
  return module;
};

async function call(action: (module: DriveBackupModule) => Promise<unknown>): Promise<DriveBackupStatus> {
  try { return normalizeDriveBackupStatus(await action(moduleFor())); }
  catch (failure) {
    if (failure instanceof DriveBackupError) throw failure;
    const code = failure && typeof failure === 'object' && 'code' in failure && typeof failure.code === 'string' ? failure.code : 'DRIVE_ACTION_FAILED';
    // Unknown bridge/provider failures use generic copy; do not surface raw server responses.
    throw new DriveBackupError(code, driveErrorMessage(code));
  }
}

export const NativeDriveBackup = {
  getStatus: (): Promise<DriveBackupStatus> => call(module => module.getStatus()),
  connect: (): Promise<DriveBackupStatus> => call(module => module.connect()),
  chooseFolder: (): Promise<DriveBackupStatus> => call(module => module.chooseFolder()),
  setEnabled: (enabled: boolean): Promise<DriveBackupStatus> => call(module => module.setEnabled(enabled)),
  retryPending: (): Promise<DriveBackupStatus> => call(module => module.retryPending()),
  disconnect: (): Promise<DriveBackupStatus> => call(module => module.disconnect()),
};
