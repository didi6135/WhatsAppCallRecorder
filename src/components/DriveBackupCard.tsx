import { t, useLocalizedStyles } from '../i18n';
import React from 'react';
import { ActivityIndicator, Alert, StyleSheet, Switch, Text, TouchableOpacity, View } from 'react-native';
import { useDriveBackup } from '../hooks/useDriveBackup';
import { DriveBackupActionError, DriveBackupStatus, NativeDriveBackup, driveActionErrorMessage, driveErrorMessage } from '../services/NativeDriveBackup';
import { colors, ui } from '../theme';

const phaseLabel = (status: DriveBackupStatus): string => {
  if (status.phase === 'needsConsent') return t('copy028');
  if (status.phase === 'error') return t('copy029');
  if (!status.connected) return t('copy030');
  if (!status.folderId) return t('copy031');
  if (!status.enabled) return t('copy032');
  if (status.phase === 'uploading') return t('copy033');
  if (status.phase === 'paused') return t('copy034');
  return status.phase === 'ready' ? t('copy035') : t('copy036');
};

export default function DriveBackupCard({ isRecording, recorderBusy }: { isRecording: boolean; recorderBusy: boolean }) {
  const styles = useLocalizedStyles(baseStyles);
  const { status, checking, action, error, errorAction, refresh, run } = useDriveBackup();
  const busy = action !== null || recorderBusy;
  const destinationReady = status?.connected === true && !!status.folderId;
  const setupDisabled = busy || isRecording || !status;
  const enableDisabled = busy || isRecording || !destinationReady || status?.phase === 'needsConsent';
  const switchDisabled = busy || (!status?.enabled && enableDisabled);
  const percent = status && status.totalBytes > 0 ? Math.max(0, Math.min(100, Math.round(status.uploadedBytes * 100 / status.totalBytes))) : null;
  const setupError = error && (errorAction === 'connect' || errorAction === 'folder')
    ? { action: errorAction, code: error.code } : null;
  const lastActionError: (Pick<DriveBackupActionError, 'action' | 'code'> & Partial<DriveBackupActionError>) | null = setupError || status?.lastActionError || null;
  const actualError = (error && !setupError ? driveErrorMessage(error.code) : null) ||
    (status?.errorCode || status?.errorMessage ? driveErrorMessage(status.errorCode) : null);

  const connectDefault = () => {
    if (setupDisabled) return;
    void run('connect', NativeDriveBackup.connectDefaultFolder);
  };

  const chooseExistingFolder = () => {
    if (setupDisabled || !status) return;
    if (status.connected) void run('folder', NativeDriveBackup.chooseFolder);
    else void run('connect', NativeDriveBackup.connect);
  };

  const reconnect = () => {
    if (setupDisabled) return;
    void run('connect', status?.folderSource === 'managed' ? NativeDriveBackup.connectDefaultFolder : NativeDriveBackup.connect);
  };

  const enable = () => {
    if (enableDisabled || !status) return;
    Alert.alert(t('copy037'), t('copy040', { p0: status.folderName || t('copy038'), p1: status.accountEmail || t('copy039') }), [
      { text: t('copy012'), style: 'cancel' },
      { text: t('copy041'), onPress: () => { void run('enable', () => NativeDriveBackup.setEnabled(true)); } },
    ]);
  };

  const disconnect = () => {
    if (setupDisabled) return;
    Alert.alert(t('copy042'), t('copy043'), [
      { text: t('copy012'), style: 'cancel' },
      { text: t('copy044'), onPress: () => { void run('disconnect', NativeDriveBackup.disconnect); } },
    ]);
  };

  return <View style={styles.card}>
    <View style={styles.heading}><Text style={styles.title}>{t('copy045')}</Text><Switch value={status?.enabled === true} onValueChange={next => { if (next) enable(); else void run('pause', () => NativeDriveBackup.setEnabled(false)); }} disabled={switchDisabled} trackColor={{ false: colors.border, true: colors.green }} thumbColor={colors.surface} accessibilityLabel={t('copy046')} accessibilityState={{ disabled: switchDisabled }} /></View>
    <View style={styles.statusRow}>{(checking && !status || action !== null) && <ActivityIndicator color={colors.green} size="small" />}<Text style={[styles.status, destinationReady && status?.enabled && status.phase === 'ready' && styles.ready]} accessibilityLiveRegion="polite">{action === 'connect' ? t('copy047') : action === 'folder' ? t('copy048') : action === 'enable' ? t('copy049') : action === 'pause' ? t('copy050') : action === 'retry' ? t('copy051') : action === 'disconnect' ? t('copy052') : status ? phaseLabel(status) : checking ? t('copy053') : t('copy054')}</Text></View>
    {actualError && <Text style={styles.error} accessibilityLiveRegion="polite">{actualError}</Text>}
    {lastActionError && <View style={styles.attemptError} accessibilityLiveRegion="polite">
      <Text style={styles.attemptTitle}>{lastActionError.action === 'connect' ? t('copy074') : t('copy075')}</Text>
      <Text style={styles.attemptBody}>{driveActionErrorMessage(lastActionError.code)}</Text>
      {lastActionError.stage && <Text style={styles.attemptNote}>{t('copy076')}{' '}{lastActionError.stage === 'authorize' ? t('copy077') : lastActionError.stage === 'pickerResult' ? t('copy078') : t('copy079')}</Text>}
      <Text style={styles.diagnosticCode} selectable>{lastActionError.code}</Text>
      {lastActionError.authStatusCode !== undefined && <Text style={styles.attemptNote}>{t('copy080')}{' '}{lastActionError.authStatusCode}</Text>}
      {lastActionError.activityResultCode !== undefined && <Text style={styles.attemptNote}>{t('copy081')}{' '}{lastActionError.activityResultCode}</Text>}
      <Text style={styles.attemptNote}>{t('copy082')}</Text>
    </View>}
    <Text style={styles.body}>{destinationReady ? t('copy055') : t('driveDefaultFolderHelp')}</Text>

    {status?.connected && <View style={styles.destination}>
      <Text style={styles.fieldLabel}>{t('copy056')}</Text><Text style={styles.email} selectable>{status.accountEmail || t('copy057')}</Text>
      <Text style={styles.fieldLabel}>{t('copy058')}</Text><Text style={styles.folder}>{status.folderName || (status.folderId ? t('copy038') : t('copy059'))}</Text>
    </View>}

    {status && !destinationReady && <TouchableOpacity style={[styles.primary, setupDisabled && ui.disabled]} onPress={connectDefault} disabled={setupDisabled} accessibilityRole="button" accessibilityLabel={t('driveConnectDefault')} accessibilityHint={t('driveDefaultFolderHelp')} accessibilityState={{ disabled: setupDisabled }}><Text style={ui.primaryText}>{t('driveConnectDefault')}</Text></TouchableOpacity>}
    {destinationReady && status?.phase === 'needsConsent' && <TouchableOpacity style={[styles.primary, setupDisabled && ui.disabled]} onPress={reconnect} disabled={setupDisabled} accessibilityRole="button"><Text style={ui.primaryText}>{t('copy061')}</Text></TouchableOpacity>}
    {destinationReady && !status?.enabled && <TouchableOpacity style={[styles.primary, enableDisabled && ui.disabled]} onPress={enable} disabled={enableDisabled} accessibilityRole="button"><Text style={ui.primaryText}>{t('copy064')}</Text></TouchableOpacity>}
    {destinationReady && !status?.enabled && <Text style={styles.note}>{t('driveUploadOff')}</Text>}
    {status && <View style={styles.folderChoice}>
      <TouchableOpacity style={[styles.secondary, setupDisabled && ui.disabled]} onPress={chooseExistingFolder} disabled={setupDisabled} accessibilityRole="button" accessibilityLabel={t('driveChooseExisting')} accessibilityHint={t('driveExistingFolderHelp')} accessibilityState={{ disabled: setupDisabled }}><Text style={ui.secondaryText}>{t('driveChooseExisting')}</Text></TouchableOpacity>
      <Text style={styles.pickerHelp}>{t('driveExistingFolderHelp')}</Text>
    </View>}
    {status?.connected && <Text style={styles.note}>{t('copy065')}</Text>}

    {destinationReady && status && <View style={styles.counters} accessibilityLabel={t('copy066', { p0: status.uploadedCount, p1: status.queuedCount, p2: status.failedCount })}>
      {[{ count: status.uploadedCount, label: t('copy067') }, { count: status.queuedCount, label: t('copy068') }, { count: status.failedCount, label: t('copy069') }].map(item => <View key={item.label} style={styles.counter}><Text style={styles.count}>{item.count}</Text><Text style={styles.counterLabel}>{item.label}</Text></View>)}
    </View>}
    {status?.phase === 'uploading' && <View style={styles.progress}>
      <Text style={styles.progressLabel}>{percent === null ? t('copy070') : t('copy071', { p0: percent })}</Text>
      {percent !== null && <View style={styles.track} accessibilityRole="progressbar" accessibilityLabel={t('copy072')} accessibilityValue={{ min: 0, max: 100, now: percent }}><View style={[styles.fill, { width: `${percent}%` }]} /></View>}
    </View>}
    {destinationReady && status && (status.queuedCount > 0 || status.failedCount > 0 || status.phase === 'error') && <TouchableOpacity style={[styles.secondary, (busy || !status.enabled || status.phase === 'uploading' || status.phase === 'needsConsent') && ui.disabled]} onPress={() => { void run('retry', NativeDriveBackup.retryPending); }} disabled={busy || !status.enabled || status.phase === 'uploading' || status.phase === 'needsConsent'} accessibilityRole="button"><Text style={ui.secondaryText}>{t('copy073')}</Text></TouchableOpacity>}
    {isRecording && <Text style={styles.note}>{t('copy083')}</Text>}
    <View style={styles.footer}>
      <TouchableOpacity style={[styles.textButton, (busy || checking) && ui.disabled]} onPress={() => { void refresh(); }} disabled={busy || checking} accessibilityRole="button"><Text style={styles.link}>{t('copy084')}</Text></TouchableOpacity>
      {status?.connected && <TouchableOpacity style={[styles.textButton, setupDisabled && ui.disabled]} onPress={reconnect} disabled={setupDisabled} accessibilityRole="button"><Text style={styles.link}>{t('copy085')}</Text></TouchableOpacity>}
    </View>
    {status?.connected && <TouchableOpacity style={[styles.disconnect, setupDisabled && ui.disabled]} onPress={disconnect} disabled={setupDisabled} accessibilityRole="button"><Text style={styles.disconnectText}>{t('copy086')}</Text></TouchableOpacity>}
  </View>;
}

