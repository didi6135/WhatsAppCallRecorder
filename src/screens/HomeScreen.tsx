import React, { useState } from 'react';
import { View, Text, StyleSheet, Alert, TouchableOpacity, ScrollView } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { StackNavigationProp } from '@react-navigation/stack';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { useRecording } from '../context/RecordingContext';
import RecordButton from '../components/RecordButton';
import { BottomNav, WaveMark } from '../components/Visuals';
import { colors, ui } from '../theme';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const messageOf = (error: unknown) => error instanceof Error ? error.message : 'הפעולה לא הושלמה. נסו שוב.';
const normalized = (value: number) => Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0));

function LevelMeter({ label, level, active }: { label: string; level: number; active: boolean }) {
  const percent = active ? Math.round(normalized(level) * 100) : 0;
  return (
    <View style={styles.meter}>
      <Text style={styles.meterLabel}>{label}: {percent}%</Text>
      <View style={styles.meterTrack} accessibilityRole="progressbar" accessibilityLabel={label} accessibilityValue={{ min: 0, max: 100, now: percent }}>
        <View style={[styles.meterFill, { width: `${percent}%` }]} />
      </View>
    </View>
  );
}

export default function HomeScreen() {
  const insets = useSafeAreaInsets();
  const navigation = useNavigation<StackNavigationProp<RootStackParamList, 'Home'>>();
  const { isRecording, recordingTime, startRecording, stopRecording, requestPermissions, status, isBusy, error,
    systemAccessStatus } = useRecording();
  const [captureSource, setCaptureSource] = useState<'microphone' | 'usb'>('microphone');
  const source = isRecording ? status.source : captureSource;
  const helperReady = !systemAccessStatus.connecting && (systemAccessStatus.helperConnected || status.usbConnected);
  const systemReady = systemAccessStatus.available && (helperReady || (systemAccessStatus.paired && systemAccessStatus.wirelessDebuggingEnabled));
  const startUnavailable = !isRecording && source === 'usb' && !systemReady;

  const chooseSource = (next: 'microphone' | 'usb') => {
    if (isRecording || isBusy) return;
    setCaptureSource(next);
  };

  const begin = async () => {
    try {
      if (!(await requestPermissions())) {
        Alert.alert('נדרשת הרשאת מיקרופון', 'אפשר לאפשר גישה למיקרופון דרך ההגדרות.');
        return;
      }
      await startRecording(captureSource);
    } catch (failure) {
      Alert.alert('לא ניתן להתחיל להקליט', messageOf(failure));
    }
  };

  const handleRecordPress = async () => {
    if (isBusy) return;
    if (isRecording) {
      try {
        const saved = await stopRecording();
        if (!saved) {
          Alert.alert('לא נשמר קובץ חדש', 'בדקו את ההקלטות ואת הודעת השגיאה לפני ניסיון נוסף.');
        } else if (saved.status === 'interrupted' || saved.status === 'recovered') {
          Alert.alert('הקובץ נשמר עם אזהרה', 'ההקלטה הופרעה או שוחזרה. ייתכן שחסר קול; האזינו לקובץ.');
        } else if (saved.captureSource === 'usb') {
          const outputMissing = (saved.outputSoundMs ?? 0) < 200;
          const micMissing = (saved.microphoneSoundMs ?? 0) < 200;
          Alert.alert(outputMissing || micMissing ? 'הקובץ נשמר עם אזהרה' : 'הקובץ נשמר', outputMissing || micMissing
            ? `${outputMissing ? 'לא זוהה קול מספיק בערוץ שמע השיחה. ' : ''}${micMissing ? 'לא זוהה קול מספיק בערוץ המיקרופון. ' : ''}האזינו כדי לבדוק אם שני הקולות נשמעים.`
            : 'זוהה אות בשני הערוצים. האזינו כדי לוודא ששני קולות השיחה אכן נשמעים.');
        } else if (saved.status === 'captured') {
          Alert.alert('הקובץ נשמר', 'זוהה קול מהמיקרופון. האזינו כדי לבדוק אילו משתתפים נשמעים.');
        } else {
          Alert.alert('הקובץ נשמר עם אזהרה', 'לא זוהה קול מספק או שהמיקרופון הושתק. האזינו לקובץ לבדיקה.');
        }
      } catch (failure) {
        Alert.alert('השמירה לא הושלמה', messageOf(failure));
      }
      return;
    }
    if (startUnavailable) return;
    Alert.alert(source === 'usb' ? 'הקלטת שיחת WhatsApp' : 'הקלטת מיקרופון', 'יש ליידע את משתתפי השיחה לפני ההקלטה.', [
      { text: 'ביטול', style: 'cancel' },
      { text: 'להקליט בידיעת המשתתפים', onPress: () => { void begin(); } },
    ]);
  };

  const warning = status.isSilenced
    ? 'המיקרופון הושתק. ייתכן שערוץ המיקרופון שקט.'
    : status.silentForMs >= 5000
      ? 'לא זוהה קול בחמש השניות האחרונות. בדקו את מדי הקול והאזינו לקובץ בסיום.'
      : null;
  const accessLabel = helperReady ? 'רכיב הקלטת השיחות מחובר'
    : systemAccessStatus.connecting ? 'מחבר את רכיב ההקלטה…'
      : systemReady ? 'רכיב השיחות יופעל בתחילת ההקלטה'
        : 'כדי להקליט שיחה, השלימו את ההגדרה';

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={[styles.content, { paddingTop: Math.max(18, insets.top + 10) }]}>
        <View style={styles.header}>
          <WaveMark size={38} />
          <Text style={styles.title}>הקלטה</Text>
        </View>
        <Text style={styles.sourceLabel}>מה להקליט?</Text>
        <View style={styles.modeRow}>
          <TouchableOpacity style={[styles.mode, source === 'microphone' && styles.modeSelected]} onPress={() => chooseSource('microphone')} disabled={isRecording || isBusy} accessibilityRole="radio" accessibilityState={{ selected: source === 'microphone', disabled: isRecording || isBusy }}><Text style={[styles.modeText, source === 'microphone' && styles.modeTextSelected]}>מיקרופון</Text></TouchableOpacity>
          <TouchableOpacity style={[styles.mode, source === 'usb' && styles.modeSelected]} onPress={() => chooseSource('usb')} disabled={isRecording || isBusy} accessibilityRole="radio" accessibilityState={{ selected: source === 'usb', disabled: isRecording || isBusy }}><Text style={[styles.modeText, source === 'usb' && styles.modeTextSelected]}>שיחת WhatsApp</Text></TouchableOpacity>
        </View>
        {source === 'usb' && !isRecording && <TouchableOpacity style={styles.accessLink} onPress={() => navigation.navigate('Settings')} accessibilityRole="button" accessibilityLabel={`${accessLabel}. פתיחת הגדרות`}>
          <Text style={[styles.accessText, helperReady && styles.accessConnected]}>{accessLabel}</Text>
          <Text style={styles.linkText}>הגדרות</Text>
        </TouchableOpacity>}
        <View style={[styles.card, isRecording && styles.cardActive]}>
          <Text style={[styles.state, isRecording && styles.recording]}>{isRecording ? 'מקליט עכשיו' : 'לחצו כדי להתחיל'}</Text>
          {isRecording ? <Text style={styles.timer} accessibilityLabel={`משך ההקלטה ${recordingTime}`} accessibilityLiveRegion="none">{recordingTime}</Text> : <Text style={styles.caption}>{source === 'usb' ? 'התחילו כאן את ההקלטה, ואז עברו לשיחה.' : 'הקול מהמיקרופון יישמר במכשיר.'}</Text>}
          {isRecording && <View style={styles.meters}>{source === 'usb' && <LevelMeter label="שמע השיחה" level={status.outputLevel} active />}<LevelMeter label="מיקרופון" level={source === 'usb' ? status.microphoneLevel : status.level} active /></View>}
          <View style={styles.recordButton}><RecordButton isRecording={isRecording} onPress={() => { void handleRecordPress(); }} disabled={isBusy || startUnavailable || (!isRecording && source === 'usb' && systemAccessStatus.connecting)} /></View>
          <Text style={styles.small}>{isBusy ? 'מבצע פעולה…' : startUnavailable ? 'נדרשת הגדרה לפני הקלטת שיחה' : isRecording ? 'בעצירה הקובץ יישמר בהקלטות שלי' : 'יש ליידע את המשתתפים לפני ההקלטה'}</Text>
          {isRecording && warning && <Text style={styles.warning}>{warning}</Text>}
          {isRecording && source === 'microphone' && status.audioMode !== 0 && <Text style={styles.warning}>המכשיר במצב תקשורת. Android עשוי לחסום הקלטה מהמיקרופון באותו מכשיר.</Text>}
          {error && <Text style={styles.warning} accessibilityLiveRegion="polite">{error}</Text>}
          {systemAccessStatus.error && systemAccessStatus.error !== error && source === 'usb' && <Text style={styles.warning}>{systemAccessStatus.error}</Text>}
        </View>
      </ScrollView>
      <BottomNav current="Home" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: ui.screen,
  content: { ...ui.content, flexGrow: 1 },
  header: { flexDirection: 'row-reverse', alignItems: 'center', gap: 12, marginBottom: 28 },
  title: { ...ui.title, flex: 1, fontSize: 26, lineHeight: 36 },
  sourceLabel: { ...ui.label, marginBottom: 10 },
  modeRow: { flexDirection: 'row-reverse', gap: 8, padding: 4, borderRadius: 18, backgroundColor: colors.border },
  mode: { flex: 1, minHeight: 52, paddingHorizontal: 10, paddingVertical: 14, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  modeSelected: { backgroundColor: colors.surface },
  modeText: { color: colors.muted, fontSize: 15, fontWeight: '500', writingDirection: 'rtl', textAlign: 'center' },
  modeTextSelected: { color: colors.ink, fontWeight: '700' },
  accessLink: { minHeight: 52, flexDirection: 'row-reverse', flexWrap: 'wrap', alignItems: 'center', gap: 10, paddingVertical: 12 },
  accessText: { ...ui.subtitle, flexGrow: 1, flexShrink: 1 },
  accessConnected: { color: colors.green },
  linkText: { color: colors.green, fontSize: 14, fontWeight: '600', textAlign: 'right', writingDirection: 'rtl' },
  card: { ...ui.card, minHeight: 250, justifyContent: 'center', marginTop: 20, paddingVertical: 28 },
  cardActive: { borderColor: '#E8C9C5' },
  state: { fontSize: 22, lineHeight: 32, fontWeight: '700', color: colors.ink, textAlign: 'center', writingDirection: 'rtl' },
  recording: { color: colors.danger },
  caption: { ...ui.body, textAlign: 'center', marginTop: 10 },
  timer: { fontSize: 36, color: colors.ink, fontVariant: ['tabular-nums'], textAlign: 'center', marginTop: 12, writingDirection: 'ltr' },
  meters: { marginTop: 22 },
  meter: { marginBottom: 12 },
  meterLabel: { ...ui.subtitle },
  meterTrack: { height: 6, backgroundColor: colors.border, borderRadius: 4, overflow: 'hidden', marginTop: 6 },
  meterFill: { height: 6, backgroundColor: colors.green, borderRadius: 4 },
  warning: { ...ui.warning, marginTop: 12 },
  recordButton: { marginTop: 28, marginBottom: 12 },
  small: { ...ui.subtitle, textAlign: 'center' },
});
