import { t } from '../i18n/core';
import { PermissionsAndroid, Platform } from 'react-native';

// Files stay in private app storage. Recording never needs a storage permission.
export const RECORDING_PERMISSIONS = Platform.OS === 'android'
  ? [PermissionsAndroid.PERMISSIONS.RECORD_AUDIO]
  : [];

export const checkPermissions = async (): Promise<boolean> => {
  if (Platform.OS !== 'android') return false;
  return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
};

export const requestNotificationPermission = async (): Promise<boolean> => {
  if (Platform.OS !== 'android') return false;
  if (Number(Platform.Version) < 33) return true;
  const permission = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS;
  return await PermissionsAndroid.check(permission)
    || await PermissionsAndroid.request(permission) === PermissionsAndroid.RESULTS.GRANTED;
};

export const requestPermissions = async (): Promise<boolean> => {
  if (Platform.OS !== 'android') return false;

  const microphone = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    {
      title: t('copy421'),
      message: t('copy422'),
      buttonPositive: t('continueAction'),
      buttonNegative: t('copy012'),
    },
  );

  if (microphone !== PermissionsAndroid.RESULTS.GRANTED) return false;

  // Notification consent is useful for the stop button, but Android allows a
  // foreground recorder even if the user declines notification visibility.
  if (Number(Platform.Version) >= 33) {
    try {
      await requestNotificationPermission();
    } catch {
      // An optional notification failure must not revoke microphone consent.
    }
  }

  return true;
};
