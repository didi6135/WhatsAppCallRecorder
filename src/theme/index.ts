import { StyleSheet } from 'react-native';

export const colors = {
  background: '#F6F8F6', surface: '#FFFFFF', ink: '#102A2E', muted: '#617578',
  mint: '#C2EDDE', mintSoft: '#EAF7F1', green: '#21634F', border: '#DFE7E3',
  danger: '#B44440', dangerSoft: '#FFF0EE', amber: '#84581F', amberSoft: '#FFF6E8',
};

export const ui = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  content: { paddingHorizontal: 22, paddingTop: 18, paddingBottom: 28 },
  card: { backgroundColor: colors.surface, borderRadius: 24, padding: 22, borderWidth: 1, borderColor: colors.border },
  title: { color: colors.ink, fontSize: 22, fontWeight: '700', textAlign: 'right', writingDirection: 'rtl', lineHeight: 32 },
  subtitle: { color: colors.muted, fontSize: 14, textAlign: 'right', writingDirection: 'rtl', lineHeight: 23 },
  body: { color: colors.muted, fontSize: 15, textAlign: 'right', writingDirection: 'rtl', lineHeight: 25 },
  label: { color: colors.ink, fontSize: 16, fontWeight: '600', textAlign: 'right', writingDirection: 'rtl', lineHeight: 24 },
  primaryButton: { minHeight: 54, backgroundColor: colors.ink, borderRadius: 16, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 18, paddingVertical: 14 },
  primaryText: { fontSize: 16, fontWeight: '700', color: colors.surface, textAlign: 'center', writingDirection: 'rtl' },
  secondaryButton: { minHeight: 52, borderRadius: 16, borderWidth: 1, borderColor: colors.border, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16, paddingVertical: 13 },
  secondaryText: { fontSize: 15, fontWeight: '600', color: colors.ink, textAlign: 'center', writingDirection: 'rtl' },
  warning: { color: colors.amber, backgroundColor: colors.amberSoft, borderRadius: 16, padding: 14, fontSize: 14, lineHeight: 23, textAlign: 'right', writingDirection: 'rtl' },
  disabled: { opacity: 0.4 },
});
