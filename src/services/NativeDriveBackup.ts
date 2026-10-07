import { t } from '../i18n/core';
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
  get GOOGLE_INTERNAL_ERROR() { return t('googleInternalError'); },
  get CONFIGURATION_REQUIRED() { return t('copy380'); },
  get AUTH_REQUIRED() { return t('copy381'); },
  get FOLDER_UNAVAILABLE() { return t('copy382'); },
  get ACCOUNT_CHANGED() { return t('copy383'); },
  get NETWORK() { return t('copy384'); },
  get RATE_LIMIT() { return t('copy385'); },
  get STORAGE_FULL() { return t('copy386'); },
  get LOCAL_FILE_MISSING() { return t('copy387'); },
  get LOCAL_FILE_CHANGED() { return t('copy388'); },
  get REMOTE_MISMATCH() { return t('copy389'); },
  get RETRY_LIMIT() { return t('copy390'); },
  get LOCAL_QUEUE_UNAVAILABLE() { return t('copy391'); },
  get DRIVE_MODULE_UNAVAILABLE() { return t('copy392'); },
  get DRIVE_ANDROID_ONLY() { return t('copy393'); },
  get DRIVE_STATUS_INVALID() { return t('copy394'); },
  get RECORDING_ACTIVE() { return t('copy395'); },
  get RECORDING_BUSY() { return t('copy396'); },
  get FOREGROUND_REQUIRED() { return t('copy397'); },
  get CONNECTION_BUSY() { return t('copy398'); },
  get NOT_CONNECTED() { return t('copy399'); },
  get UPLOAD_FAILED() { return t('copy400'); },
  get HTTP_ERROR() { return t('copy401'); },
  get CREATE_CONFLICT() { return t('copy402'); },
};

const knownCode = (code: unknown): code is string => typeof code === 'string' && Object.prototype.hasOwnProperty.call(messages, code);
// UI copy is always app-owned, including failures from newer or incompatible native builds.
export const driveErrorMessage = (code: string | null, _nativeMessage?: string | null): string =>
  knownCode(code) ? messages[code] : t('copy403');

const diagnosticCodes = new Set([
  'GOOGLE_INTERNAL_ERROR',
  'CONFIGURATION_REQUIRED', 'AUTH_REQUIRED', 'ACCOUNT_CHANGED', 'FOLDER_UNAVAILABLE',
  'NETWORK', 'RATE_LIMIT', 'STORAGE_FULL', 'LOCAL_QUEUE_UNAVAILABLE', 'FOREGROUND_REQUIRED',
  'RECORDING_BUSY', 'CONNECTION_BUSY', 'NOT_CONNECTED', 'UPLOAD_FAILED', 'HTTP_ERROR', 'REMOTE_MISMATCH',
]);
const attemptMessages: Record<string, string> = {
  get GOOGLE_INTERNAL_ERROR() { return t('googleInternalError'); },
  get CONFIGURATION_REQUIRED() { return t('copy404'); },
  get AUTH_REQUIRED() { return t('copy405'); },
  get ACCOUNT_CHANGED() { return t('copy406'); },
  get FOLDER_UNAVAILABLE() { return t('copy407'); },
  get NETWORK() { return t('copy408'); },
  get RATE_LIMIT() { return t('copy409'); },
  get STORAGE_FULL() { return t('copy410'); },
  get LOCAL_QUEUE_UNAVAILABLE() { return t('copy411'); },
  get UPLOAD_FAILED() { return t('copy412'); },
  get HTTP_ERROR() { return t('copy413'); },
  get REMOTE_MISMATCH() { return t('copy414'); },
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
