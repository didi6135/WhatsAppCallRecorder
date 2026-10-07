import { localizedError, t, useLocalization, useLocalizedStyles } from '../i18n';
import React, { useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { StackNavigationProp } from '@react-navigation/stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { useRecording } from '../context/RecordingContext';
import { useSetup } from '../setup/SetupContext';
import { BottomNav } from '../components/Visuals';
import { colors, ui } from '../theme';
import DriveBackupCard from '../components/DriveBackupCard';
import TelegramBackupCard from '../components/TelegramBackupCard';

function ReadinessRow({ title, ready, checking, yes, no }: {
  title: string; ready: boolean; checking: boolean; yes: string; no: string;
}) {
  const styles = useLocalizedStyles(baseStyles);
  const label = checking ? t('copy302') : ready ? yes : no;
  return <View style={styles.row} accessibilityLabel={`${title}: ${label}`}>
    <View style={[styles.check, !ready && styles.pendingCheck]}>
      <Text style={[styles.checkGlyph, !ready && styles.pendingGlyph]}>{checking ? '·' : ready ? '✓' : '−'}</Text>
    </View>
    <Text style={styles.rowTitle}>{title}</Text>
    <Text style={[styles.rowStatus, !ready && styles.pendingStatus]}>{label}</Text>
  </View>;
}

export default function SettingsScreen() {
  const styles = useLocalizedStyles(baseStyles);
  const { language, selectLanguage, error: languageError } = useLocalization();
  const navigation = useNavigation<StackNavigationProp<RootStackParamList, 'Settings'>>();
  const insets = useSafeAreaInsets();
  const { isBusy, isRecording } = useRecording();
  const { loading, readiness, refresh, setupError, supported } = useSetup();
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [savingLanguage, setSavingLanguage] = useState(false);
  const waiting = loading || checking;

  const checkAgain = async () => {
    if (waiting) return;
    setChecking(true);
    setCheckError(null);
    try { await refresh(); }
    catch { setCheckError(t('copy303')); }
    finally { setChecking(false); }
  };

  const helperMissing = supported === false ? t('copy304') : t('copy305');
  return <View style={styles.container}>
    <ScrollView contentContainerStyle={[styles.content, { paddingTop: Math.max(24, insets.top + 18) }]}>
      <Text style={styles.title}>{t('copy195')}</Text>
      <Text style={styles.subtitle}>{t('copy306')}</Text>
      <View style={[styles.summary, (!readiness.allReady || setupError) && styles.summaryPending]} accessibilityLiveRegion="polite">
        {waiting && <ActivityIndicator color={colors.green} style={styles.spinner} />}
        <Text style={styles.summaryTitle}>{waiting ? t('copy307') : setupError ? t('copy308') : readiness.allReady ? t('copy309') : t('copy310')}</Text>
        <Text style={styles.summaryBody}>{waiting ? t('copy311')
          : setupError ? t('copy312')
            : readiness.allReady ? t('copy313')
            : supported === false ? t('copy314')
              : t('copy315')}</Text>
      </View>
      <View style={styles.checklist}>
        <ReadinessRow title={t('copy245')} ready={readiness.microphone} checking={waiting} yes={t('copy316')} no={t('copy317')} />
        <ReadinessRow title={t('copy318')} ready={readiness.notifications} checking={waiting} yes={t('copy319')} no={t('copy317')} />
        <ReadinessRow title={t('copy320')} ready={readiness.callAccess} checking={waiting} yes={t('copy321')} no={t('copy305')} />
        <ReadinessRow title={t('copy322')} ready={readiness.helper} checking={waiting} yes={t('copy321')} no={helperMissing} />
        <ReadinessRow title={t('copy015')} ready={readiness.automatic} checking={waiting} yes={t('copy017')} no={t('copy323')} />
      </View>
      <TouchableOpacity style={[styles.button, (waiting || isBusy || isRecording) && ui.disabled]}
        onPress={() => navigation.navigate('Setup')} disabled={waiting || isBusy || isRecording} accessibilityRole="button">
        <Text style={ui.primaryText}>{readiness.allReady ? t('copy324') : t('copy325')}</Text>
      </TouchableOpacity>
      <TouchableOpacity style={[styles.refresh, waiting && ui.disabled]} onPress={() => { void checkAgain(); }}
        disabled={waiting} accessibilityRole="button" accessibilityLabel={t('copy326')}>
        <Text style={styles.refreshText}>{checking ? t('copy302') : t('copy327')}</Text>
      </TouchableOpacity>
      {isRecording && <Text style={styles.note}>{t('copy328')}</Text>}
      {(checkError || setupError) && <Text style={styles.error} accessibilityLiveRegion="polite">{localizedError(checkError || setupError)}</Text>}
      <View style={styles.languageCard}>
        <Text style={styles.languageTitle}>{t('languageTitle')}</Text>
        <Text style={styles.languageHelp}>{t('languageHelp')}</Text>
        <View style={styles.languageChoices}>
          {(['he', 'en'] as const).map(next => <TouchableOpacity key={next} style={[styles.languageChoice, language === next && styles.languageSelected, savingLanguage && ui.disabled]} disabled={savingLanguage}
            accessibilityRole="radio" accessibilityState={{ selected: language === next, disabled: savingLanguage }} onPress={() => {
              if (savingLanguage || next === language) return;
              setSavingLanguage(true);
              void selectLanguage(next).finally(() => setSavingLanguage(false));
            }}><Text style={[styles.languageName, language === next && styles.languageSelectedText]}>{t(next === 'he' ? 'languageHebrew' : 'languageEnglish')}</Text></TouchableOpacity>)}
        </View>
        {savingLanguage && <Text style={styles.note}>{t('languageSaving')}</Text>}
        {languageError && <Text style={styles.error} accessibilityLiveRegion="polite">{t(languageError)}</Text>}
      </View>
      <DriveBackupCard isRecording={isRecording} recorderBusy={isBusy} />
      <TelegramBackupCard isRecording={isRecording} recorderBusy={isBusy} />
    </ScrollView>
    <BottomNav current="Settings" />
  </View>;
}

const baseStyles = StyleSheet.create({
  container: ui.screen,
  content: { ...ui.content, paddingBottom: 24 },
  title: { ...ui.title, fontSize: 28, lineHeight: 38 },
  subtitle: { ...ui.subtitle, marginTop: 5, marginBottom: 24 },
  summary: { padding: 20, borderRadius: 22, backgroundColor: colors.mintSoft, borderWidth: 1, borderColor: '#D4EBDD', marginBottom: 18 },
  summaryPending: { backgroundColor: colors.surface, borderColor: colors.border },
  summaryTitle: { ...ui.label, fontSize: 19, lineHeight: 29 },
  summaryBody: { ...ui.subtitle, marginTop: 5 },
  spinner: { alignSelf: 'flex-end', marginBottom: 10 },
  checklist: { ...ui.card, padding: 0, paddingHorizontal: 16, marginBottom: 24, overflow: 'hidden' },
  row: { flexDirection: 'row-reverse', alignItems: 'center', gap: 10, paddingVertical: 18, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  check: { width: 27, height: 27, borderRadius: 14, backgroundColor: colors.mintSoft, justifyContent: 'center', alignItems: 'center' },
  pendingCheck: { backgroundColor: colors.background },
  checkGlyph: { color: colors.green, fontSize: 17, fontWeight: '700' },
  pendingGlyph: { color: colors.muted },
  rowTitle: { ...ui.label, flex: 1, fontSize: 14, lineHeight: 23 },
  rowStatus: { color: colors.green, textAlign: 'left', writingDirection: 'rtl', fontSize: 12, lineHeight: 20, maxWidth: '34%', flexShrink: 1 },
  pendingStatus: { color: colors.muted },
  button: ui.primaryButton,
  refresh: { minHeight: 52, alignItems: 'center', justifyContent: 'center', marginTop: 8, paddingVertical: 12 },
  refreshText: { color: colors.green, fontWeight: '600', fontSize: 14, writingDirection: 'rtl', textAlign: 'center' },
  note: { ...ui.subtitle, fontSize: 12, textAlign: 'center', marginTop: 8 },
  error: { ...ui.warning, marginTop: 10 },
  languageCard: { ...ui.card, marginTop: 24, padding: 20 },
  languageTitle: { ...ui.label, fontSize: 17 },
  languageHelp: { ...ui.subtitle, fontSize: 12, marginTop: 6 },
  languageChoices: { flexDirection: 'row-reverse', gap: 10, marginTop: 16 },
  languageChoice: { flex: 1, minHeight: 52, borderRadius: 14, borderWidth: 1, borderColor: colors.border, alignItems: 'center', justifyContent: 'center', padding: 12 },
  languageSelected: { backgroundColor: colors.mintSoft, borderColor: colors.green },
  languageName: { color: colors.muted, fontSize: 15, fontWeight: '600', textAlign: 'center' },
  languageSelectedText: { color: colors.green },
});
