import { localizedError, t, useLocalizedStyles } from '../i18n';
import React, { useState } from 'react';
import { ActivityIndicator, Alert, AppState, Linking, PermissionsAndroid, Platform, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { StackNavigationProp } from '@react-navigation/stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { useRecording } from '../context/RecordingContext';
import { useSetup } from '../setup/SetupContext';
import { nextSetupStep, SetupStep } from '../setup/readiness';
import { requestNotificationPermission } from '../utils/permissions';
import SystemSetupWizard from '../components/SystemSetupWizard';
import { WaveMark } from '../components/Visuals';
import { colors, ui } from '../theme';

const steps: SetupStep[] = ['microphone', 'notifications', 'activation', 'call_access', 'automatic'];
const titles: Record<SetupStep, string> = {
  get loading() { return t('copy329'); }, get checking() { return t('copy330'); }, get microphone() { return t('copy331'); }, get notifications() { return t('copy332'); },
  get activation() { return t('copy333'); }, get call_access() { return t('copy334'); },
  get automatic() { return t('copy335'); }, get done() { return t('copy336'); },
};

export default function SetupScreen() {
  const styles = useLocalizedStyles(baseStyles);
  const navigation = useNavigation<StackNavigationProp<RootStackParamList, 'Setup'>>();
  const insets = useSafeAreaInsets();
  const { loading, completed, readiness, refresh, completeOnboarding, completeManualOnboarding,
    setupError, systemAccessStatus } = useSetup();
  const { enableAutoRecording, openNotificationAccessSetup, autoRecordingStatus, isBusy, isRecording } = useRecording();
  const [working, setWorking] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [microphoneBlocked, setMicrophoneBlocked] = useState(false);
  const [consent, setConsent] = useState(false);
  const step = nextSetupStep(readiness, loading);
  const navigationDisabled = working || isBusy;
  const disabled = navigationDisabled || isRecording;
  const firstAutomaticOptIn = !autoRecordingStatus.enabled;
  const showProgress = step !== 'loading' && step !== 'checking' && !setupError;
  const manualAvailable = Platform.OS === 'android' && readiness.microphone && readiness.notifications;

  const run = async (action: () => Promise<void>, changesCapture = true) => {
    if (navigationDisabled || (changesCapture && isRecording)) return;
    setWorking(true); setLocalError(null);
    try {
      await action();
      await refresh();
    } catch (failure) {
      setLocalError(localizedError(failure, 'copy337'));
    } finally { setWorking(false); }
  };
  const openAppSettings = () => run(async () => { await Linking.openSettings(); });
  const microphone = () => run(async () => {
    if (Platform.OS !== 'android') throw new Error(t('copy338'));
    if (microphoneBlocked) { await Linking.openSettings(); return; }
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      { title: t('copy339'), message: t('copy340'), buttonPositive: t('continueAction'), buttonNegative: t('copy341') });
    setMicrophoneBlocked(result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN);
    if (result !== PermissionsAndroid.RESULTS.GRANTED) setLocalError(t('copy342'));
  });
  const notifications = () => run(async () => {
    const runtimeGranted = Platform.OS === 'android' && (Number(Platform.Version) < 33 ||
      await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS));
    if (runtimeGranted) await Linking.openSettings();
    else if (!(await requestNotificationPermission())) setLocalError(t('copy343'));
  });
  const finish = () => run(async () => {
    await completeOnboarding();
    navigation.reset({ index: 0, routes: [{ name: 'Home' }] });
  }, false);
  const manual = () => Alert.alert(t('copy344'),
    t('copy345'), [
      { get text() { return t('copy346'); }, style: 'cancel' },
      { get text() { return t('copy347'); }, onPress: () => { void run(async () => {
        await completeManualOnboarding();
        navigation.reset({ index: 0, routes: [{ name: 'Home' }] });
      }); } },
    ]);
  const currentIndex = step === 'done' ? 5 : Math.max(0, steps.indexOf(step));

  return <View style={styles.screen}>
    <ScrollView contentContainerStyle={[styles.content, { paddingTop: insets.top + 22, paddingBottom: insets.bottom + 28 }]} keyboardShouldPersistTaps="handled">
      <View style={styles.heading}><WaveMark size={40} /><View style={styles.headingCopy}>
        <Text style={styles.eyebrow}>{completed ? t('copy348') : t('copy349')}</Text>
        <Text style={styles.title}>{t('copy350')}</Text>
      </View></View>
      {showProgress && <View style={styles.progress} accessibilityLabel={step === 'done' ? t('copy351') : t('copy352', { p0: currentIndex + 1 })}>
        {steps.map((item, index) => <View key={item} style={[styles.progressDot, index <= currentIndex && styles.progressActive]} />)}
      </View>}
      {showProgress && step !== 'done' && <Text style={styles.counter}>{t('copy352', { p0: currentIndex + 1 })}</Text>}
      <Text style={styles.stepTitle} accessibilityRole="header">{titles[step]}</Text>
      {step === 'loading' && <ActivityIndicator size="large" color={colors.green} style={styles.spinner} />}
      {step === 'checking' && <View style={styles.card}><Text style={styles.body}>{t('copy355')}</Text></View>}
      {step === 'microphone' && <View style={styles.card}>
        <Text style={styles.body}>{Platform.OS === 'android'
          ? t('copy356')
          : t('copy357')}</Text>
        <TouchableOpacity style={[styles.primary, disabled && styles.disabled]} onPress={() => { void microphone(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{microphoneBlocked ? t('copy358') : t('copy359')}</Text></TouchableOpacity>
        {!microphoneBlocked && <TouchableOpacity style={styles.linkButton} onPress={() => { void openAppSettings(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>{t('copy360')}</Text></TouchableOpacity>}
      </View>}
      {step === 'notifications' && <View style={styles.card}>
        <Text style={styles.body}>{t('copy361')}</Text>
        <Text style={styles.body}>{t('copy362')}</Text>
        <TouchableOpacity style={[styles.primary, disabled && styles.disabled]} onPress={() => { void notifications(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{t('copy363')}</Text></TouchableOpacity>
        <TouchableOpacity style={styles.linkButton} onPress={() => { void openAppSettings(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>{t('copy364')}</Text></TouchableOpacity>
      </View>}
      {step === 'activation' && <SystemSetupWizard focused accessOverride={systemAccessStatus} onStatusChanged={refresh} />}
      {step === 'call_access' && <View style={styles.card}>
        <Text style={styles.body}>{t('copy365')}</Text>
        <Text style={styles.body}>{t('copy366')}</Text>
        <Text style={styles.small}>{t('copy367')}</Text>
        <TouchableOpacity style={[styles.primary, disabled && styles.disabled]} onPress={() => { void run(openNotificationAccessSetup); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{t('copy368')}</Text></TouchableOpacity>
      </View>}
      {step === 'automatic' && <View style={styles.card}>
        <Text style={styles.body}>{t('copy369')}</Text>
        <Text style={styles.body}>{t('copy370')}</Text>
        {firstAutomaticOptIn && <TouchableOpacity style={styles.consent} onPress={() => setConsent(value => !value)} accessibilityRole="checkbox" accessibilityState={{ checked: consent }} disabled={disabled}>
          <View style={[styles.checkbox, consent && styles.checkboxChecked]}><Text style={styles.checkmark}>{consent ? '✓' : ''}</Text></View>
          <Text style={styles.consentText}>{t('copy371')}</Text>
        </TouchableOpacity>}
        <TouchableOpacity style={[styles.primary, ((firstAutomaticOptIn && !consent) || disabled) && styles.disabled]} onPress={() => { if (AppState.currentState === 'active') void run(enableAutoRecording); }} disabled={(firstAutomaticOptIn && !consent) || disabled} accessibilityRole="button"><Text style={styles.primaryText}>{firstAutomaticOptIn ? t('copy372') : t('copy373')}</Text></TouchableOpacity>
      </View>}
      {step === 'done' && <View style={styles.card}>
        <Text style={styles.body}>{t('copy374')}</Text>
        <TouchableOpacity style={[styles.primary, navigationDisabled && styles.disabled]} onPress={() => { void finish(); }} disabled={navigationDisabled} accessibilityRole="button"><Text style={styles.primaryText}>{t('copy375')}</Text></TouchableOpacity>
      </View>}
      {(localError || setupError) && <Text style={styles.error} accessibilityLiveRegion="polite">{localizedError(localError || setupError)}</Text>}
      {working && <ActivityIndicator color={colors.green} style={styles.spinner} />}
      {step !== 'loading' && step !== 'done' && <TouchableOpacity style={styles.linkButton} onPress={() => { void run(refresh); }} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>{t('copy376')}</Text></TouchableOpacity>}
      {manualAvailable && <TouchableOpacity style={[styles.secondary, disabled && styles.disabled]} onPress={manual} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>{t('copy377')}</Text></TouchableOpacity>}
      {completed && <TouchableOpacity style={styles.linkButton} onPress={() => { if (navigation.canGoBack()) navigation.goBack(); else navigation.navigate('Home'); }} accessibilityRole="button"><Text style={styles.link}>{t('copy378')}</Text></TouchableOpacity>}
      <Text style={styles.footer}>{t('copy379')}</Text>
    </ScrollView>
  </View>;
}

const baseStyles = StyleSheet.create({
  screen: ui.screen, content: { paddingHorizontal: 22 },
  heading: { flexDirection: 'row-reverse', alignItems: 'flex-start', gap: 14, marginBottom: 24 },
  headingCopy: { flex: 1 }, eyebrow: { ...ui.subtitle, fontSize: 12, marginBottom: 6 },
  title: { ...ui.title, fontSize: 25, lineHeight: 36 },
  progress: { flexDirection: 'row-reverse', gap: 7, marginBottom: 12 },
  progressDot: { flex: 1, height: 4, borderRadius: 3, backgroundColor: colors.border }, progressActive: { backgroundColor: colors.green },
  counter: { ...ui.subtitle, fontSize: 12, marginBottom: 12 }, stepTitle: { ...ui.title, fontSize: 21, marginBottom: 18 },
  card: { ...ui.card, padding: 22, marginBottom: 12 }, body: { ...ui.body, marginBottom: 16 }, small: { ...ui.subtitle, fontSize: 12, marginBottom: 16 },
  primary: ui.primaryButton, primaryText: ui.primaryText, secondary: { ...ui.secondaryButton, marginTop: 10 }, secondaryText: ui.secondaryText,
  linkButton: { minHeight: 48, paddingVertical: 14, justifyContent: 'center' }, link: { color: colors.green, fontSize: 13, fontWeight: '600', textAlign: 'right', writingDirection: 'rtl', lineHeight: 21 },
  consent: { flexDirection: 'row-reverse', gap: 12, alignItems: 'center', marginVertical: 12, minHeight: 52 }, consentText: { ...ui.body, flex: 1 },
  checkbox: { width: 24, height: 24, borderRadius: 6, borderColor: colors.border, borderWidth: 1, alignItems: 'center', justifyContent: 'center' }, checkboxChecked: { backgroundColor: colors.green, borderColor: colors.green },
  checkmark: { color: colors.surface, fontWeight: '700' }, spinner: { marginVertical: 15 }, error: ui.warning,
  disabled: ui.disabled, footer: { ...ui.subtitle, fontSize: 12, marginTop: 20 },
});
