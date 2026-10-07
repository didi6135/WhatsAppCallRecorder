import { t } from '../i18n/core';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { DriveBackupError, DriveBackupStatus, NativeDriveBackup } from '../services/NativeDriveBackup';

export type DriveBackupAction = 'connect' | 'folder' | 'enable' | 'pause' | 'retry' | 'disconnect';

export function useDriveBackup() {
  const [status, setStatus] = useState<DriveBackupStatus | null>(null);
  const [checking, setChecking] = useState(true);
  const [action, setAction] = useState<DriveBackupAction | null>(null);
  const [error, setError] = useState<DriveBackupError | null>(null);
  const [errorAction, setErrorAction] = useState<DriveBackupAction | null>(null);
  const mounted = useRef(true);
  const focused = useRef(false);
  const polling = useRef(false);
  const acting = useRef(false);
  const revision = useRef(0);
  const errorSource = useRef<'poll' | 'action' | null>(null);
  const failedAction = useRef<DriveBackupAction | null>(null);

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  const refresh = useCallback(async () => {
    if (!focused.current || AppState.currentState !== 'active' || polling.current || acting.current) return;
    polling.current = true;
    const requestedAt = revision.current;
    try {
      const next = await NativeDriveBackup.getStatus();
      if (mounted.current && focused.current && requestedAt === revision.current) {
        setStatus(next);
        if (errorSource.current === 'poll' ||
          ((failedAction.current === 'connect' || failedAction.current === 'folder') && next.lastActionError !== undefined)) {
          // New native builds retain/clear setup diagnostics across remounts. Use that authoritative result.
          errorSource.current = null; failedAction.current = null; setError(null); setErrorAction(null);
        }
      }
    } catch (failure) {
      if (mounted.current && focused.current && requestedAt === revision.current && errorSource.current !== 'action') {
        errorSource.current = 'poll';
        failedAction.current = null;
        setErrorAction(null);
        setError(failure instanceof DriveBackupError ? failure : new DriveBackupError('DRIVE_ACTION_FAILED', t('copy214')));
      }
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

  const run = useCallback(async (name: DriveBackupAction, operation: () => Promise<DriveBackupStatus>) => {
    if (acting.current || !focused.current || AppState.currentState !== 'active') return;
    acting.current = true;
    revision.current += 1;
    errorSource.current = null;
    failedAction.current = null;
    if (mounted.current) { setAction(name); setError(null); setErrorAction(null); }
    try {
      const next = await operation();
      if (mounted.current) setStatus(next);
    } catch (failure) {
      errorSource.current = 'action';
      failedAction.current = name;
      if (mounted.current) {
        setErrorAction(name);
        setError(failure instanceof DriveBackupError ? failure : new DriveBackupError('DRIVE_ACTION_FAILED', t('copy009')));
      }
    } finally {
      acting.current = false;
      if (mounted.current) { setAction(null); setChecking(false); }
    }
  }, []);

  return { status, checking, action, error, errorAction, refresh, run };
}
