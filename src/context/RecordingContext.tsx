import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { AppState, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  EMPTY_RECORDER_STATUS,
  EMPTY_SYSTEM_ACCESS_STATUS,
  EMPTY_AUTO_RECORDING_STATUS,
  AutoRecordingStatus,
  CaptureSource,
  NativeRecorder,
  NativeRecording,
  NativeRecordingStatus,
  RecorderDeviceInfo,
  RecorderStatus,
  SystemAccessStatus,
} from '../services/NativeRecorder';
import {
  checkPermissions,
  requestPermissions as requestMicrophonePermissions,
  requestNotificationPermission,
} from '../utils/permissions';

export interface Recording {
  id: string;
  title: string;
  date: string;
  duration: string;
  durationMs?: number;
  filePath: string;
  fileSize: number;
  status: NativeRecordingStatus | 'legacy';
  wasSilenced: boolean;
  source: 'native' | 'legacy';
  captureSource?: CaptureSource;
  channels?: number;
  outputSoundMs?: number;
  microphoneSoundMs?: number;
  startedAutomatically?: boolean;
}

export interface RecordingContextType {
  autoRecordingStatus: AutoRecordingStatus;
  refreshAutoRecordingStatus: () => Promise<void>;
  enableAutoRecording: () => Promise<void>;
  disableAutoRecording: () => Promise<void>;
  openNotificationAccessSetup: () => Promise<void>;
  recordings: Recording[];
  isRecording: boolean;
  recordingTime: string;
  status: RecorderStatus;
  deviceInfo: RecorderDeviceInfo | null;
  systemAccessStatus: SystemAccessStatus;
  refreshSystemAccessStatus: () => Promise<void>;
  prepareSystemPairing: () => Promise<void>;
  pairSystemRecorder: (pairingPort: number, pairingCode: string) => Promise<void>;
  connectSystemRecorder: (connectionPort?: number) => Promise<void>;
  openSystemAccessSetup: (destination?: 'developer' | 'wireless' | 'about') => Promise<void>;
  isBusy: boolean;
  error: string | null;
  startRecording: (captureSource?: CaptureSource) => Promise<void>;
  stopRecording: () => Promise<Recording | null>;
  deleteRecording: (id: string) => Promise<void>;
  shareRecording: (id: string) => Promise<void>;
  openWhatsApp: () => Promise<void>;
  loadRecordings: () => Promise<void>;
  refreshStatus: () => Promise<void>;
  hasPermissions: boolean;
  requestPermissions: () => Promise<boolean>;
  clearAllRecordings: () => Promise<void>;
}

const RecordingContext = createContext<RecordingContextType | undefined>(undefined);

const foregroundRequired = () => new Error('יש להתחיל את ההקלטה כשהאפליקציה פתוחה על המסך.');

// Android may resolve a permission request before its Activity resumes. Wait
// only briefly for that return; ordinary background starts remain prohibited.
const waitForActiveForeground = async (afterPermissionDialog = false): Promise<void> => {
  const initialState = AppState.currentState;
  if (initialState === 'active') return;
  if (initialState === 'background' && !afterPermissionDialog) throw foregroundRequired();

  await new Promise<void>((resolve, reject) => {
    let settled = false;
    let subscription: ReturnType<typeof AppState.addEventListener> | undefined;
    const finish = (failure?: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      subscription?.remove();
      if (failure) reject(failure);
      else resolve();
    };
    const timeout = setTimeout(() => finish(foregroundRequired()), 2000);
    const inspectState = (state: typeof AppState.currentState) => {
      if (state === 'active') finish();
      else if (state === 'background' && !afterPermissionDialog) finish(foregroundRequired());
    };
    subscription = AppState.addEventListener('change', inspectState);
    // Close the gap between the initial read and event registration.
    inspectState(AppState.currentState);
  });
};

export const formatRecordingDuration = (durationMs: number): string => {
  const seconds = Math.floor(Math.max(0, Number.isFinite(durationMs) ? durationMs : 0) / 1000);
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map(value => value.toString().padStart(2, '0'))
    .join(':');
};

const nativeRecording = (recording: NativeRecording): Recording => ({
  ...recording,
  duration: formatRecordingDuration(recording.durationMs),
  source: 'native',
  captureSource: recording.captureSource || 'microphone',
  channels: recording.channels ?? 1,
});

