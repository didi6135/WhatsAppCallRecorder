import { t, useLocalizedStyles } from '../i18n';
import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { StackNavigationProp } from '@react-navigation/stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { colors } from '../theme';

// Code-native shapes avoid a launcher/font dependency and remain crisp at any size.
export function WaveMark({ size = 42, dark = false }: { size?: number; dark?: boolean }) {
  const styles = useLocalizedStyles(baseStyles);
  return <View style={[styles.mark, { width: size, height: size, borderRadius: size * 0.28, backgroundColor: colors.ink }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
    {[0.14, 0.28].map((height, index) => <View key={`left${index}`} style={{ height: size * height, width: size * 0.06, borderRadius: size, backgroundColor: colors.mint }} />)}
    <View style={{ width: size * 0.2, height: size * 0.2, borderRadius: size, backgroundColor: colors.mint, alignItems: 'center', justifyContent: 'center', marginHorizontal: size * 0.025 }}><View style={{ width: size * 0.1, height: size * 0.1, borderRadius: size, backgroundColor: '#F08D70' }} /></View>
    {[0.28, 0.14].map((height, index) => <View key={`right${index}`} style={{ height: size * height, width: size * 0.06, borderRadius: size, backgroundColor: colors.mint }} />)}
  </View>;
}

export function Waveform({ active = false, level = 0 }: { active?: boolean; level?: number }) {
  const styles = useLocalizedStyles(baseStyles);
  const normalized = Math.max(0, Math.min(1, Number.isFinite(level) ? level : 0));
  const heights = [10, 20, 13, 30, 18, 42, 24, 54, 34, 23, 43, 17, 32, 52, 22, 36, 13, 25, 17, 9];
  return <View style={styles.waveform} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
    {heights.map((height, index) => <View key={index} style={[styles.waveBar, { height: active ? Math.max(5, height * normalized) : height, backgroundColor: active ? colors.green : index > 5 && index < 15 ? colors.mint : colors.border }]} />)}
  </View>;
}

export function SectionHeading({ title, action, onPress }: { title: string; action?: string; onPress?: () => void }) {
  const styles = useLocalizedStyles(baseStyles);
  return <View style={styles.sectionHeading}><Text style={styles.sectionTitle}>{title}</Text>{action && onPress && <TouchableOpacity onPress={onPress} style={styles.sectionAction} accessibilityRole="button"><Text style={styles.link}>{action}</Text></TouchableOpacity>}</View>;
}

function NavIcon({ kind, selected }: { kind: 'Home' | 'Recordings' | 'Settings'; selected: boolean }) {
  const styles = useLocalizedStyles(baseStyles);
  const color = selected ? colors.ink : colors.muted;
  if (kind === 'Home') return <View style={[styles.navHome, { borderColor: color }]}><View style={[styles.navHomeDot, { backgroundColor: color }]} /></View>;
  if (kind === 'Recordings') return <View style={styles.navLibrary}>{[12, 20, 15, 8].map((height, index) => <View key={index} style={{ width: 3, height, borderRadius: 2, backgroundColor: color }} />)}</View>;
  return <View style={styles.navSettings}>{[0, 1, 2].map(index => <View key={index} style={[styles.navSettingsLine, { backgroundColor: color }]}><View style={[styles.navSettingsKnob, { left: index === 1 ? 3 : 12, borderColor: color }]} /></View>)}</View>;
}

export function BottomNav({ current }: { current: 'Home' | 'Recordings' | 'Settings' }) {
  const styles = useLocalizedStyles(baseStyles);
  const navigation = useNavigation<StackNavigationProp<RootStackParamList>>();
  const insets = useSafeAreaInsets();
  const items = [{ key: 'Home', title: t('copy193') }, { key: 'Recordings', title: t('copy194') }, { key: 'Settings', title: t('copy195') }] as const;
  return <View style={[styles.nav, { paddingBottom: Math.max(10, insets.bottom) }]}>{items.map(item => <TouchableOpacity key={item.key} style={styles.navItem} onPress={() => { if (item.key !== current) navigation.navigate(item.key); }} accessibilityRole="tab" accessibilityState={{ selected: current === item.key }} accessibilityLabel={item.title}>
    <View style={[styles.navIconContainer, current === item.key && styles.navIconSelected]}><NavIcon kind={item.key} selected={current === item.key} /></View><Text style={[styles.navText, current === item.key && styles.navTextSelected]}>{item.title}</Text>
  </TouchableOpacity>)}</View>;
}

const baseStyles = StyleSheet.create({
  mark: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 3 },
  waveform: { height: 60, flexDirection: 'row', gap: 5, alignItems: 'center', justifyContent: 'center' },
  waveBar: { width: 5, borderRadius: 5 },
  sectionHeading: { flexDirection: 'row-reverse', alignItems: 'center', justifyContent: 'space-between', marginTop: 26, marginBottom: 10 },
  sectionTitle: { color: colors.ink, fontSize: 18, fontWeight: '700', textAlign: 'right', writingDirection: 'rtl' },
  sectionAction: { minHeight: 48, justifyContent: 'center', paddingHorizontal: 4 },
  link: { color: colors.green, fontSize: 14, fontWeight: '600', writingDirection: 'rtl' },
  nav: { flexDirection: 'row-reverse', paddingTop: 10, backgroundColor: colors.surface, borderTopWidth: 1, borderTopColor: colors.border },
  navItem: { flex: 1, minHeight: 58, alignItems: 'center', justifyContent: 'center', gap: 4 },
  navIconContainer: { width: 58, height: 30, alignItems: 'center', justifyContent: 'center', borderRadius: 16 },
  navIconSelected: { backgroundColor: colors.mint },
  navText: { fontSize: 12, color: colors.muted, writingDirection: 'rtl' },
  navTextSelected: { color: colors.ink, fontWeight: '700' },
  navHome: { width: 21, height: 21, borderRadius: 7, borderWidth: 1.8, alignItems: 'center', justifyContent: 'center' },
  navHomeDot: { height: 6, width: 6, borderRadius: 4 },
  navLibrary: { flexDirection: 'row', gap: 3, height: 22, alignItems: 'center' },
  navSettings: { width: 23, gap: 5, height: 22, justifyContent: 'center' },
  navSettingsLine: { height: 1.8, width: 23 },
  navSettingsKnob: { position: 'absolute', top: -2, width: 6, height: 6, borderRadius: 3, borderWidth: 1.5, backgroundColor: colors.surface },
});
