import React from 'react';
import { TouchableOpacity, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme';

interface RecordButtonProps {
  isRecording: boolean;
  onPress: () => void;
  disabled?: boolean;
  size?: number;
}

export default function RecordButton({ isRecording, onPress, disabled = false }: RecordButtonProps) {
  return (
    <TouchableOpacity
      style={[styles.button, isRecording && styles.active, disabled && styles.disabled]}
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={isRecording ? 'עצירה ושמירת הקלטה' : 'התחלת הקלטה'}
      accessibilityState={{ disabled }}
      activeOpacity={0.75}>
      <View style={isRecording ? styles.stop : styles.record} />
      <Text style={styles.label}>{isRecording ? 'עצירה ושמירה' : 'התחלת הקלטה'}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  button: { minHeight: 60, width: '100%', backgroundColor: colors.ink, borderRadius: 18, alignItems: 'center', justifyContent: 'center', flexDirection: 'row-reverse', gap: 12, paddingHorizontal: 20, paddingVertical: 16 },
  active: { backgroundColor: colors.danger },
  disabled: { opacity: 0.4 },
  record: { backgroundColor: colors.mint, width: 14, height: 14, borderRadius: 7 },
  stop: { backgroundColor: colors.surface, width: 14, height: 14, borderRadius: 3 },
  label: { color: colors.surface, fontSize: 17, fontWeight: '700', textAlign: 'center', writingDirection: 'rtl' },
});
