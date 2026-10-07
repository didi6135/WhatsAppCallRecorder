import { localizedError, t } from '../i18n/core';
import type { TranslationKey } from '../i18n/catalog';
import { NativeModules, Platform } from 'react-native';

export type NativeRecordingStatus = 'captured' | 'silent' | 'interrupted' | 'recovered';
export type CaptureSource = 'microphone' | 'usb';
export interface CallNameStatus { enabled: boolean }
export interface AutoRecordingStatus {
  enabled: boolean;
  armed: boolean;
  notificationAccessGranted: boolean;
  notificationsEnabled: boolean;
  listenerConnected: boolean;
  helperConnected: boolean;
  state: 'off' | 'needs_access' | 'needs_activation' | 'ready' | 'recording' | 'paused' | 'error';
  error: string | null;
  errorCode?: string | null;
}
export const EMPTY_AUTO_RECORDING_STATUS: AutoRecordingStatus = {
  enabled: false, armed: false, notificationAccessGranted: false, listenerConnected: false,
  notificationsEnabled: false,
  helperConnected: false, state: 'off', error: null,
};

export interface RecorderStatus {
  isRecording: boolean;
  elapsedMs: number;
  /** Normalized combined RMS, from 0 to 1. */
  level: number;
  usbConnected: boolean;
  source: CaptureSource;
  /** Normalized RMS of the USB output and local microphone channels. */
  outputLevel: number;
  microphoneLevel: number;
  isSilenced: boolean;
  silentForMs: number;
  audioMode: number;
  error: string | null;
  errorCode?: string | null;
}

export interface NativeRecording {
  id: string;
  title: string;
  date: string;
  durationMs: number;
  filePath: string;
  fileSize: number;
  status: NativeRecordingStatus;
  wasSilenced: boolean;
  captureSource?: CaptureSource;
  channels?: number;
  outputSoundMs?: number;
  microphoneSoundMs?: number;
  startedAutomatically?: boolean;
  callDisplayName?: string;
  callPackage?: string;
  exportFileName?: string;
}

export interface RecorderDeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
  sdk: number;
}

export interface SystemAccessStatus {
  available: boolean;
  experimental?: boolean;
  paired: boolean;
  wirelessDebuggingEnabled: boolean;
  developerOptionsEnabled?: boolean;
  helperConnected: boolean;
  connecting: boolean;
  error: string | null;
  errorCode?: string | null;
  pairingSetup?: {
    active: boolean;
    discovering: boolean;
    pairing: boolean;
    error: string | null;
    errorCode?: string | null;
    localPortAvailable: boolean;
  };
}

export const EMPTY_SYSTEM_ACCESS_STATUS: SystemAccessStatus = {
  available: false,
  paired: false,
  wirelessDebuggingEnabled: false,
  helperConnected: false,
  connecting: false,
  error: null,
};

interface CallRecorderModule {
  getCallNameStatus(): Promise<CallNameStatus>;
  setCallNameEnabled(enabled: boolean): Promise<CallNameStatus>;
  getAutoRecordingStatus(): Promise<AutoRecordingStatus>;
  setAutoRecordingEnabled(enabled: boolean): Promise<void>;
  openNotificationAccessSetup(): Promise<void>;
  getStatus(): Promise<RecorderStatus>;
  startRecording(source: string): Promise<RecorderStatus>;
  stopRecording(): Promise<RecorderStatus>;
  listRecordings(): Promise<NativeRecording[]>;
  deleteRecording(id: string): Promise<void>;
  shareRecording(id: string): Promise<void>;
  openWhatsApp(): Promise<void>;
  getDeviceInfo(): Promise<RecorderDeviceInfo>;
  getSystemAccessStatus(): Promise<SystemAccessStatus>;
  prepareSystemPairing(): Promise<void>;
  pairSystemRecorder(pairingPort: number, pairingCode: string): Promise<void>;
  connectSystemRecorder(connectionPort: number): Promise<void>;
  openSystemAccessSetup(destination: 'developer' | 'wireless' | 'about'): Promise<void>;
}