const readLegacyRecordings = async (): Promise<Recording[]> => {
  const saved = await AsyncStorage.getItem('recordings');
  if (!saved) return [];

  const parsed: unknown = JSON.parse(saved);
  if (!Array.isArray(parsed)) throw new Error('רשימת ההקלטות הישנה אינה תקינה. המידע המקורי נשמר ללא שינוי.');

  return parsed.flatMap((entry: unknown, index: number): Recording[] => {
    if (!entry || typeof entry !== 'object') return [];
    const item = entry as Record<string, unknown>;
    if (typeof item.id !== 'string' || typeof item.filePath !== 'string' || !item.filePath) return [];

    return [{
      // A distinct namespace prevents old timestamp IDs from shadowing native IDs.
      id: `legacy:${index}:${item.id}`,
      title: typeof item.title === 'string' ? item.title : 'הקלטה קודמת',
      date: typeof item.date === 'string' ? item.date : '',
      duration: typeof item.duration === 'string' ? item.duration : '00:00:00',
      durationMs: typeof item.durationMs === 'number' ? item.durationMs : undefined,
      filePath: item.filePath,
      fileSize: typeof item.fileSize === 'number' ? item.fileSize : 0,
      status: 'legacy',
      wasSilenced: false,
      source: 'legacy',
    }];
  });
};

const messageFor = (failure: unknown): string => failure instanceof Error
  ? failure.message
  : 'הפעולה לא הושלמה. נסה שוב.';

const dateValue = (recording: Recording): number => {
  const value = Date.parse(recording.date);
  return Number.isFinite(value) ? value : 0;
};

