import { NativeModules, Platform } from 'react-native';
import { telegramText, TelegramTextKey } from '../i18n/telegram';

export type TelegramStatusCode = 'DISCONNECTED' | 'AWAITING_CHAT' | 'DISABLED' | 'READY' | 'UPLOADING' | 'ACTION_REQUIRED';
export interface TelegramBackupStatus {
  connected: boolean;
  enabled: boolean;
  connectionPending: boolean;
  botUsername: string | null;
  startLink: string | null;
  connectionExpiresAt: number | null;
  queued: number;
  uploaded: number;
  failed: number;
  unknownOutcome: number;
  uploading: boolean;
  lastErrorCode: string | null;
  lastErrorAt: number | null;
  statusCode: TelegramStatusCode;
}
interface TelegramBackupModule {
  getStatus(): Promise<unknown>;
  connect(token: string): Promise<unknown>;
  verifyConnection(): Promise<unknown>;
  setEnabled(enabled: boolean): Promise<unknown>;
  retryPending(confirmPossibleDuplicates: boolean): Promise<unknown>;
  disconnect(): Promise<unknown>;
}

export const telegramErrorCodes = [
  'TOKEN_INVALID', 'BOT_IN_USE', 'PRIVATE_CHAT_REQUIRED', 'CONNECTION_EXPIRED', 'CONNECTION_NOT_CONFIRMED', 'CHAT_AMBIGUOUS', 'NOT_CONNECTED', 'FOREGROUND_REQUIRED', 'RECORDING_BUSY', 'CONNECTION_BUSY', 'NETWORK', 'RATE_LIMIT', 'RETRY_LIMIT', 'CHAT_UNAVAILABLE', 'UPLOAD_REJECTED', 'UNKNOWN_OUTCOME', 'UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION', 'LOCAL_FILE_MISSING', 'LOCAL_FILE_CHANGED', 'UNSUPPORTED_WAV', 'LOCAL_QUEUE_UNAVAILABLE', 'LOCAL_QUEUE_FULL', 'RESPONSE_INVALID', 'CANCELED',
  'TELEGRAM_MODULE_UNAVAILABLE', 'TELEGRAM_ANDROID_ONLY', 'TELEGRAM_STATUS_INVALID',
] as const;
const codes = new Set<string>(telegramErrorCodes);
const knownCode = (value: unknown): value is typeof telegramErrorCodes[number] => typeof value === 'string' && codes.has(value);
export const telegramErrorMessage = (code: string | null): string => telegramText(knownCode(code) ? code as TelegramTextKey : 'generic');
export class TelegramBackupError extends Error {
  constructor(readonly code: string) { super(telegramErrorMessage(code)); this.name = 'TelegramBackupError'; }
}
const invalid = (): never => { throw new TelegramBackupError('TELEGRAM_STATUS_INVALID'); };
const integer = (value: unknown): number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : invalid();
const timestamp = (value: unknown): number | null => value === null ? null : integer(value);
const username = (value: unknown): string | null => value === null ? null : typeof value === 'string' && /^[A-Za-z0-9_]{5,32}$/.test(value) ? value : invalid();

// Accept only the exact app-generated private-chat link. Never open provider-controlled redirects.
const startLink = (value: unknown, bot: string | null): string | null => {
  if (value === null) return null;
  if (typeof value !== 'string' || !bot) return invalid();
  const match = /^https:\/\/t\.me\/([A-Za-z0-9_]{5,32})\?start=([A-Za-z0-9_-]{1,64})$/.exec(value);
  return match && match[1].toLowerCase() === bot.toLowerCase() ? value : invalid();
};

export function normalizeTelegramBackupStatus(value: unknown): TelegramBackupStatus {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid();
  const raw = value as Record<string, unknown>;
  const states: TelegramStatusCode[] = ['DISCONNECTED', 'AWAITING_CHAT', 'DISABLED', 'READY', 'UPLOADING', 'ACTION_REQUIRED'];
  if (typeof raw.connected !== 'boolean' || typeof raw.enabled !== 'boolean' || typeof raw.connectionPending !== 'boolean' || typeof raw.uploading !== 'boolean' || !states.includes(raw.statusCode as TelegramStatusCode)) return invalid();
  const bot = username(raw.botUsername);
  const link = startLink(raw.startLink, bot);
  const expiry = timestamp(raw.connectionExpiresAt);
  if (raw.enabled && !raw.connected || raw.connected && !bot || raw.connectionPending && (!link || expiry === null) || !raw.connectionPending && (link !== null || expiry !== null)) return invalid();
  if (raw.lastErrorCode !== null && typeof raw.lastErrorCode !== 'string') return invalid();
  // Copy only public primitives. Tokens, chat IDs, raw provider errors and unknown extras are discarded.
  return {
    connected: raw.connected, enabled: raw.enabled, connectionPending: raw.connectionPending, uploading: raw.uploading,
    botUsername: bot, startLink: link, connectionExpiresAt: expiry,
    queued: integer(raw.queued), uploaded: integer(raw.uploaded), failed: integer(raw.failed), unknownOutcome: integer(raw.unknownOutcome),
    lastErrorCode: raw.lastErrorCode === null ? null : knownCode(raw.lastErrorCode) ? raw.lastErrorCode : 'TELEGRAM_ACTION_FAILED',
    lastErrorAt: timestamp(raw.lastErrorAt), statusCode: raw.statusCode as TelegramStatusCode,
  };
}

const moduleFor = (): TelegramBackupModule => {
  if (Platform.OS !== 'android') throw new TelegramBackupError('TELEGRAM_ANDROID_ONLY');
  const module = NativeModules.TelegramBackup as TelegramBackupModule | undefined;
  if (!module) throw new TelegramBackupError('TELEGRAM_MODULE_UNAVAILABLE');
  return module;
};
async function call(operation: (module: TelegramBackupModule) => Promise<unknown>): Promise<TelegramBackupStatus> {
  try { return normalizeTelegramBackupStatus(await operation(moduleFor())); }
  catch (error) {
    if (error instanceof TelegramBackupError) throw error;
    const raw = error && typeof error === 'object' && 'code' in error ? error.code : null;
    throw new TelegramBackupError(knownCode(raw) ? raw : 'TELEGRAM_ACTION_FAILED');
  }
}
export const NativeTelegramBackup = {
  getStatus: (): Promise<TelegramBackupStatus> => call(module => module.getStatus()),
  connect: (token: string): Promise<TelegramBackupStatus> => call(module => {
    if (typeof token !== 'string' || !token.length || token.length > 256) throw new TelegramBackupError('TOKEN_INVALID');
    return module.connect(token);
  }),
  verifyConnection: (): Promise<TelegramBackupStatus> => call(module => module.verifyConnection()),
  setEnabled: (enabled: boolean): Promise<TelegramBackupStatus> => call(module => {
    if (typeof enabled !== 'boolean') throw new TelegramBackupError('TELEGRAM_ACTION_FAILED');
    return module.setEnabled(enabled);
  }),
  retryPending: (confirmPossibleDuplicates: boolean): Promise<TelegramBackupStatus> => call(module => {
    if (typeof confirmPossibleDuplicates !== 'boolean') throw new TelegramBackupError('UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION');
    return module.retryPending(confirmPossibleDuplicates);
  }),
  disconnect: (): Promise<TelegramBackupStatus> => call(module => module.disconnect()),
};