export const EMPTY_RECORDER_STATUS: RecorderStatus = {
  isRecording: false,
  elapsedMs: 0,
  level: 0,
  usbConnected: false,
  source: 'microphone',
  outputLevel: 0,
  microphoneLevel: 0,
  isSilenced: false,
  silentForMs: 0,
  audioMode: 0,
  error: null,
};

const getModule = (): CallRecorderModule => {
  if (Platform.OS !== 'android') {
    throw new Error(t('copy415'));
  }

  const recorder = NativeModules.CallRecorder as CallRecorderModule | undefined;
  if (!recorder) {
    throw new Error(t('copy416'));
  }

  return recorder;
};

// Lifecycle, WAV finalization, and metadata belong to the Android service.
// JavaScript only asks for actions and reads the authoritative native state.
const activationErrorKeys: Record<string, TranslationKey> = {
  ACTIVATION_DISCOVERY_FAILED: 'activationDiscoveryFailed',
  ACTIVATION_ADB_CONNECTION_FAILED: 'activationAdbConnectionFailed',
  ACTIVATION_BOOTSTRAP_FAILED: 'activationBootstrapFailed',
  ACTIVATION_AUTHENTICATION_FAILED: 'activationAuthenticationFailed',
  ACTIVATION_HANDOFF_FAILED: 'activationHandoffFailed',
  ACTIVATION_DETACH_FAILED: 'activationDetachFailed',
  ACTIVATION_LIVENESS_FAILED: 'activationLivenessFailed',
  ACTIVATION_READINESS_OWNER_FAILED: 'activationReadinessOwnerFailed',
};
const errorKeys: Record<string, TranslationKey> = {
  ...activationErrorKeys,
  RECORDING_BUSY: 'callNameRecordingBusy', SETTINGS_SAVE_FAILED: 'callNameSaveFailed',
  FOREGROUND_REQUIRED: 'copy196', NOTIFICATION_SETUP_FAILED: 'copy337',
  SYSTEM_CONNECTION_FAILED: 'copy206', SETUP_FAILED: 'copy337', LOAD_FAILED: 'copy285',
  DELETE_FAILED: 'copy289', SHARE_FAILED: 'copy260', WHATSAPP_UNAVAILABLE: 'whatsappUnavailable',
  AUTO_SETUP_REQUIRED: 'copy418', AUTO_ACTIVATION_CANCELLED: 'activationCancelled', AUTO_ACTIVATION_FAILED: 'copy206',
  PAIRING_FAILED: 'copy148', PAIRING_SETUP: 'copy148', PAIRING_BUSY: 'pairingBusy', NOTIFICATION_PERMISSION: 'copy202',
  UNSUPPORTED_ANDROID: 'copy203', INVALID_ACTIVATION: 'copy337', OWNER_STOPPING: 'recorderShuttingDown',
  ACTIVATION_SETUP_REQUIRED: 'copy205', ACTIVATION_BUSY: 'copy201', ACTIVATION_FAILED: 'copy206', READINESS_OWNER_FAILED: 'copy206',
  ALREADY_RECORDING: 'alreadyRecording', INVALID_SOURCE: 'invalidSource', RECORDER_SHUTTING_DOWN: 'recorderShuttingDown',
  USB_DISCONNECTED: 'copy204', MIC_PERMISSION: 'copy211', START_FAILED: 'copy222', STOP_FAILED: 'stopFailed',
  START_CANCELLED: 'activationCancelled', RECORDING_FAILED: 'copy226',
};
export const recorderErrorMessage = (code: unknown, message?: unknown): string =>
  typeof code === 'string' && Object.prototype.hasOwnProperty.call(errorKeys, code) ? t(errorKeys[code]) : localizedError(message);
