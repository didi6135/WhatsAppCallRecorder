import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { NativeTelegramBackup, TelegramBackupError, TelegramBackupStatus } from '../services/NativeTelegramBackup';

export type TelegramBackupAction = 'connect' | 'verify' | 'enable' | 'pause' | 'retry' | 'disconnect';
export function useTelegramBackup() {
  const [status, setStatus] = useState<TelegramBackupStatus | null>(null);
  const [checking, setChecking] = useState(true);
  const [action, setAction] = useState<TelegramBackupAction | null>(null);
  const [error, setError] = useState<TelegramBackupError | null>(null);
  const mounted = useRef(true);
  const focused = useRef(false);
  const polling = useRef(false);
  const acting = useRef(false);
  const revision = useRef(0);
  const actionError = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  const refresh = useCallback(async () => {
    if (!focused.current || AppState.currentState !== 'active' || polling.current || acting.current) return;
    polling.current = true;
    const requestedAt = revision.current;
    try {
      const next = await NativeTelegramBackup.getStatus();
      if (mounted.current && focused.current && requestedAt === revision.current) {
        setStatus(next);
        if (!actionError.current) setError(null);
      }
    } catch (failure) {
      if (mounted.current && focused.current && requestedAt === revision.current && !actionError.current)
        setError(failure instanceof TelegramBackupError ? failure : new TelegramBackupError('TELEGRAM_ACTION_FAILED'));
    } finally {
      polling.current = false;
      if (mounted.current && focused.current && requestedAt === revision.current) setChecking(false);
    }
  }, []);

  useFocusEffect(useCallback(() => {
    focused.current = true;
    let interval: ReturnType<typeof setInterval> | undefined;
    const update = (active: boolean) => {
      if (interval) clearInterval(interval);
      interval = undefined;
      if (active) { void refresh(); interval = setInterval(() => { void refresh(); }, 4000); }
    };
    update(AppState.currentState === 'active');
    const subscription = AppState.addEventListener('change', state => update(state === 'active'));
    return () => { focused.current = false; revision.current += 1; if (interval) clearInterval(interval); subscription.remove(); };
  }, [refresh]));

  const run = useCallback(async (name: TelegramBackupAction, operation: () => Promise<TelegramBackupStatus>) => {
    if (acting.current || !focused.current || AppState.currentState !== 'active') return;
    acting.current = true;
    revision.current += 1;
    actionError.current = false;
    if (mounted.current) { setAction(name); setError(null); }
    try {
      const next = await operation();
      if (mounted.current) setStatus(next);
    } catch (failure) {
      actionError.current = true;
      if (mounted.current) setError(failure instanceof TelegramBackupError ? failure : new TelegramBackupError('TELEGRAM_ACTION_FAILED'));
    } finally {
      acting.current = false;
      if (mounted.current) { setAction(null); setChecking(false); }
    }
  }, []);

  return {status, checking, action, error, refresh, run};
}