const baseStyles = StyleSheet.create({
  card: { ...ui.card, padding: 20, marginTop: 26 },
  heading: { flexDirection: 'row-reverse', gap: 10, alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 },
  title: { ...ui.label, flex: 1, fontSize: 17 },
  statusRow: { flexDirection: 'row-reverse', alignItems: 'center', gap: 8, marginBottom: 10 },
  status: { ...ui.subtitle, flex: 1, fontSize: 13 },
  ready: { color: colors.green },
  body: { ...ui.body, fontSize: 14, marginBottom: 12 },
  folderChoice: { marginBottom: 6 },
  pickerHelp: { ...ui.subtitle, fontSize: 12, marginTop: 3, marginBottom: 4 },
  destination: { padding: 16, backgroundColor: colors.background, borderRadius: 16, marginBottom: 12 },
  fieldLabel: { ...ui.subtitle, fontSize: 11, marginBottom: 3 },
  email: { color: colors.ink, fontSize: 14, lineHeight: 23, writingDirection: 'ltr', textAlign: 'right', marginBottom: 12 },
  folder: { ...ui.label, fontSize: 14 },
  primary: { ...ui.primaryButton, marginVertical: 7 },
  secondary: { ...ui.secondaryButton, marginVertical: 7 },
  note: { ...ui.subtitle, fontSize: 12, marginTop: 8 },
  counters: { flexDirection: 'row-reverse', borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 18, marginTop: 18, marginBottom: 12 },
  counter: { flex: 1, alignItems: 'center' },
  count: { color: colors.ink, fontSize: 22, fontWeight: '600', fontVariant: ['tabular-nums'] },
  counterLabel: { ...ui.subtitle, fontSize: 11, textAlign: 'center', marginTop: 4 },
  progress: { marginBottom: 16 },
  progressLabel: { ...ui.subtitle, fontSize: 12, marginBottom: 8 },
  track: { height: 6, backgroundColor: colors.border, borderRadius: 4, overflow: 'hidden' },
  fill: { height: 6, backgroundColor: colors.green, borderRadius: 4 },
  error: { ...ui.warning, marginTop: 12 },
  attemptError: { backgroundColor: colors.amberSoft, borderRadius: 16, padding: 14, marginTop: 12 },
  attemptTitle: { ...ui.label, color: colors.amber, fontSize: 13, marginBottom: 5 },
  attemptBody: { ...ui.body, color: colors.amber, fontSize: 13, lineHeight: 22 },
  attemptNote: { ...ui.subtitle, color: colors.amber, fontSize: 11, lineHeight: 19, marginTop: 5 },
  diagnosticCode: { color: colors.amber, fontSize: 11, lineHeight: 19, writingDirection: 'ltr', textAlign: 'right', marginTop: 5 },
  footer: { flexDirection: 'row-reverse', gap: 12, marginTop: 10 },
  textButton: { flex: 1, minHeight: 48, alignItems: 'center', justifyContent: 'center', paddingVertical: 12 },
  link: { color: colors.green, fontSize: 13, fontWeight: '600', writingDirection: 'rtl', textAlign: 'center' },
  disconnect: { minHeight: 48, alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: colors.border, marginTop: 4, paddingVertical: 12 },
  disconnectText: { color: colors.danger, fontSize: 13, writingDirection: 'rtl' },
});