const activationDetailCode = (failure: unknown): string | null => {
  if (!failure || typeof failure !== 'object' || !('code' in failure) ||
    typeof failure.code !== 'string' || !['ACTIVATION_FAILED', 'READINESS_OWNER_FAILED'].includes(failure.code) ||
    !Object.prototype.hasOwnProperty.call(failure, 'userInfo') || !('userInfo' in failure)) return null;
  const details = failure.userInfo;
  if (!details || typeof details !== 'object' || Array.isArray(details) ||
    !Object.prototype.hasOwnProperty.call(details, 'errorCode') || !('errorCode' in details)) return null;
  const code = details.errorCode;
  if (typeof code !== 'string' || !Object.prototype.hasOwnProperty.call(activationErrorKeys, code)) return null;
  const readiness = code === 'ACTIVATION_READINESS_OWNER_FAILED';
  return readiness === (failure.code === 'READINESS_OWNER_FAILED') ? code : null;
};
async function call<T>(action: (module: CallRecorderModule) => Promise<T>): Promise<T> {
  try { return await action(getModule()); }
  catch (failure) {
    const code = failure && typeof failure === 'object' && 'code' in failure ? failure.code : null;
    const detailCode = activationDetailCode(failure);
    const error = new Error(recorderErrorMessage(detailCode ?? code, failure));
    throw Object.assign(error, { code: typeof code === 'string' && Object.prototype.hasOwnProperty.call(errorKeys, code) ? code : 'RECORDER_ACTION_FAILED',
      ...(detailCode ? { errorCode: detailCode } : {}) });
  }
}
const localizeStatus = <T extends { error: string | null; errorCode?: string | null }>(status: T): T =>
  ({ ...status, error: status.error ? recorderErrorMessage(status.errorCode, status.error) : null });
export const NativeRecorder = {
  getCallNameStatus: async (): Promise<CallNameStatus> => {
    const value = await call(module => module.getCallNameStatus());
    if (!value || typeof value.enabled !== 'boolean') throw new Error(t('callNameReadFailed'));
    return { enabled: value.enabled };
  },
  setCallNameEnabled: async (enabled: boolean): Promise<CallNameStatus> => {
    const value = await call(module => module.setCallNameEnabled(enabled));
    if (!value || typeof value.enabled !== 'boolean' || value.enabled !== enabled) throw new Error(t('callNameSaveFailed'));
    return { enabled: value.enabled };
  },
  getAutoRecordingStatus: async (): Promise<AutoRecordingStatus> => localizeStatus(await call(module => module.getAutoRecordingStatus())),
  setAutoRecordingEnabled: async (enabled: boolean): Promise<void> => call(module => module.setAutoRecordingEnabled(enabled)),
  openNotificationAccessSetup: async (): Promise<void> => call(module => module.openNotificationAccessSetup()),
  getStatus: async (): Promise<RecorderStatus> => localizeStatus(await call(module => module.getStatus())),
  startRecording: async (source: CaptureSource = 'microphone'): Promise<RecorderStatus> => localizeStatus(await call(module => module.startRecording(source))),
  stopRecording: async (): Promise<RecorderStatus> => localizeStatus(await call(module => module.stopRecording())),
  listRecordings: async (): Promise<NativeRecording[]> => call(module => module.listRecordings()),
  deleteRecording: async (id: string): Promise<void> => call(module => module.deleteRecording(id)),
  shareRecording: async (id: string): Promise<void> => call(module => module.shareRecording(id)),
  openWhatsApp: async (): Promise<void> => call(module => module.openWhatsApp()),
  getDeviceInfo: async (): Promise<RecorderDeviceInfo> => call(module => module.getDeviceInfo()),
  getSystemAccessStatus: async (): Promise<SystemAccessStatus> => {
    const status = localizeStatus(await call(module => module.getSystemAccessStatus()));
    return { ...status, ...(status.pairingSetup ? { pairingSetup: localizeStatus(status.pairingSetup) } : {}) };
  },
  prepareSystemPairing: async (): Promise<void> => call(module => module.prepareSystemPairing()),
  pairSystemRecorder: async (pairingPort: number, pairingCode: string): Promise<void> => call(module => module.pairSystemRecorder(pairingPort, pairingCode)),
  connectSystemRecorder: async (connectionPort = 0): Promise<void> => call(module => module.connectSystemRecorder(connectionPort)),
  openSystemAccessSetup: async (destination: 'developer' | 'wireless' | 'about' = 'wireless'): Promise<void> => call(module => module.openSystemAccessSetup(destination)),
};
