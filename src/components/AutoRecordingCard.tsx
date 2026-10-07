import { localizedError, t, useLocalization, useLocalizedStyles } from '../i18n';
import React, { useState } from 'react';
import { Alert, StyleSheet, Switch, Text, TouchableOpacity, View } from 'react-native';
import { useRecording } from '../context/RecordingContext';
import { colors, ui } from '../theme';

export default function AutoRecordingCard({ compact = false, onShowDetails }: { compact?: boolean; onShowDetails?: () => void }) {
  const styles = useLocalizedStyles(baseStyles);
  const { isRTL } = useLocalization();
  const { autoRecordingStatus: status, isBusy, enableAutoRecording, disableAutoRecording, openNotificationAccessSetup } = useRecording();
  const [localError, setLocalError] = useState<string | null>(null);
  const ready = status.armed && status.listenerConnected && status.helperConnected;
  const monitoring = ready && status.state !== 'paused' && status.state !== 'error';
  const label = status.state === 'error' ? t('copy001')
    : status.state === 'paused' ? t('copy002')
      : ready && status.state === 'recording' ? t('copy003')
        : ready ? t('copy004')
          : !status.notificationAccessGranted ? t('copy005')
            : !status.listenerConnected ? t('copy006')
            : status.enabled ? t('copy007') : t('copy008');
  const run = async (operation: () => Promise<void>) => {
    setLocalError(null);
    try { await operation(); }
    catch (failure) { setLocalError(localizedError(failure)); }
  };
  const enable = () => {
    if (status.enabled) { void run(enableAutoRecording); return; }
    Alert.alert(t('copy010'), t('copy011'), [
      { text: t('copy012'), style: 'cancel' },
      { text: t('copy013'), onPress: () => { void run(enableAutoRecording); } },
    ]);
  };

  if (compact) return <TouchableOpacity style={styles.compact} onPress={onShowDetails} accessibilityRole="button" accessibilityLabel={t('copy014', { p0: label })}>
    <View style={styles.compactCopy}><Text style={styles.title}>{t('copy015')}</Text><Text style={styles.caption}>{label}</Text></View>
    <View style={[styles.badge, monitoring && styles.readyBadge]}><Text style={[styles.badgeText, monitoring && styles.readyBadgeText]}>{monitoring ? status.state === 'recording' ? t('copy016') : t('copy017') : t('copy018')}</Text></View>
    <Text style={styles.chevron}>{isRTL ? '‹' : '›'}</Text>
  </TouchableOpacity>;

  return <View style={styles.card}>
    <View style={styles.heading}><Text style={styles.title}>{t('copy015')}</Text><Switch value={status.enabled} onValueChange={next => { if (next) enable(); else void run(disableAutoRecording); }} disabled={isBusy || (!status.notificationAccessGranted && !status.enabled)} trackColor={{ false: colors.border, true: colors.green }} thumbColor={colors.surface} accessibilityLabel={t('copy019')} accessibilityState={{ disabled: isBusy || (!status.notificationAccessGranted && !status.enabled) }} /></View>
    <Text style={[styles.status, ready && styles.readyStatus]} accessibilityLiveRegion="polite">{label}</Text>
    <Text style={styles.body}>{t('copy020')}</Text>
    <Text style={styles.note}>{t('copy021')}</Text>
    {!status.notificationAccessGranted && <View style={styles.access}>
      <Text style={styles.step}>{t('copy022')}</Text>
      <Text style={styles.body}>{t('copy023')}</Text>
      <TouchableOpacity style={[styles.button, isBusy && ui.disabled]} onPress={() => { void run(openNotificationAccessSetup); }} disabled={isBusy} accessibilityRole="button"><Text style={styles.buttonText}>{t('copy024')}</Text></TouchableOpacity>
      <Text style={styles.note}>{t('copy025')}</Text>
    </View>}
    {status.notificationAccessGranted && !ready && <TouchableOpacity style={[styles.button, isBusy && ui.disabled]} onPress={enable} disabled={isBusy} accessibilityRole="button"><Text style={styles.buttonText}>{status.enabled ? t('copy026') : t('copy010')}</Text></TouchableOpacity>}
    {ready && <Text style={styles.note}>{t('copy027')}</Text>}
    {(localError || status.error) && <Text style={styles.warning} accessibilityLiveRegion="polite">{localizedError(localError || status.error)}</Text>}
  </View>;
}

const baseStyles = StyleSheet.create({
  card: { ...ui.card, padding: 20, marginBottom: 14 },
  heading: { flexDirection: 'row-reverse', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 6 },
  title: { ...ui.label, fontSize: 17 },
  status: { ...ui.subtitle, fontSize: 13, color: colors.amber, marginBottom: 12 },
  readyStatus: { color: colors.green },
  body: { ...ui.body, fontSize: 14, marginBottom: 10 },
  access: { paddingTop: 6 },
  step: { ...ui.label, fontSize: 15, marginBottom: 8 },
  button: { ...ui.primaryButton, marginVertical: 8 },
  buttonText: { ...ui.primaryText, fontSize: 14 },
  note: { ...ui.subtitle, fontSize: 12, marginTop: 6 },
  warning: { ...ui.warning, marginTop: 12 },
  compact: { minHeight: 74, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: 18, flexDirection: 'row-reverse', gap: 10, alignItems: 'center', padding: 14, marginBottom: 12 },
  compactCopy: { flex: 1 },
  caption: { ...ui.subtitle, fontSize: 11, marginTop: 3, lineHeight: 19 },
  badge: { backgroundColor: colors.background, borderRadius: 10, paddingHorizontal: 10, paddingVertical: 6 },
  readyBadge: { backgroundColor: colors.mintSoft },
  badgeText: { color: colors.muted, fontSize: 11, fontWeight: '600', writingDirection: 'rtl' },
  readyBadgeText: { color: colors.green },
  chevron: { color: colors.muted, fontSize: 25, fontWeight: '300' },
});
