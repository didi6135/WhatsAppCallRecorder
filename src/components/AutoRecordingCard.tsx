import React, { useState } from 'react';
import { Alert, StyleSheet, Switch, Text, TouchableOpacity, View } from 'react-native';
import { useRecording } from '../context/RecordingContext';
import { colors, ui } from '../theme';

export default function AutoRecordingCard({ compact = false, onShowDetails }: { compact?: boolean; onShowDetails?: () => void }) {
  const { autoRecordingStatus: status, isBusy, enableAutoRecording, disableAutoRecording, openNotificationAccessSetup } = useRecording();
  const [localError, setLocalError] = useState<string | null>(null);
  const ready = status.armed && status.listenerConnected && status.helperConnected;
  const monitoring = ready && status.state !== 'paused' && status.state !== 'error';
  const label = status.state === 'error' ? 'נדרשת בדיקה'
    : status.state === 'paused' ? 'הזיהוי מושהה כרגע'
      : ready && status.state === 'recording' ? 'שיחה מזוהה · מקליטים'
        : ready ? 'מוכן לשיחה הבאה'
          : !status.notificationAccessGranted ? 'נשאר לאפשר זיהוי שיחות'
            : !status.listenerConnected ? 'ממתינים לחיבור זיהוי השיחות'
            : status.enabled ? 'נדרשת הפעלה מחדש' : 'טרם הופעל';
  const run = async (operation: () => Promise<void>) => {
    setLocalError(null);
    try { await operation(); }
    catch (failure) { setLocalError(failure instanceof Error ? failure.message : 'הפעולה לא הושלמה. נסו שוב.'); }
  };
  const enable = () => {
    if (status.enabled) { void run(enableAutoRecording); return; }
    Alert.alert('הפעלת הקלטה אוטומטית', 'כשהמצב פעיל, שיחות WhatsApp ו־WhatsApp Business מזוהות יוקלטו אוטומטית. בזמן ההמתנה לשיחה לא נשמר קול. יש ליידע את משתתפי השיחה על ההקלטה.', [
      { text: 'ביטול', style: 'cancel' },
      { text: 'הפעלה בידיעת המשתתפים', onPress: () => { void run(enableAutoRecording); } },
    ]);
  };

  if (compact) return <TouchableOpacity style={styles.compact} onPress={onShowDetails} accessibilityRole="button" accessibilityLabel={`הקלטה אוטומטית. ${label}. פתיחת הגדרות`}>
    <View style={styles.compactCopy}><Text style={styles.title}>הקלטה אוטומטית</Text><Text style={styles.caption}>{label}</Text></View>
    <View style={[styles.badge, monitoring && styles.readyBadge]}><Text style={[styles.badgeText, monitoring && styles.readyBadgeText]}>{monitoring ? status.state === 'recording' ? 'מקליטה' : 'פעילה' : 'להגדרה'}</Text></View>
    <Text style={styles.chevron}>‹</Text>
  </TouchableOpacity>;

  return <View style={styles.card}>
    <View style={styles.heading}><Text style={styles.title}>הקלטה אוטומטית</Text><Switch value={status.enabled} onValueChange={next => { if (next) enable(); else void run(disableAutoRecording); }} disabled={isBusy || (!status.notificationAccessGranted && !status.enabled)} trackColor={{ false: colors.border, true: colors.green }} thumbColor={colors.surface} accessibilityLabel="הקלטת שיחות WhatsApp ו־WhatsApp Business באופן אוטומטי" accessibilityState={{ disabled: isBusy || (!status.notificationAccessGranted && !status.enabled) }} /></View>
    <Text style={[styles.status, ready && styles.readyStatus]} accessibilityLiveRegion="polite">{label}</Text>
    <Text style={styles.body}>שיחות נכנסות ויוצאות שזוהו ב־WhatsApp וב־WhatsApp Business מוקלטות ונשמרות בטלפון.</Text>
    <Text style={styles.note}>הזיהוי עשוי לקחת רגע; ייתכן שתחילת השיחה לא תיכלל בהקלטה.</Text>
    {!status.notificationAccessGranted && <View style={styles.access}>
      <Text style={styles.step}>1. מאפשרים זיהוי שיחות</Text>
      <Text style={styles.body}>Android יבקש גישה להתראות. האפליקציה משתמשת בה לזיהוי שיחות של WhatsApp ו־WhatsApp Business בלבד.</Text>
      <TouchableOpacity style={[styles.button, isBusy && ui.disabled]} onPress={() => { void run(openNotificationAccessSetup); }} disabled={isBusy} accessibilityRole="button"><Text style={styles.buttonText}>פתיחת הרשאת זיהוי השיחות</Text></TouchableOpacity>
      <Text style={styles.note}>אחרי האישור חוזרים לכאן ומפעילים את המתג.</Text>
    </View>}
    {status.notificationAccessGranted && !ready && <TouchableOpacity style={[styles.button, isBusy && ui.disabled]} onPress={enable} disabled={isBusy} accessibilityRole="button"><Text style={styles.buttonText}>{status.enabled ? 'הפעלה מחדש' : 'הפעלת הקלטה אוטומטית'}</Text></TouchableOpacity>}
    {ready && <Text style={styles.note}>ממתינים לשיחה בלי להקליט. אפשר לכבות את המצב בכל רגע. אחרי אתחול או עצירת הרכיב נדרשת הפעלה מחדש.</Text>}
    {(localError || status.error) && <Text style={styles.warning} accessibilityLiveRegion="polite">{localError || status.error}</Text>}
  </View>;
}

const styles = StyleSheet.create({
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
