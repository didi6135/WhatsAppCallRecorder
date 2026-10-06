import React, { useCallback, useState } from 'react';
import { View, Text, FlatList, StyleSheet, TouchableOpacity, Alert } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { StackNavigationProp } from '@react-navigation/stack';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { useRecording } from '../context/RecordingContext';
import type { Recording } from '../context/RecordingContext';
import { BottomNav, WaveMark } from '../components/Visuals';
import { colors, ui } from '../theme';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const messageOf = (error: unknown) => error instanceof Error ? error.message : 'הפעולה לא הושלמה.';

const qualityWarning = (recording: Recording): string | null => {
  if (recording.captureSource === 'usb' && recording.status === 'captured') {
    const outputMissing = (recording.outputSoundMs ?? 0) < 200;
    const micMissing = (recording.microphoneSoundMs ?? 0) < 200;
    if (outputMissing && micMissing) return 'לא זוהה מספיק קול בשני הערוצים. בדקו בהאזנה.';
    if (outputMissing) return 'לא זוהה מספיק קול מהשיחה. בדקו בהאזנה.';
    if (micMissing) return 'לא זוהה מספיק קול מהמיקרופון. בדקו בהאזנה.';
  }
  switch (recording.status) {
    case 'captured': return recording.wasSilenced ? 'המיקרופון הושתק בחלק מההקלטה.' : null;
    case 'silent': return 'קובץ שקט או קול חלש. בדקו בהאזנה.';
    case 'interrupted': return 'ההקלטה הופרעה. ייתכן שחסר קול.';
    case 'recovered': return 'הקובץ שוחזר לאחר הפסקה. בדקו בהאזנה.';
    default: return null;
  }
};

