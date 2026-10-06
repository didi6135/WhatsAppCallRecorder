export interface SetupReadiness {
  microphone: boolean;
  notifications: boolean;
  callAccess: boolean;
  helper: boolean;
  automatic: boolean;
  allReady: boolean;
}

export interface PhoneEvidence {
  available: boolean;
  helperConnected: boolean;
  developerOptionsEnabled?: boolean;
  wirelessDebuggingEnabled: boolean;
  paired: boolean;
}

export interface SetupEvidence {
  nativeAvailable: boolean | null;
  microphoneGranted: boolean | null;
  notificationsGranted: boolean | null;
  system: PhoneEvidence | null;
  automatic: {
    enabled: boolean;
    armed: boolean;
    notificationAccessGranted: boolean;
    listenerConnected: boolean;
    notificationsEnabled: boolean;
    helperConnected: boolean;
    state: string;
  } | null;
}

export const EMPTY_SETUP_READINESS: SetupReadiness = {
  microphone: false, notifications: false, callAccess: false,
  helper: false, automatic: false, allReady: false,
};

export function deriveSetupReadiness(evidence: SetupEvidence): SetupReadiness {
  const microphone = evidence.microphoneGranted === true;
  const notifications = evidence.notificationsGranted === true && evidence.automatic?.notificationsEnabled === true;
  const callAccess = evidence.automatic?.notificationAccessGranted === true && evidence.automatic.listenerConnected === true;
  const helper = evidence.system?.available === true && evidence.system.helperConnected === true;
  const automatic = evidence.automatic?.enabled === true && evidence.automatic.armed === true &&
    evidence.automatic.helperConnected === true && ['ready', 'recording'].includes(evidence.automatic.state);
  return { microphone, notifications, callAccess, helper, automatic,
    allReady: evidence.nativeAvailable === true && microphone && notifications && callAccess && helper && automatic };
}

export type SetupStep = 'loading' | 'checking' | 'microphone' | 'notifications' | 'activation' | 'call_access' | 'automatic' | 'done';
export function nextSetupStep(readiness: SetupReadiness, loading = false): SetupStep {
  if (loading) return 'loading';
  if (!readiness.microphone) return 'microphone';
  if (!readiness.notifications) return 'notifications';
  if (!readiness.helper) return 'activation';
  if (!readiness.callAccess) return 'call_access';
  if (!readiness.automatic) return 'automatic';
  return readiness.allReady ? 'done' : 'checking';
}

export type PhoneSetupStep = 'checking' | 'ready' | 'unsupported' | 'developer' | 'wireless' | 'pair' | 'connect';
export function phoneSetupStep(access: PhoneEvidence | null): PhoneSetupStep {
  if (access === null) return 'checking';
  if (!access.available) return 'unsupported';
  // A live authenticated helper needs no new Wi-Fi/debugging/ADB session.
  if (access.helperConnected) return 'ready';
  if (!(access.developerOptionsEnabled ?? access.wirelessDebuggingEnabled)) return 'developer';
  if (!access.wirelessDebuggingEnabled) return 'wireless';
  return access.paired ? 'connect' : 'pair';
}
