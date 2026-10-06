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
  loading: 'בודקים את הטלפון', checking: 'בודקים את מוכנות המקליט', microphone: 'מאפשרים שימוש במיקרופון', notifications: 'מאפשרים התראות למקליט',
  activation: 'מחברים את ההקלטה לטלפון', call_access: 'מאפשרים זיהוי שיחות',
  automatic: 'בוחרים איך להקליט', done: 'ההגדרות אומתו',
};

export default function SetupScreen() {
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
      setLocalError(failure instanceof Error && /[\u0590-\u05ff]/.test(failure.message)
        ? failure.message : 'הפעולה לא הושלמה. אפשר לנסות שוב או לפתוח את הגדרות הטלפון.');
    } finally { setWorking(false); }
  };
  const openAppSettings = () => run(async () => { await Linking.openSettings(); });
  const microphone = () => run(async () => {
    if (Platform.OS !== 'android') throw new Error('הקלטה באפליקציה זמינה ב־Android בלבד.');
    if (microphoneBlocked) { await Linking.openSettings(); return; }
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
      { title: 'הרשאת מיקרופון', message: 'המקליט צריך גישה למיקרופון כדי לשמור את הקול שלך.', buttonPositive: 'המשך', buttonNegative: 'לא עכשיו' });
    setMicrophoneBlocked(result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN);
    if (result !== PermissionsAndroid.RESULTS.GRANTED) setLocalError('המיקרופון עדיין לא אושר. אפשר לאשר את ההרשאה בהגדרות האפליקציה.');
  });
  const notifications = () => run(async () => {
    const runtimeGranted = Platform.OS === 'android' && (Number(Platform.Version) < 33 ||
      await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS));
    if (runtimeGranted) await Linking.openSettings();
    else if (!(await requestNotificationPermission())) setLocalError('ההתראות עדיין לא אושרו. הפעילו אותן בהגדרות האפליקציה וחזרו לכאן.');
  });
  const finish = () => run(async () => {
    await completeOnboarding();
    navigation.reset({ index: 0, routes: [{ name: 'Home' }] });
  }, false);
  const manual = () => Alert.alert('ממשיכים עם הקלטה ידנית',
    'המצב האוטומטי יכובה. אפשר להקליט מהמיקרופון בלחיצה; הקלטת שיחת WhatsApp ידנית דורשת גם חיבור פעיל לטלפון.', [
      { text: 'חזרה להגדרה', style: 'cancel' },
      { text: 'להמשיך ידנית', onPress: () => { void run(async () => {
        await completeManualOnboarding();
        navigation.reset({ index: 0, routes: [{ name: 'Home' }] });
      }); } },
    ]);
  const currentIndex = step === 'done' ? 5 : Math.max(0, steps.indexOf(step));

  return <View style={styles.screen}>
    <ScrollView contentContainerStyle={[styles.content, { paddingTop: insets.top + 22, paddingBottom: insets.bottom + 28 }]} keyboardShouldPersistTaps="handled">
      <View style={styles.heading}><WaveMark size={40} /><View style={styles.headingCopy}>
        <Text style={styles.eyebrow}>{completed ? 'השלמת ההגדרות' : 'ברוכים הבאים למקליט השיחות'}</Text>
        <Text style={styles.title}>נכין את הטלפון, צעד אחד בכל פעם.</Text>
      </View></View>
      {showProgress && <View style={styles.progress} accessibilityLabel={step === 'done' ? 'ההגדרה הושלמה' : `שלב ${currentIndex + 1} מתוך 5`}>
        {steps.map((item, index) => <View key={item} style={[styles.progressDot, index <= currentIndex && styles.progressActive]} />)}
      </View>}
      {showProgress && step !== 'done' && <Text style={styles.counter}>שלב {currentIndex + 1} מתוך 5</Text>}
      <Text style={styles.stepTitle} accessibilityRole="header">{titles[step]}</Text>
      {step === 'loading' && <ActivityIndicator size="large" color={colors.green} style={styles.spinner} />}
      {step === 'checking' && <View style={styles.card}><Text style={styles.body}>בדיקת המקליט לא הושלמה. נבדוק שוב את המצב בפועל לפני סיום ההגדרה.</Text></View>}
      {step === 'microphone' && <View style={styles.card}>
        <Text style={styles.body}>{Platform.OS === 'android'
          ? 'ההרשאה מאפשרת לשמור את קול המיקרופון בהקלטה. במסך הבא של Android בחרו ״בזמן השימוש באפליקציה״ או ״אפשר״.'
          : 'הגרסה הזו מיועדת ל־Android. הקלטה באפליקציה אינה זמינה כאן באייפון.'}</Text>
        <TouchableOpacity style={[styles.primary, disabled && styles.disabled]} onPress={() => { void microphone(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{microphoneBlocked ? 'פתיחת הרשאת המיקרופון' : 'לאפשר מיקרופון'}</Text></TouchableOpacity>
        {!microphoneBlocked && <TouchableOpacity style={styles.linkButton} onPress={() => { void openAppSettings(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>ההרשאה לא מופיעה? פתיחת הגדרות האפליקציה</Text></TouchableOpacity>}
      </View>}
      {step === 'notifications' && <View style={styles.card}>
        <Text style={styles.body}>ההתראה מציגה כשהמקליט פעיל ונותנת דרך לעצור אותו. היא גם מאפשרת להזין את קוד החיבור בלי לסגור את הגדרות הטלפון.</Text>
        <Text style={styles.body}>אשרו התראות למקליט. אם נפתח דף ההגדרות, הפעילו גם את ערוץ ״הקלטת שיחות״ ואז חזרו לאפליקציה.</Text>
        <TouchableOpacity style={[styles.primary, disabled && styles.disabled]} onPress={() => { void notifications(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>לאפשר התראות</Text></TouchableOpacity>
        <TouchableOpacity style={styles.linkButton} onPress={() => { void openAppSettings(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>פתיחת הגדרות ההתראות של האפליקציה</Text></TouchableOpacity>
      </View>}
      {step === 'activation' && <SystemSetupWizard focused accessOverride={systemAccessStatus} onStatusChanged={refresh} />}
      {step === 'call_access' && <View style={styles.card}>
        <Text style={styles.body}>Android מבקש אישור נוסף כדי שהמקליט יוכל לקבל סימנים על מצב שיחת WhatsApp או WhatsApp Business.</Text>
        <Text style={styles.body}>במסך שייפתח בחרו ״זיהוי שיחות WhatsApp״ של מקליט השיחות, הפעילו את הגישה ואשרו. חזרו לכאן כדי שנבדוק שהמערכת התחברה.</Text>
        <Text style={styles.small}>המקליט משתמש במצב השיחה. שמות, מספרים ותוכן הודעות אינם נשמרים כחלק מהזיהוי.</Text>
        <TouchableOpacity style={[styles.primary, disabled && styles.disabled]} onPress={() => { void run(openNotificationAccessSetup); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>פתיחת אישור זיהוי השיחות</Text></TouchableOpacity>
      </View>}
      {step === 'automatic' && <View style={styles.card}>
        <Text style={styles.body}>הפעלת המצב האוטומטי מכינה את המקליט להקלטה כשמזוהה שיחה. בזמן ההמתנה לא נשמר קול.</Text>
        <Text style={styles.body}>יש לבצע שיחת בדיקה כדי לוודא שהזיהוי ושני הקולות עובדים במכשיר שלך.</Text>
        {firstAutomaticOptIn && <TouchableOpacity style={styles.consent} onPress={() => setConsent(value => !value)} accessibilityRole="checkbox" accessibilityState={{ checked: consent }} disabled={disabled}>
          <View style={[styles.checkbox, consent && styles.checkboxChecked]}><Text style={styles.checkmark}>{consent ? '✓' : ''}</Text></View>
          <Text style={styles.consentText}>אשתמש בהקלטה בידיעת המשתתפים.</Text>
        </TouchableOpacity>}
        <TouchableOpacity style={[styles.primary, ((firstAutomaticOptIn && !consent) || disabled) && styles.disabled]} onPress={() => { if (AppState.currentState === 'active') void run(enableAutoRecording); }} disabled={(firstAutomaticOptIn && !consent) || disabled} accessibilityRole="button"><Text style={styles.primaryText}>{firstAutomaticOptIn ? 'הפעלת המצב האוטומטי' : 'להפעיל מחדש'}</Text></TouchableOpacity>
      </View>}
      {step === 'done' && <View style={styles.card}>
        <Text style={styles.body}>המיקרופון, ההתראות, חיבור ההקלטה והמצב האוטומטי אומתו כפעילים כעת. אפשר לעבור למסך ההקלטה ולבצע שיחת בדיקה.</Text>
        <TouchableOpacity style={[styles.primary, navigationDisabled && styles.disabled]} onPress={() => { void finish(); }} disabled={navigationDisabled} accessibilityRole="button"><Text style={styles.primaryText}>למסך ההקלטה</Text></TouchableOpacity>
      </View>}
      {(localError || setupError) && <Text style={styles.error} accessibilityLiveRegion="polite">{localError || setupError}</Text>}
      {working && <ActivityIndicator color={colors.green} style={styles.spinner} />}
      {step !== 'loading' && step !== 'done' && <TouchableOpacity style={styles.linkButton} onPress={() => { void run(refresh); }} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>בדיקה נוספת של ההגדרות</Text></TouchableOpacity>}
      {manualAvailable && <TouchableOpacity style={[styles.secondary, disabled && styles.disabled]} onPress={manual} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>להמשיך עם הקלטה ידנית</Text></TouchableOpacity>}
      {completed && <TouchableOpacity style={styles.linkButton} onPress={() => { if (navigation.canGoBack()) navigation.goBack(); else navigation.navigate('Home'); }} accessibilityRole="button"><Text style={styles.link}>חזרה לאפליקציה</Text></TouchableOpacity>}
      <Text style={styles.footer}>בכל חזרה מהגדרות Android נבדוק מה אושר, ונציג רק את מה שעוד חסר.</Text>
    </ScrollView>
  </View>;
}

const styles = StyleSheet.create({
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
