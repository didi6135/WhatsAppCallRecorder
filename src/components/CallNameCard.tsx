import React, { useCallback, useRef, useState } from 'react';
import { ActivityIndicator, AppState, StyleSheet, Switch, Text, TouchableOpacity, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { NativeRecorder } from '../services/NativeRecorder';
import { localizedError, t, useLocalizedStyles } from '../i18n';
import { colors, ui } from '../theme';

export default function CallNameCard({ isRecording, recorderBusy }: { isRecording: boolean; recorderBusy: boolean }) {
  const styles = useLocalizedStyles(baseStyles);
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const operation = useRef(0);

  const refresh = useCallback(async () => {
    const request = ++operation.current;
    setLoading(true); setSaving(false); setError(null);
    try {
      const result = await NativeRecorder.getCallNameStatus();
      if (request === operation.current) setEnabled(result.enabled);
    } catch {
      if (request === operation.current) { setEnabled(null); setError(t('callNameReadFailed')); }
    } finally { if (request === operation.current) setLoading(false); }
  }, []);

  useFocusEffect(useCallback(() => {
    void refresh();
    const subscription = AppState.addEventListener('change', state => { if (state === 'active') void refresh(); });
    return () => { ++operation.current; subscription.remove(); };
  }, [refresh]));

  const disabled = loading || saving || enabled === null || isRecording || recorderBusy;
  const change = async (next: boolean) => {
    if (disabled || next === enabled) return;
    const request = ++operation.current;
    setSaving(true); setError(null);
    try {
      const result = await NativeRecorder.setCallNameEnabled(next);
      if (request === operation.current) setEnabled(result.enabled);
    } catch (failure) {
      if (request === operation.current) setError(localizedError(failure, 'callNameSaveFailed'));
    } finally { if (request === operation.current) setSaving(false); }
  };

  return <View style={styles.card}>
    <View style={styles.heading}>
      <Text style={styles.title}>{t('callNameTitle')}</Text>
      <Switch value={enabled === true} onValueChange={next => { void change(next); }} disabled={disabled}
        trackColor={{ false: colors.border, true: colors.green }} thumbColor={colors.surface}
        accessibilityLabel={t('callNameTitle')} accessibilityHint={t('callNameHelp')} accessibilityState={{ disabled }} />
    </View>
    <Text style={styles.body}>{t('callNameHelp')}</Text>
    <View style={styles.statusRow}>
      {(loading || saving) && <ActivityIndicator color={colors.green} />}
      <Text style={styles.status} accessibilityLiveRegion="polite">{loading ? t('callNameChecking') : saving ? t('callNameSaving') : enabled === null ? t('callNameUnavailable') : enabled ? t('callNameOn') : t('callNameOff')}</Text>
    </View>
    {error && <Text style={styles.error} accessibilityLiveRegion="polite">{localizedError(error, 'callNameReadFailed')}</Text>}
    {error && !saving && <TouchableOpacity style={styles.retry} onPress={() => { void refresh(); }} disabled={loading} accessibilityRole="button"><Text style={ui.secondaryText}>{t('copy327')}</Text></TouchableOpacity>}
  </View>;
}

const baseStyles = StyleSheet.create({
  card: { ...ui.card, padding: 20, marginTop: 26 },
  heading: { flexDirection: 'row-reverse', gap: 10, alignItems: 'center', marginBottom: 10 },
  title: { ...ui.label, flex: 1, fontSize: 17 },
  body: { ...ui.body, fontSize: 14, marginBottom: 12 },
  statusRow: { flexDirection: 'row-reverse', alignItems: 'center', gap: 8 },
  status: { ...ui.subtitle, flex: 1, fontSize: 13 },
  error: { ...ui.warning, marginTop: 10 },
  retry: { ...ui.secondaryButton, marginTop: 12 },
});
