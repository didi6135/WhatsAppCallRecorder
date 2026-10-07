import React, { useCallback, useState } from 'react';
import { ActivityIndicator, Alert, Linking, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { useLocalization, useLocalizedStyles } from '../i18n';
import { telegramText as text, TelegramTextKey } from '../i18n/telegram';
import { useTelegramBackup, TelegramBackupAction } from '../hooks/useTelegramBackup';
import { NativeTelegramBackup, TelegramBackupStatus, telegramErrorMessage } from '../services/NativeTelegramBackup';
import { colors, ui } from '../theme';

const phaseKeys: Record<TelegramBackupStatus['statusCode'], TelegramTextKey> = {DISCONNECTED: 'disconnected', AWAITING_CHAT: 'awaiting', DISABLED: 'disabled', READY: 'ready', UPLOADING: 'uploading', ACTION_REQUIRED: 'required'};
const actionKeys: Record<TelegramBackupAction, TelegramTextKey> = {connect: 'connecting', verify: 'verifying', enable: 'enabling', pause: 'pausing', retry: 'retrying', disconnect: 'disconnecting'};

export default function TelegramBackupCard({isRecording, recorderBusy}: {isRecording: boolean; recorderBusy: boolean}) {
  const {language} = useLocalization();
  const styles = useLocalizedStyles(baseStyles);
  const {status, checking, action, error, refresh, run} = useTelegramBackup();
  const [token, setToken] = useState('');
  const [linkFailed, setLinkFailed] = useState(false);
  // Input is local, short-lived state only. Clear it whenever Settings loses focus, including unmount.
  useFocusEffect(useCallback(() => () => { setToken(''); }, []));
  const busy = action !== null || recorderBusy;
  const setupDisabled = busy || isRecording || !status;
  const enableDisabled = setupDisabled || !status?.connected || status.connectionPending;
  const switchDisabled = busy || (!status?.enabled && enableDisabled);
  const unknown = (status?.unknownOutcome || 0) > 0 ||
    ['UNKNOWN_OUTCOME', 'UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION'].includes(error?.code || '') ||
    ['UNKNOWN_OUTCOME', 'UNKNOWN_OUTCOME_REQUIRES_CONFIRMATION'].includes(status?.lastErrorCode || '');
  const retryDisabled = busy || !status?.enabled || status.uploading;
  const hasWork = !!status && (status.queued > 0 || status.failed > 0 || status.unknownOutcome > 0 || unknown);
  const actualError = error ? telegramErrorMessage(error.code) : status?.lastErrorCode ? telegramErrorMessage(status.lastErrorCode) : null;
  const messageCode = error?.code || status?.lastErrorCode;
  const tr = (key: TelegramTextKey) => text(key, language);

  const openLink = async (url: string) => {
    setLinkFailed(false);
    try { await Linking.openURL(url); } catch { setLinkFailed(true); }
  };
  const connect = () => {
    if (setupDisabled || !token.trim()) return;
    const submitted = token.trim();
    setToken('');
    setLinkFailed(false);
    void run('connect', () => NativeTelegramBackup.connect(submitted));
  };
  const enable = () => {
    if (enableDisabled) return;
    Alert.alert(tr('enableTitle'), tr('enableBody'), [
      {text: tr('cancel'), style: 'cancel'},
      {text: tr('enableConfirm'), onPress: () => { void run('enable', () => NativeTelegramBackup.setEnabled(true)); }},
    ]);
  };
  const retry = () => {
    if (retryDisabled) return;
    if (unknown) {
      Alert.alert(tr('unknownTitle'), tr('unknownBody'), [
        {text: tr('cancel'), style: 'cancel'},
        {text: tr('unknownConfirm'), onPress: () => { void run('retry', () => NativeTelegramBackup.retryPending(true)); }},
      ]);
    } else void run('retry', () => NativeTelegramBackup.retryPending(false));
  };
  const disconnect = () => {
    if (setupDisabled) return;
    Alert.alert(tr('disconnectTitle'), tr('disconnectBody'), [
      {text: tr('cancel'), style: 'cancel'},
      {text: tr('disconnectConfirm'), onPress: () => { setToken(''); void run('disconnect', NativeTelegramBackup.disconnect); }},
    ]);
  };

  return <View style={styles.card}>
    <View style={styles.heading}><Text style={styles.title}>{tr('title')}</Text><Switch value={status?.enabled === true} onValueChange={next => { if (next) enable(); else void run('pause', () => NativeTelegramBackup.setEnabled(false)); }} disabled={switchDisabled} trackColor={{false: colors.border, true: colors.green}} thumbColor={colors.surface} accessibilityLabel={tr('switchLabel')} accessibilityState={{disabled: switchDisabled}} /></View>
    <View style={styles.statusRow}>{(checking && !status || action !== null) && <ActivityIndicator color={colors.green} size="small" />}<Text style={[styles.status, status?.statusCode === 'READY' && styles.ready]} accessibilityLiveRegion="polite">{action ? tr(actionKeys[action]) : status ? tr(phaseKeys[status.statusCode]) : tr(checking ? 'checking' : 'unavailable')}</Text></View>
    <Text style={styles.body}>{tr('description')}</Text>
    {status && !status.connected && <View style={styles.setup}>
      <Text style={styles.setupTitle}>{tr('setupTitle')}</Text><Text style={styles.body}>{tr('setupBody')}</Text>
      <TouchableOpacity style={styles.textButton} onPress={() => { void openLink('https://t.me/BotFather'); }} accessibilityRole="link"><Text style={styles.link}>{tr('botFather')}</Text></TouchableOpacity>
      <Text style={styles.label}>{tr('tokenLabel')}</Text><TextInput value={token} onChangeText={setToken} maxLength={256} secureTextEntry autoCorrect={false} autoCapitalize="none" autoComplete="off" textContentType="none" placeholder={tr('tokenPlaceholder')} placeholderTextColor={colors.muted} editable={!setupDisabled} onSubmitEditing={connect} style={styles.token} accessibilityLabel={tr('tokenLabel')} />
      <Text style={styles.note}>{tr('tokenNote')}</Text>
      <TouchableOpacity style={[styles.primary, (setupDisabled || !token.trim()) && ui.disabled]} onPress={connect} disabled={setupDisabled || !token.trim()} accessibilityRole="button"><Text style={styles.primaryText}>{tr('connect')}</Text></TouchableOpacity>
    </View>}
    {status?.connectionPending && status.startLink && <View style={styles.destination}>
      <Text style={styles.note}>{tr('pendingNote')}</Text>
      <TouchableOpacity style={[styles.primary, setupDisabled && ui.disabled]} onPress={() => { void openLink(status.startLink!); }} disabled={setupDisabled} accessibilityRole="link"><Text style={styles.primaryText}>{tr('start')}</Text></TouchableOpacity>
      <TouchableOpacity style={[styles.secondary, setupDisabled && ui.disabled]} onPress={() => { void run('verify', NativeTelegramBackup.verifyConnection); }} disabled={setupDisabled} accessibilityRole="button"><Text style={styles.secondaryText}>{tr('verify')}</Text></TouchableOpacity>
    </View>}
    {status?.connected && <View style={styles.destination}><Text style={styles.label}>{tr('bot')}</Text><Text style={styles.bot} selectable>@{status.botUsername}</Text></View>}
    {status?.connected && !status.enabled && !status.connectionPending && <TouchableOpacity style={[styles.primary, enableDisabled && ui.disabled]} onPress={enable} disabled={enableDisabled} accessibilityRole="button"><Text style={styles.primaryText}>{tr('enable')}</Text></TouchableOpacity>}
    {status && <View style={styles.counters}>{(['queued', 'uploaded', 'failed', 'unknown'] as const).map(key => <View key={key} style={styles.counter}><Text style={styles.count}>{status[key === 'unknown' ? 'unknownOutcome' : key]}</Text><Text style={styles.counterLabel}>{tr(key)}</Text></View>)}</View>}
    {status && <Text style={styles.note}>{tr('countersNote')}</Text>}
    {unknown && <Text style={styles.warning}>{tr('unknownNote')}</Text>}
    {hasWork && <TouchableOpacity style={[styles.secondary, retryDisabled && ui.disabled]} onPress={retry} disabled={retryDisabled} accessibilityRole="button"><Text style={styles.secondaryText}>{tr('retry')}</Text></TouchableOpacity>}
    {actualError && <View style={styles.error} accessibilityLiveRegion="polite"><Text style={styles.errorText}>{actualError}</Text>{messageCode && <Text style={styles.code} selectable>{messageCode}</Text>}</View>}
    {linkFailed && <Text style={styles.warning} accessibilityLiveRegion="polite">{tr('linkFailed')}</Text>}
    {isRecording && <Text style={styles.note}>{tr('recordingNote')}</Text>}
    <TouchableOpacity style={[styles.textButton, (busy || checking) && ui.disabled]} onPress={() => { void refresh(); }} disabled={busy || checking} accessibilityRole="button"><Text style={styles.link}>{tr('refresh')}</Text></TouchableOpacity>
    {(status?.connected || status?.connectionPending) && <TouchableOpacity style={[styles.disconnect, setupDisabled && ui.disabled]} onPress={disconnect} disabled={setupDisabled} accessibilityRole="button"><Text style={styles.disconnectText}>{tr('disconnect')}</Text></TouchableOpacity>}
  </View>;
}

const baseStyles = StyleSheet.create({
  card: {...ui.card, padding: 20, marginTop: 26}, heading: {flexDirection: 'row-reverse', gap: 10, alignItems: 'center', justifyContent: 'space-between', marginBottom: 10}, title: {...ui.label, flex: 1, fontSize: 17}, statusRow: {flexDirection: 'row-reverse', alignItems: 'center', gap: 8, marginBottom: 10}, status: {...ui.subtitle, flex: 1, fontSize: 13}, ready: {color: colors.green}, body: {...ui.body, fontSize: 14, marginBottom: 12},
  setup: {marginTop: 6}, setupTitle: {...ui.label, fontSize: 15, marginBottom: 7}, label: {...ui.subtitle, fontSize: 12, marginBottom: 6}, token: {borderWidth: 1, borderColor: colors.border, backgroundColor: colors.background, borderRadius: 12, color: colors.ink, minHeight: 52, paddingHorizontal: 13, paddingVertical: 12, fontSize: 14, textAlign: 'left', writingDirection: 'ltr'}, note: {...ui.subtitle, fontSize: 12, marginTop: 8, marginBottom: 5}, destination: {padding: 14, backgroundColor: colors.background, borderRadius: 16, marginVertical: 9}, bot: {color: colors.ink, fontSize: 15, writingDirection: 'ltr', textAlign: 'right'},
  primary: {...ui.primaryButton, marginVertical: 7}, primaryText: {...ui.primaryText}, secondary: {...ui.secondaryButton, marginVertical: 7}, secondaryText: {...ui.secondaryText}, counters: {flexDirection: 'row-reverse', flexWrap: 'wrap', borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 16, marginTop: 16, gap: 12}, counter: {flexGrow: 1, flexBasis: '40%', alignItems: 'center'}, count: {color: colors.ink, fontSize: 22, fontWeight: '600', fontVariant: ['tabular-nums']}, counterLabel: {...ui.subtitle, fontSize: 11, textAlign: 'center', marginTop: 3}, warning: {...ui.warning, marginTop: 12},
  error: {backgroundColor: colors.amberSoft, borderRadius: 16, padding: 14, marginTop: 12}, errorText: {...ui.body, color: colors.amber, fontSize: 13, lineHeight: 22}, code: {color: colors.amber, fontSize: 11, lineHeight: 19, writingDirection: 'ltr', textAlign: 'right', marginTop: 5}, textButton: {minHeight: 48, alignItems: 'center', justifyContent: 'center', paddingVertical: 12}, link: {color: colors.green, fontSize: 13, fontWeight: '600', writingDirection: 'rtl', textAlign: 'center'}, disconnect: {minHeight: 48, alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: colors.border, marginTop: 4, paddingVertical: 12}, disconnectText: {color: colors.danger, fontSize: 13, writingDirection: 'rtl'},
});
