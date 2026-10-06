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

function ReadinessRow({ title, ready, checking, yes, no }: {
  title: string; ready: boolean; checking: boolean; yes: string; no: string;
}) {
  const label = checking ? 'בודקים…' : ready ? yes : no;
  return <View style={styles.row} accessibilityLabel={`${title}: ${label}`}>
    <View style={[styles.check, !ready && styles.pendingCheck]}>
      <Text style={[styles.checkGlyph, !ready && styles.pendingGlyph]}>{checking ? '·' : ready ? '✓' : '−'}</Text>
    </View>
    <Text style={styles.rowTitle}>{title}</Text>
    <Text style={[styles.rowStatus, !ready && styles.pendingStatus]}>{label}</Text>
  </View>;
}

export default function SettingsScreen() {
  const navigation = useNavigation<StackNavigationProp<RootStackParamList, 'Settings'>>();
  const insets = useSafeAreaInsets();
  const { isBusy, isRecording } = useRecording();
  const { loading, readiness, refresh, setupError, supported } = useSetup();
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const waiting = loading || checking;

  const checkAgain = async () => {
    if (waiting) return;
    setChecking(true);
    setCheckError(null);
    try { await refresh(); }
    catch { setCheckError('הבדיקה לא הושלמה. אפשר לנסות שוב.'); }
    finally { setChecking(false); }
  };

  const helperMissing = supported === false ? 'לא זמין במכשיר' : 'לא מחובר';
  return <View style={styles.container}>
    <ScrollView contentContainerStyle={[styles.content, { paddingTop: Math.max(24, insets.top + 18) }]}>
      <Text style={styles.title}>הגדרות</Text>
      <Text style={styles.subtitle}>כל מה שהאפליקציה צריכה, במקום אחד.</Text>
      <View style={[styles.summary, (!readiness.allReady || setupError) && styles.summaryPending]} accessibilityLiveRegion="polite">
        {waiting && <ActivityIndicator color={colors.green} style={styles.spinner} />}
        <Text style={styles.summaryTitle}>{waiting ? 'בודקים את החיבור' : setupError ? 'הבדיקה לא הושלמה' : readiness.allReady ? 'הכול מחובר' : 'יש שלבים להשלמה'}</Text>
        <Text style={styles.summaryBody}>{waiting ? 'קוראים את מצב ההרשאות וההפעלה בטלפון.'
          : setupError ? 'נבדוק שוב לפני שנציג שהכול מחובר.'
            : readiness.allReady ? 'ההרשאות מאושרות ורכיב ההקלטה פעיל.'
            : supported === false ? 'אפשר להקליט מהמיקרופון. הקלטת שיחות אינה זמינה במכשיר הזה.'
              : 'נעבור יחד על מה שחסר, צעד אחר צעד.'}</Text>
      </View>
      <View style={styles.checklist}>
        <ReadinessRow title="מיקרופון" ready={readiness.microphone} checking={waiting} yes="מאושר" no="נדרש אישור" />
        <ReadinessRow title="התראות האפליקציה" ready={readiness.notifications} checking={waiting} yes="מאושרות" no="נדרש אישור" />
        <ReadinessRow title="זיהוי שיחות" ready={readiness.callAccess} checking={waiting} yes="מחובר" no="לא מחובר" />
        <ReadinessRow title="רכיב הקלטת השיחות" ready={readiness.helper} checking={waiting} yes="מחובר" no={helperMissing} />
        <ReadinessRow title="הקלטה אוטומטית" ready={readiness.automatic} checking={waiting} yes="פעילה" no="לא פעילה" />
      </View>
      <TouchableOpacity style={[styles.button, (waiting || isBusy || isRecording) && ui.disabled]}
        onPress={() => navigation.navigate('Setup')} disabled={waiting || isBusy || isRecording} accessibilityRole="button">
        <Text style={ui.primaryText}>{readiness.allReady ? 'ניהול ההפעלה' : 'להשלמת ההגדרות'}</Text>
      </TouchableOpacity>
      <TouchableOpacity style={[styles.refresh, waiting && ui.disabled]} onPress={() => { void checkAgain(); }}
        disabled={waiting} accessibilityRole="button" accessibilityLabel="בדיקת ההרשאות והחיבור מחדש">
        <Text style={styles.refreshText}>{checking ? 'בודקים…' : 'בדיקה מחדש'}</Text>
      </TouchableOpacity>
      {isRecording && <Text style={styles.note}>אפשר לשנות את ההפעלה אחרי שההקלטה תסתיים.</Text>}
      {(checkError || setupError) && <Text style={styles.error} accessibilityLiveRegion="polite">{checkError || setupError}</Text>}
      <DriveBackupCard isRecording={isRecording} recorderBusy={isBusy} />
    </ScrollView>
    <BottomNav current="Settings" />
  </View>;
}

const styles = StyleSheet.create({
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
});
