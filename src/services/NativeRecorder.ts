import { NativeModules, Platform } from 'react-native';

export type NativeRecordingStatus = 'captured' | 'silent' | 'interrupted' | 'recovered';
export type CaptureSource = 'microphone' | 'usb';
export interface AutoRecordingStatus {
  enabled: boolean;
  armed: boolean;
  notificationAccessGranted: boolean;
  notificationsEnabled: boolean;
  listenerConnected: boolean;
  helperConnected: boolean;
  state: 'off' | 'needs_access' | 'needs_activation' | 'ready' | 'recording' | 'paused' | 'error';
  error: string | null;
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
  pairingSetup?: {
    active: boolean;
    discovering: boolean;
    pairing: boolean;
    error: string | null;
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
    throw new Error('ההקלטה זמינה בבניית Android בלבד. אין תמיכה בהקלטת שיחות ב-iPhone.');
  }

  const recorder = NativeModules.CallRecorder as CallRecorderModule | undefined;
  if (!recorder) {
    throw new Error('רכיב ההקלטה חסר בבנייה זו. יש להתקין את אפליקציית Android המלאה; Expo Go אינו תומך ברכיב.');
  }

  return recorder;
};

// Lifecycle, WAV finalization, and metadata belong to the Android service.
// JavaScript only asks for actions and reads the authoritative native state.
export const NativeRecorder = {
  getAutoRecordingStatus: async (): Promise<AutoRecordingStatus> => getModule().getAutoRecordingStatus(),
  setAutoRecordingEnabled: async (enabled: boolean): Promise<void> => getModule().setAutoRecordingEnabled(enabled),
  openNotificationAccessSetup: async (): Promise<void> => getModule().openNotificationAccessSetup(),
  getStatus: async (): Promise<RecorderStatus> => getModule().getStatus(),
  startRecording: async (source: CaptureSource = 'microphone'): Promise<RecorderStatus> => getModule().startRecording(source),
  stopRecording: async (): Promise<RecorderStatus> => getModule().stopRecording(),
  listRecordings: async (): Promise<NativeRecording[]> => getModule().listRecordings(),
  deleteRecording: async (id: string): Promise<void> => getModule().deleteRecording(id),
  shareRecording: async (id: string): Promise<void> => getModule().shareRecording(id),
  openWhatsApp: async (): Promise<void> => getModule().openWhatsApp(),
  getDeviceInfo: async (): Promise<RecorderDeviceInfo> => getModule().getDeviceInfo(),
  getSystemAccessStatus: async (): Promise<SystemAccessStatus> => getModule().getSystemAccessStatus(),
  prepareSystemPairing: async (): Promise<void> => getModule().prepareSystemPairing(),
  pairSystemRecorder: async (pairingPort: number, pairingCode: string): Promise<void> => getModule().pairSystemRecorder(pairingPort, pairingCode),
  connectSystemRecorder: async (connectionPort = 0): Promise<void> => getModule().connectSystemRecorder(connectionPort),
  openSystemAccessSetup: async (destination: 'developer' | 'wireless' | 'about' = 'wireless'): Promise<void> => getModule().openSystemAccessSetup(destination),
};