export default function RecordingsScreen() {
  const insets = useSafeAreaInsets();
  const navigation = useNavigation<StackNavigationProp<RootStackParamList, 'Recordings'>>();
  const { recordings, deleteRecording, shareRecording, loadRecordings, isRecording, isBusy } = useRecording();
  const [isRefreshing, setIsRefreshing] = useState(false);

  useFocusEffect(useCallback(() => {
    void loadRecordings().catch(failure => Alert.alert('לא ניתן לטעון הקלטות', messageOf(failure)));
  }, [loadRecordings]));

  const refresh = async () => {
    setIsRefreshing(true);
    try { await loadRecordings(); }
    catch (failure) { Alert.alert('לא ניתן לטעון הקלטות', messageOf(failure)); }
    finally { setIsRefreshing(false); }
  };

  const confirmDelete = (item: Recording) => {
    if (isRecording || isBusy) return;
    Alert.alert('מחיקת הקלטה', `למחוק את "${item.title}" ואת קובץ הקול מהמכשיר?`, [
      { text: 'ביטול', style: 'cancel' },
      { text: 'מחיקה', style: 'destructive', onPress: () => {
        void deleteRecording(item.id).catch(failure => Alert.alert('המחיקה לא הושלמה', messageOf(failure)));
      } },
    ]);
  };

  const share = async (item: Recording) => {
    if (isBusy || item.source === 'legacy') return;
    try { await shareRecording(item.id); }
    catch (failure) { Alert.alert('השיתוף לא הושלם', messageOf(failure)); }
  };

  const renderItem = ({ item }: { item: Recording }) => {
    const warning = qualityWarning(item);
    return (
      <View style={styles.item}>
        <TouchableOpacity style={styles.info} onPress={() => navigation.navigate('Playback', { recordingId: item.id })} accessibilityRole="button" accessibilityLabel={`פרטי הקלטה ${item.title}`}>
          <Text style={styles.title}>{item.title}</Text>
          <Text style={styles.date}>{new Date(item.date).toLocaleString('he-IL')}</Text>
          <Text style={styles.details}>{item.duration} · {item.source === 'legacy' ? 'הקלטה ישנה' : item.captureSource === 'usb' ? 'שיחת WhatsApp' : 'מיקרופון'}</Text>
          {warning && <Text style={styles.warning}>{warning}</Text>}
        </TouchableOpacity>
        <View style={styles.actions}>
          <TouchableOpacity style={styles.action} onPress={() => navigation.navigate('Playback', { recordingId: item.id })} accessibilityRole="button" accessibilityLabel={`האזנה להקלטה ${item.title}`}><Text style={styles.playText}>האזנה</Text></TouchableOpacity>
          {item.source === 'native' ? <>
            <TouchableOpacity style={[styles.action, isBusy && ui.disabled]} onPress={() => { void share(item); }} disabled={isBusy} accessibilityRole="button" accessibilityLabel={`שיתוף ${item.title}`} accessibilityState={{ disabled: isBusy }}><Text style={styles.shareText}>שיתוף</Text></TouchableOpacity>
            <TouchableOpacity style={[styles.action, (isRecording || isBusy) && ui.disabled]} onPress={() => confirmDelete(item)} disabled={isRecording || isBusy} accessibilityRole="button" accessibilityLabel={`מחיקת ${item.title}`} accessibilityState={{ disabled: isRecording || isBusy }}>
              <Text style={styles.deleteText}>מחיקה</Text>
            </TouchableOpacity>
          </> : <Text style={styles.legacy}>להאזנה בלבד</Text>}
        </View>
      </View>
    );
  };

  return (
    <View style={styles.container}>
      <FlatList
        data={recordings}
        renderItem={renderItem}
        keyExtractor={item => item.id}
        contentContainerStyle={[styles.list, { paddingTop: Math.max(18, insets.top + 10) }, recordings.length === 0 && styles.emptyList]}
        refreshing={isRefreshing}
        onRefresh={() => { void refresh(); }}
        ListHeaderComponent={<View style={styles.listHeader}><View style={styles.header}><WaveMark size={38} /><Text style={styles.listHeadline}>ההקלטות שלי</Text></View>{recordings.length > 0 && <Text style={styles.listCaption}>{recordings.length} הקלטות במכשיר</Text>}{isRecording && <Text style={styles.notice}>הקלטה פעילה. אפשר למחוק קבצים לאחר העצירה.</Text>}</View>}
        ListEmptyComponent={<View style={styles.empty}><Text style={styles.emptyTitle}>אין עדיין הקלטות</Text><Text style={styles.emptyText}>לאחר עצירה ושמירה, ההקלטה תופיע כאן.</Text><TouchableOpacity style={styles.emptyButton} onPress={() => navigation.navigate('Home')} accessibilityRole="button"><Text style={styles.emptyButtonText}>להקלטה</Text></TouchableOpacity></View>}
      />
      <BottomNav current="Recordings" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: ui.screen,
  list: { paddingHorizontal: 22, paddingBottom: 30 },
  listHeader: { marginBottom: 22 },
  header: { flexDirection: 'row-reverse', alignItems: 'center', gap: 12 },
  listHeadline: { ...ui.title, flex: 1, fontSize: 26, lineHeight: 36 },
  listCaption: { ...ui.subtitle, marginTop: 14 },
  emptyList: { flexGrow: 1 },
  item: { ...ui.card, padding: 18, marginBottom: 14 },
  info: { minHeight: 48 },
  title: { ...ui.label, marginBottom: 5 },
  date: ui.subtitle,
  details: { ...ui.subtitle, marginTop: 4 },
  warning: { ...ui.subtitle, color: colors.amber, marginTop: 10 },
  actions: { flexDirection: 'row-reverse', gap: 8, paddingTop: 12, borderTopWidth: 1, borderTopColor: colors.border, marginTop: 14 },
  action: { flex: 1, minHeight: 48, paddingHorizontal: 8, paddingVertical: 12, justifyContent: 'center', alignItems: 'center', borderRadius: 12, backgroundColor: colors.background },
  playText: { fontWeight: '700', color: colors.ink, fontSize: 14, textAlign: 'center', writingDirection: 'rtl' },
  shareText: { fontWeight: '600', color: colors.green, fontSize: 14, textAlign: 'center', writingDirection: 'rtl' },
  deleteText: { color: colors.danger, fontSize: 14, textAlign: 'center', writingDirection: 'rtl' },
  legacy: { ...ui.subtitle, flex: 1, alignSelf: 'center', textAlign: 'center' },
  notice: { ...ui.warning, marginTop: 16 },
  empty: { flex: 1, justifyContent: 'center', alignItems: 'center', paddingVertical: 40 },
  emptyTitle: { ...ui.title, textAlign: 'center' },
  emptyText: { ...ui.body, textAlign: 'center', marginTop: 10 },
  emptyButton: { ...ui.primaryButton, marginTop: 28, minWidth: 190 },
  emptyButtonText: ui.primaryText,
});
