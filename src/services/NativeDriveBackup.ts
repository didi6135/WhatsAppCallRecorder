import { NativeModules, Platform } from 'react-native';

export type DriveBackupPhase = 'disconnected' | 'ready' | 'uploading' | 'paused' | 'needsConsent' | 'error';
export interface DriveBackupActionError {
  action: 'connect' | 'folder';
  stage: 'authorize' | 'pickerResult' | 'selection';
  code: string;
  authStatusCode?: number;
  activityResultCode?: number;
}
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
  // A failed setup attempt is separate from the current destination/upload queue.
  lastActionError?: DriveBackupActionError | null;
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

const knownCode = (code: unknown): code is string => typeof code === 'string' && Object.prototype.hasOwnProperty.call(messages, code);
// UI copy is always app-owned, including failures from newer or incompatible native builds.
export const driveErrorMessage = (code: string | null, _nativeMessage?: string | null): string =>
  knownCode(code) ? messages[code] : 'הפעולה לא הושלמה. ההקלטות בטלפון נשמרות; נסו שוב.';

const diagnosticCodes = new Set([
  'CONFIGURATION_REQUIRED', 'AUTH_REQUIRED', 'ACCOUNT_CHANGED', 'FOLDER_UNAVAILABLE',
  'NETWORK', 'RATE_LIMIT', 'STORAGE_FULL', 'LOCAL_QUEUE_UNAVAILABLE', 'FOREGROUND_REQUIRED',
  'RECORDING_BUSY', 'CONNECTION_BUSY', 'NOT_CONNECTED', 'UPLOAD_FAILED', 'HTTP_ERROR', 'REMOTE_MISMATCH',
]);
const attemptMessages: Record<string, string> = {
  CONFIGURATION_REQUIRED: 'Google לא אישר את ניסיון החיבור עבור גרסה זו. נדרשת בדיקה של הגדרת האפליקציה ב־Google Cloud.',
  AUTH_REQUIRED: 'Google מבקש אישור לחשבון. נסו את חיבור החשבון שוב.',
  ACCOUNT_CHANGED: 'לא ניתן היה לאמת את החשבון שנבחר בניסיון הזה. נסו לחבר את החשבון הרצוי שוב.',
  FOLDER_UNAVAILABLE: 'לא ניתן היה לאמת גישה לתיקייה בניסיון הבחירה. נסו לבחור תיקייה זמינה.',
  NETWORK: 'ניסיון החיבור ל־Google Drive לא הושלם בגלל הרשת. אפשר לנסות שוב כשהחיבור חוזר.',
  RATE_LIMIT: 'Google הגביל זמנית את הבקשה. אפשר לנסות שוב בהמשך.',
  STORAGE_FULL: 'Google מדווח שאין מספיק מקום פנוי בחשבון. פנו מקום ונסו שוב.',
  LOCAL_QUEUE_UNAVAILABLE: 'לא ניתן היה לקרוא את הגדרות הגיבוי המקומיות בניסיון הזה. נסו שוב.',
  UPLOAD_FAILED: 'Google לא השלים את ניסיון החיבור או הבחירה. אפשר לנסות שוב.',
  HTTP_ERROR: 'הבקשה ל־Google Drive לא הושלמה. בדקו את החיבור ונסו שוב.',
  REMOTE_MISMATCH: 'לא ניתן היה לאמת את התשובה מ־Google Drive בניסיון הזה. נסו שוב.',
};
export const driveActionErrorMessage = (code: string): string =>
  Object.prototype.hasOwnProperty.call(attemptMessages, code) ? attemptMessages[code] : driveErrorMessage(code);
const boundedInteger = (value: unknown, min: number, max: number): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;

// Optional diagnostics cannot invalidate a healthy upload queue. Never copy provider bodies or extras.
const actionErrorOrNull = (value: unknown): DriveBackupActionError | null => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw = value as Record<string, unknown>;
  if ((raw.action !== 'connect' && raw.action !== 'folder') ||
    !['authorize', 'pickerResult', 'selection'].includes(raw.stage as string) ||
    typeof raw.code !== 'string' || !diagnosticCodes.has(raw.code) ||
    (raw.authStatusCode !== undefined && !boundedInteger(raw.authStatusCode, 0, 65535)) ||
    (raw.activityResultCode !== undefined && !boundedInteger(raw.activityResultCode, -65535, 65535))) return null;
  return {
    action: raw.action,
    stage: raw.stage as DriveBackupActionError['stage'],
    code: raw.code,
    ...(raw.authStatusCode === undefined ? {} : { authStatusCode: raw.authStatusCode as number }),
    ...(raw.activityResultCode === undefined ? {} : { activityResultCode: raw.activityResultCode as number }),
  };
};

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
  const rawErrorCode = textOrNull(raw.errorCode);
  const rawErrorMessage = textOrNull(raw.errorMessage);
  const errorCode = rawErrorCode === null ? null : knownCode(rawErrorCode) ? rawErrorCode : 'DRIVE_ACTION_FAILED';
  const status: DriveBackupStatus = {
    enabled: raw.enabled, connected: raw.connected, phase: raw.phase as DriveBackupPhase,
    accountEmail: textOrNull(raw.accountEmail), folderId: textOrNull(raw.folderId), folderName: textOrNull(raw.folderName),
    queuedCount: count(raw.queuedCount), uploadedCount: count(raw.uploadedCount), failedCount: count(raw.failedCount),
    uploadingId: textOrNull(raw.uploadingId), uploadedBytes: count(raw.uploadedBytes), totalBytes: count(raw.totalBytes),
    errorCode, errorMessage: rawErrorMessage === null ? null : driveErrorMessage(errorCode),
  };
  if (raw.lastActionError !== undefined) status.lastActionError = actionErrorOrNull(raw.lastActionError);
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
    const reportedCode = failure && typeof failure === 'object' && 'code' in failure ? failure.code : null;
    const code = knownCode(reportedCode) ? reportedCode : 'DRIVE_ACTION_FAILED';
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
