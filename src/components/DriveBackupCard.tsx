import React from 'react';
import { ActivityIndicator, Alert, StyleSheet, Switch, Text, TouchableOpacity, View } from 'react-native';
import { useDriveBackup } from '../hooks/useDriveBackup';
import { DriveBackupActionError, DriveBackupStatus, NativeDriveBackup, driveActionErrorMessage, driveErrorMessage } from '../services/NativeDriveBackup';
import { colors, ui } from '../theme';

const phaseLabel = (status: DriveBackupStatus): string => {
  if (status.phase === 'needsConsent') return 'נדרש אישור מחדש בחשבון Google';
  if (status.phase === 'error') return 'הגיבוי דורש בדיקה';
  if (!status.connected) return 'חשבון Google עדיין לא מחובר';
  if (!status.folderId) return 'נשאר לבחור תיקייה';
  if (!status.enabled) return 'הגיבוי לא הופעל';
  if (status.phase === 'uploading') return 'מעלה הקלטה ל־Google Drive';
  if (status.phase === 'paused') return 'הגיבוי מושהה';
  return status.phase === 'ready' ? 'גיבוי ההקלטות פעיל' : 'נדרש חיבור מחדש ל־Google Drive';
};

export default function DriveBackupCard({ isRecording, recorderBusy }: { isRecording: boolean; recorderBusy: boolean }) {
  const { status, checking, action, error, errorAction, refresh, run } = useDriveBackup();
  const busy = action !== null || recorderBusy;
  const destinationReady = status?.connected === true && !!status.folderId;
  const setupDisabled = busy || isRecording || !status;
  const enableDisabled = busy || isRecording || !destinationReady;
  const switchDisabled = busy || (!status?.enabled && enableDisabled);
  const percent = status && status.totalBytes > 0 ? Math.max(0, Math.min(100, Math.round(status.uploadedBytes * 100 / status.totalBytes))) : null;
  const setupError = error && (errorAction === 'connect' || errorAction === 'folder')
    ? { action: errorAction, code: error.code } : null;
  const lastActionError: (Pick<DriveBackupActionError, 'action' | 'code'> & Partial<DriveBackupActionError>) | null = setupError || status?.lastActionError || null;
  const actualError = (error && !setupError ? driveErrorMessage(error.code) : null) ||
    (status?.errorCode || status?.errorMessage ? driveErrorMessage(status.errorCode) : null);

  const enable = () => {
    if (enableDisabled || !status) return;
    Alert.alert('גיבוי ההקלטות ל־Google Drive', `כל ההקלטות שהושלמו ושמורות באפליקציה, וגם ההקלטות העתידיות לאחר שיישמרו, יועלו לתיקייה ״${status.folderName || 'התיקייה שבחרתם'}״ בחשבון ${status.accountEmail || 'Google שבחרתם'}.\n\nהקבצים שבטלפון יישארו במקומם.`, [
      { text: 'ביטול', style: 'cancel' },
      { text: 'אישור והפעלת גיבוי', onPress: () => { void run('enable', () => NativeDriveBackup.setEnabled(true)); } },
    ]);
  };

  const disconnect = () => {
    if (setupDisabled) return;
    Alert.alert('ניתוק חשבון Google', 'הגיבוי יפסיק לפעול. ההקלטות שבטלפון והקבצים שכבר נמצאים ב־Google Drive לא יימחקו.', [
      { text: 'ביטול', style: 'cancel' },
      { text: 'ניתוק', onPress: () => { void run('disconnect', NativeDriveBackup.disconnect); } },
    ]);
  };

  return <View style={styles.card}>
    <View style={styles.heading}><Text style={styles.title}>גיבוי ל־Google Drive</Text><Switch value={status?.enabled === true} onValueChange={next => { if (next) enable(); else void run('pause', () => NativeDriveBackup.setEnabled(false)); }} disabled={switchDisabled} trackColor={{ false: colors.border, true: colors.green }} thumbColor={colors.surface} accessibilityLabel="גיבוי כל ההקלטות שהושלמו וההקלטות העתידיות ל־Google Drive" accessibilityState={{ disabled: switchDisabled }} /></View>
    <View style={styles.statusRow}>{(checking && !status || action !== null) && <ActivityIndicator color={colors.green} size="small" />}<Text style={[styles.status, destinationReady && status?.enabled && status.phase === 'ready' && styles.ready]} accessibilityLiveRegion="polite">{action === 'connect' ? 'פותחים את החיבור ל־Google…' : action === 'folder' ? 'בוחרים תיקייה ב־Google Drive…' : action === 'enable' ? 'מפעילים את הגיבוי…' : action === 'pause' ? 'משהים את הגיבוי…' : action === 'retry' ? 'בודקים את ההעלאות שלא הושלמו…' : action === 'disconnect' ? 'מנתקים את החשבון…' : status ? phaseLabel(status) : checking ? 'בודקים את מצב הגיבוי…' : 'לא ניתן לבדוק את הגיבוי כרגע'}</Text></View>
    <Text style={styles.body}>גיבוי אופציונלי לחשבון ולתיקייה שתבחרו. הקלטות מועלות רק לאחר שנשמרו, ורק אחרי אישור שלכם.</Text>

    {status?.connected && <View style={styles.destination}>
      <Text style={styles.fieldLabel}>חשבון Google</Text><Text style={styles.email} selectable>{status.accountEmail || 'חשבון Google מחובר'}</Text>
      <Text style={styles.fieldLabel}>תיקיית הגיבוי</Text><Text style={styles.folder}>{status.folderName || (status.folderId ? 'התיקייה שבחרתם' : 'טרם נבחרה תיקייה')}</Text>
    </View>}

    {status && !status.connected && <TouchableOpacity style={[styles.primary, setupDisabled && ui.disabled]} onPress={() => { void run('connect', NativeDriveBackup.connect); }} disabled={setupDisabled} accessibilityRole="button"><Text style={ui.primaryText}>חיבור חשבון Google</Text></TouchableOpacity>}
    {status?.connected && status.phase === 'needsConsent' && <TouchableOpacity style={[styles.primary, setupDisabled && ui.disabled]} onPress={() => { void run('connect', NativeDriveBackup.connect); }} disabled={setupDisabled} accessibilityRole="button"><Text style={ui.primaryText}>אישור מחדש ב־Google</Text></TouchableOpacity>}
    {status?.connected && <TouchableOpacity style={[styles.secondary, setupDisabled && ui.disabled]} onPress={() => { void run('folder', NativeDriveBackup.chooseFolder); }} disabled={setupDisabled} accessibilityRole="button"><Text style={ui.secondaryText}>{status.folderId ? 'בחירת תיקייה אחרת' : 'בחירת תיקייה ב־Google Drive'}</Text></TouchableOpacity>}
    {destinationReady && !status?.enabled && <TouchableOpacity style={[styles.primary, enableDisabled && ui.disabled]} onPress={enable} disabled={enableDisabled} accessibilityRole="button"><Text style={ui.primaryText}>הפעלת גיבוי ההקלטות</Text></TouchableOpacity>}
    {status?.connected && <Text style={styles.note}>בחירת חשבון או תיקייה חדשים משהה את הגיבוי עד לאישור מחדש. ביטול הבחירה שומר את ההגדרה הקיימת.</Text>}

    {destinationReady && status && <View style={styles.counters} accessibilityLabel={`${status.uploadedCount} הקלטות גובו, ${status.queuedCount} בהמתנה, ${status.failedCount} דורשות בדיקה`}>
      {[{ count: status.uploadedCount, label: 'גובו' }, { count: status.queuedCount, label: 'בהמתנה' }, { count: status.failedCount, label: 'לבדיקה' }].map(item => <View key={item.label} style={styles.counter}><Text style={styles.count}>{item.count}</Text><Text style={styles.counterLabel}>{item.label}</Text></View>)}
    </View>}
    {status?.phase === 'uploading' && <View style={styles.progress}>
      <Text style={styles.progressLabel}>{percent === null ? 'מעלים את הקובץ…' : `הקובץ הנוכחי · ${percent}%`}</Text>
      {percent !== null && <View style={styles.track} accessibilityRole="progressbar" accessibilityLabel="העלאת הקובץ הנוכחי" accessibilityValue={{ min: 0, max: 100, now: percent }}><View style={[styles.fill, { width: `${percent}%` }]} /></View>}
    </View>}
    {destinationReady && status && (status.queuedCount > 0 || status.failedCount > 0 || status.phase === 'error') && <TouchableOpacity style={[styles.secondary, (busy || !status.enabled || status.phase === 'uploading' || status.phase === 'needsConsent') && ui.disabled]} onPress={() => { void run('retry', NativeDriveBackup.retryPending); }} disabled={busy || !status.enabled || status.phase === 'uploading' || status.phase === 'needsConsent'} accessibilityRole="button"><Text style={ui.secondaryText}>ניסיון גיבוי נוסף</Text></TouchableOpacity>}
    {actualError && <Text style={styles.error} accessibilityLiveRegion="polite">{actualError}</Text>}
    {lastActionError && <View style={styles.attemptError} accessibilityLiveRegion="polite">
      <Text style={styles.attemptTitle}>{lastActionError.action === 'connect' ? 'ניסיון החיבור האחרון לא הושלם' : 'ניסיון בחירת התיקייה האחרון לא הושלם'}</Text>
      <Text style={styles.attemptBody}>{driveActionErrorMessage(lastActionError.code)}</Text>
      {lastActionError.stage && <Text style={styles.attemptNote}>שלב: {lastActionError.stage === 'authorize' ? 'אישור החשבון' : lastActionError.stage === 'pickerResult' ? 'תוצאת בחירת התיקייה' : 'אימות הבחירה'}</Text>}
      <Text style={styles.diagnosticCode} selectable>{lastActionError.code}</Text>
      {lastActionError.authStatusCode !== undefined && <Text style={styles.attemptNote}>קוד אישור Google: {lastActionError.authStatusCode}</Text>}
      {lastActionError.activityResultCode !== undefined && <Text style={styles.attemptNote}>קוד תוצאת הבחירה: {lastActionError.activityResultCode}</Text>}
      <Text style={styles.attemptNote}>מצב הגיבוי שמוצג למעלה מתייחס לחשבון ולתיקייה הנוכחיים.</Text>
    </View>}
    {isRecording && <Text style={styles.note}>אפשר לשנות חשבון או תיקייה אחרי שההקלטה תסתיים. אפשר לכבות את הגיבוי גם בזמן ההקלטה.</Text>}
    <View style={styles.footer}>
      <TouchableOpacity style={[styles.textButton, (busy || checking) && ui.disabled]} onPress={() => { void refresh(); }} disabled={busy || checking} accessibilityRole="button"><Text style={styles.link}>בדיקת הגיבוי</Text></TouchableOpacity>
      {status?.connected && <TouchableOpacity style={[styles.textButton, setupDisabled && ui.disabled]} onPress={() => { void run('connect', NativeDriveBackup.connect); }} disabled={setupDisabled} accessibilityRole="button"><Text style={styles.link}>החלפת חשבון</Text></TouchableOpacity>}
    </View>
    {status?.connected && <TouchableOpacity style={[styles.disconnect, setupDisabled && ui.disabled]} onPress={disconnect} disabled={setupDisabled} accessibilityRole="button"><Text style={styles.disconnectText}>ניתוק החשבון</Text></TouchableOpacity>}
  </View>;
}

const styles = StyleSheet.create({
  card: { ...ui.card, padding: 20, marginTop: 26 },
  heading: { flexDirection: 'row-reverse', gap: 10, alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 },
  title: { ...ui.label, flex: 1, fontSize: 17 },
  statusRow: { flexDirection: 'row-reverse', alignItems: 'center', gap: 8, marginBottom: 10 },
  status: { ...ui.subtitle, flex: 1, fontSize: 13 },
  ready: { color: colors.green },
  body: { ...ui.body, fontSize: 14, marginBottom: 12 },
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