export const RecordingProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [recordings, setRecordings] = useState<Recording[]>([]);
  const [status, setStatus] = useState<RecorderStatus>(EMPTY_RECORDER_STATUS);
  const [deviceInfo, setDeviceInfo] = useState<RecorderDeviceInfo | null>(null);
  const [systemAccessStatus, setSystemAccessStatus] = useState<SystemAccessStatus>(EMPTY_SYSTEM_ACCESS_STATUS);
  const [autoRecordingStatus, setAutoRecordingStatus] = useState<AutoRecordingStatus>(EMPTY_AUTO_RECORDING_STATUS);
  const [hasPermissions, setHasPermissions] = useState(false);
  const [isBusy, setIsBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const mounted = useRef(true);
  const statusRef = useRef<RecorderStatus>(EMPTY_RECORDER_STATUS);
  const recordingsRef = useRef<Recording[]>([]);
  const busyRef = useRef(false);
  const pollingRef = useRef(false);
  const systemPollingRef = useRef(false);
  const actionRevisionRef = useRef(0);
  const recordingsRequestRef = useRef(0);

  const applyStatus = useCallback((next: RecorderStatus) => {
    statusRef.current = next;
    if (mounted.current) setStatus(next);
  }, []);

  const applyRecordings = useCallback(async (native?: NativeRecording[]): Promise<Recording[]> => {
    const requestId = ++recordingsRequestRef.current;
    const [nativeResult, legacyResult] = await Promise.allSettled([
      native ? Promise.resolve(native) : NativeRecorder.listRecordings(),
      readLegacyRecordings(),
    ]);

    const legacy = legacyResult.status === 'fulfilled' ? legacyResult.value : [];
    if (legacyResult.status === 'rejected' && mounted.current) {
      setActionError(`לא הצלחנו לקרוא את ההקלטות הישנות. המידע המקורי נשמר. ${messageFor(legacyResult.reason)}`);
    }

    const currentNative = nativeResult.status === 'fulfilled'
      ? nativeResult.value.map(nativeRecording)
      : recordingsRef.current.filter(recording => recording.source === 'native');
    const merged = [...currentNative, ...legacy]
      .sort((first, second) => dateValue(second) - dateValue(first));
    if (requestId === recordingsRequestRef.current) {
      recordingsRef.current = merged;
      if (mounted.current) setRecordings(merged);
    }
    if (nativeResult.status === 'rejected') throw nativeResult.reason;
    return merged;
  }, []);

  const loadRecordings = useCallback(async (): Promise<void> => {
    try {
      await applyRecordings();
    } catch (failure) {
      if (mounted.current) setActionError(messageFor(failure));
      throw failure;
    }
  }, [applyRecordings]);

  const refreshStatus = useCallback(async (): Promise<void> => {
    if (pollingRef.current || busyRef.current) return;
    pollingRef.current = true;
    const revision = actionRevisionRef.current;
    try {
      const wasRecording = statusRef.current.isRecording;
      const next = await NativeRecorder.getStatus();
      if (busyRef.current || revision !== actionRevisionRef.current) return;
      applyStatus(next);
      if (wasRecording && !next.isRecording) await applyRecordings();
    } catch (failure) {
      if (mounted.current) setActionError(messageFor(failure));
    } finally {
      pollingRef.current = false;
    }
  }, [applyRecordings, applyStatus]);

  const requestPermissions = useCallback(async (): Promise<boolean> => {
    try {
      const granted = await requestMicrophonePermissions();
      if (mounted.current) setHasPermissions(granted);
      if (granted) await waitForActiveForeground(true);
      return granted;
    } catch (failure) {
      if (mounted.current) setActionError(messageFor(failure));
      return false;
    }
  }, []);

  const runAction = useCallback(async <T,>(action: () => Promise<T>): Promise<T> => {
    if (busyRef.current) throw new Error('פעולה אחרת עדיין מתבצעת. המתן לסיומה.');
    busyRef.current = true;
    actionRevisionRef.current += 1;
    if (mounted.current) {
      setIsBusy(true);
      setActionError(null);
    }
    try {
      return await action();
    } catch (failure) {
      if (mounted.current) setActionError(messageFor(failure));
      throw failure;
    } finally {
      busyRef.current = false;
      if (mounted.current) setIsBusy(false);
    }
  }, []);

  const readSystemAccess = useCallback(async (): Promise<SystemAccessStatus> => {
    const next = await NativeRecorder.getSystemAccessStatus();
    if (mounted.current) setSystemAccessStatus(next);
    return next;
  }, []);

  const refreshSystemAccessStatus = useCallback(async (): Promise<void> => {
    if (systemPollingRef.current || busyRef.current) return;
    systemPollingRef.current = true;
    try {
      await readSystemAccess();
    } catch (failure) {
      if (mounted.current) setActionError(messageFor(failure));
    } finally {
      systemPollingRef.current = false;
    }
  }, [readSystemAccess]);

  const prepareSystemPairing = useCallback(() => runAction(async (): Promise<void> => {
    await waitForActiveForeground();
    const granted = await requestNotificationPermission();
    await waitForActiveForeground(true);
    if (!granted) throw new Error('כדי להזין את קוד הצימוד מתוך ההגדרות יש לאפשר התראות. אפשר גם להשתמש בהזנה הידנית.');
    await NativeRecorder.prepareSystemPairing();
  }), [runAction]);

  const pairSystemRecorder = useCallback((pairingPort: number, pairingCode: string) => runAction(async (): Promise<void> => {
    await waitForActiveForeground();
    await NativeRecorder.pairSystemRecorder(pairingPort, pairingCode);
    await readSystemAccess();
  }), [readSystemAccess, runAction]);

  // Called only by an explicit connection/record action, never by polling.
  const ensureSystemRecorder = useCallback(async (connectionPort = 0): Promise<void> => {
    await waitForActiveForeground();
    const access = await readSystemAccess();
    if (!access.available) throw new Error('רכיב הקלטת השיחות דורש Android 14 ומעלה ואת הרשאות המערכת המתאימות. במכשיר זה אפשר להשתמש בהקלטת מיקרופון.');
    // An authenticated live helper needs neither Wi-Fi nor a new ADB session.
    if (!access.helperConnected) {
      if (!access.paired) throw new Error('יש להשלים את אשף ההגדרה ואת אישור קוד הצימוד לפני ההקלטה.');
      if (!access.wirelessDebuggingEnabled) throw new Error('להפעלת רכיב ההקלטה יש להתחבר ל-Wi-Fi ולהפעיל ניפוי באגים אלחוטי.');
      await NativeRecorder.connectSystemRecorder(connectionPort);
    }
    await readSystemAccess();
    const next = await NativeRecorder.getStatus();
    applyStatus(next);
    if (!next.usbConnected) throw new Error('רכיב ההקלטה לא התחבר. התחבר ל-Wi-Fi וחזור לאשף ההגדרה כדי להפעיל אותו.');
  }, [applyStatus, readSystemAccess]);

  const connectSystemRecorder = useCallback((connectionPort = 0) => runAction(() => ensureSystemRecorder(connectionPort)), [ensureSystemRecorder, runAction]);
  const openSystemAccessSetup = useCallback((destination: 'developer' | 'wireless' | 'about' = 'wireless') => runAction(() => NativeRecorder.openSystemAccessSetup(destination)), [runAction]);
  const refreshAutoRecordingStatus = useCallback(async () => {
    const next = await NativeRecorder.getAutoRecordingStatus();
    if (mounted.current) setAutoRecordingStatus(next);
  }, []);
  const enableAutoRecording = useCallback(() => runAction(async () => {
    await waitForActiveForeground();
    const access = await NativeRecorder.getAutoRecordingStatus();
    if (!access.notificationAccessGranted) throw new Error('אפשרו תחילה זיהוי שיחות דרך הרשאת גישה להתראות בהגדרות.');
    if (!(await requestPermissions())) throw new Error('נדרשת הרשאת מיקרופון להפעלת הקלטה אוטומטית.');
    const notifications = await requestNotificationPermission();
    await waitForActiveForeground(true);
    if (!notifications) throw new Error('נדרשת הרשאת התראות כדי להציג שהקלטה אוטומטית פעילה.');
    await ensureSystemRecorder();
    await NativeRecorder.setAutoRecordingEnabled(true);
    await refreshAutoRecordingStatus();
  }), [ensureSystemRecorder, refreshAutoRecordingStatus, requestPermissions, runAction]);
  const disableAutoRecording = useCallback(() => runAction(async () => {
    await NativeRecorder.setAutoRecordingEnabled(false);
    await refreshAutoRecordingStatus();
    await refreshStatus();
  }), [refreshAutoRecordingStatus, refreshStatus, runAction]);
  const openNotificationAccessSetup = useCallback(() => runAction(async () => {
    await waitForActiveForeground();
    await NativeRecorder.openNotificationAccessSetup();
  }), [runAction]);

  const startRecording = useCallback((captureSource: CaptureSource = 'microphone') => runAction(async () => {
    if (Platform.OS !== 'android') {
      throw new Error('הקלטה זו נתמכת ב-Android בלבד.');
    }
    await waitForActiveForeground();
    const current = await NativeRecorder.getStatus();
    applyStatus(current);
    if (current.isRecording) return;
    if (captureSource === 'usb' && !current.usbConnected) {
      await ensureSystemRecorder();
    }

    const alreadyGranted = await checkPermissions();
    const granted = alreadyGranted || await requestMicrophonePermissions();
    if (mounted.current) setHasPermissions(granted);
    if (!granted) throw new Error('כדי להקליט יש לאפשר גישה למיקרופון בהגדרות האפליקציה.');

    await waitForActiveForeground(!alreadyGranted);
    if (AppState.currentState !== 'active') throw foregroundRequired();
    applyStatus(await NativeRecorder.startRecording(captureSource));
  }), [applyStatus, ensureSystemRecorder, runAction]);

  const stopRecording = useCallback(() => runAction(async (): Promise<Recording | null> => {
    const current = await NativeRecorder.getStatus();
    applyStatus(current);
    if (!current.isRecording) {
      await applyRecordings();
      return null;
    }

    const before = await NativeRecorder.listRecordings();
    const previousIds = new Set(before.map(recording => recording.id));
    // The promise resolves only after WAV and metadata have been saved natively.
    applyStatus(await NativeRecorder.stopRecording());
    const after = await NativeRecorder.listRecordings();
    const merged = await applyRecordings(after);
    return merged.find(recording => recording.source === 'native' && !previousIds.has(recording.id)) || null;
  }), [applyRecordings, applyStatus, runAction]);

  const findWritableRecording = useCallback((id: string): Recording => {
    const recording = recordingsRef.current.find(item => item.id === id);
    if (!recording) throw new Error('ההקלטה לא נמצאה. רענן את הרשימה.');
    if (recording.source === 'legacy') {
      throw new Error('הקלטה קודמת נשמרת לקריאה בלבד כדי לשמור על הקובץ המקורי. מחיקה ושיתוף זמינים להקלטות החדשות.');
    }
    return recording;
  }, []);

  const deleteRecording = useCallback((id: string) => runAction(async () => {
    const recording = findWritableRecording(id);
    await NativeRecorder.deleteRecording(recording.id);
    await applyRecordings();
  }), [applyRecordings, findWritableRecording, runAction]);

  const shareRecording = useCallback((id: string) => runAction(async () => {
    const recording = findWritableRecording(id);
    await NativeRecorder.shareRecording(recording.id);
  }), [findWritableRecording, runAction]);

  const openWhatsApp = useCallback(() => runAction(() => NativeRecorder.openWhatsApp()), [runAction]);

  const clearAllRecordings = useCallback(() => runAction(async () => {
    // Existing AsyncStorage metadata and legacy files are never modified.
    const native = await NativeRecorder.listRecordings();
    for (const recording of native) await NativeRecorder.deleteRecording(recording.id);
    await applyRecordings();
  }), [applyRecordings, runAction]);

  useEffect(() => {
    mounted.current = true;
    let interval: ReturnType<typeof setInterval> | undefined;
    let systemInterval: ReturnType<typeof setInterval> | undefined;

    const syncActiveState = async () => {
      const results = await Promise.allSettled([
        refreshStatus(),
        loadRecordings(),
        checkPermissions(),
        NativeRecorder.getDeviceInfo(),
        refreshSystemAccessStatus(),
        refreshAutoRecordingStatus(),
      ]);
      if (!mounted.current) return;
      if (results[2].status === 'fulfilled') setHasPermissions(results[2].value);
      if (results[3].status === 'fulfilled') setDeviceInfo(results[3].value);
      for (const result of results) {
        if (result.status === 'rejected') setActionError(messageFor(result.reason));
      }
    };

    const updatePolling = (active: boolean) => {
      if (interval) clearInterval(interval);
      if (systemInterval) clearInterval(systemInterval);
      interval = undefined;
      systemInterval = undefined;
      if (active) {
        void syncActiveState();
        interval = setInterval(() => { void refreshStatus(); }, 500);
        systemInterval = setInterval(() => { void Promise.allSettled([refreshSystemAccessStatus(), refreshAutoRecordingStatus()]); }, 3000);
        // Status polling is read-only; it never arms or starts a recording.
      }
    };

    updatePolling(AppState.currentState === 'active');
    const subscription = AppState.addEventListener('change', state => updatePolling(state === 'active'));
    return () => {
      mounted.current = false;
      if (interval) clearInterval(interval);
      if (systemInterval) clearInterval(systemInterval);
      subscription.remove();
      // The visible native notification owns background stop and finalization.
      // Provider unmount must never stop or discard an active recording.
    };
  }, [loadRecordings, refreshStatus, refreshSystemAccessStatus, refreshAutoRecordingStatus]);

  return (
    <RecordingContext.Provider value={{
      recordings,
      isRecording: status.isRecording,
      recordingTime: formatRecordingDuration(status.elapsedMs),
      status,
      deviceInfo,
      systemAccessStatus,
      autoRecordingStatus,
      refreshAutoRecordingStatus,
      enableAutoRecording,
      disableAutoRecording,
      openNotificationAccessSetup,
      refreshSystemAccessStatus,
      prepareSystemPairing,
      pairSystemRecorder,
      connectSystemRecorder,
      openSystemAccessSetup,
      isBusy,
      error: actionError || status.error,
      startRecording,
      stopRecording,
      deleteRecording,
      shareRecording,
      openWhatsApp,
      loadRecordings,
      refreshStatus,
      hasPermissions,
      requestPermissions,
      clearAllRecordings,
    }}>
      {children}
    </RecordingContext.Provider>
  );
};

export const useRecording = (): RecordingContextType => {
  const context = useContext(RecordingContext);
  if (!context) throw new Error('useRecording must be used within a RecordingProvider');
  return context;
};
