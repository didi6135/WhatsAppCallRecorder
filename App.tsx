import React from 'react';
import { StatusBar } from 'expo-status-bar';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { RecordingProvider } from './src/context/RecordingContext';
import AppNavigator from './src/navigation/AppNavigator';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { colors } from './src/theme';
import { SetupProvider } from './src/setup/SetupContext';
import { LocalizationProvider, t, useLocalization } from './src/i18n';

function LocalizedApp() {
  const { ready } = useLocalization();
  if (!ready) return <View style={styles.loading}><ActivityIndicator color={colors.green} /><Text style={styles.loadingText}>{t('copy215')}</Text></View>;
  return <RecordingProvider><SetupProvider><View style={styles.container}>
    <AppNavigator />
  </View></SetupProvider></RecordingProvider>;
}

export default function App() {
  return (
    <GestureHandlerRootView style={styles.container}>
    <SafeAreaProvider>
    <StatusBar style="dark" backgroundColor={colors.background} />
    <LocalizationProvider><LocalizedApp /></LocalizationProvider>
    </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({ 
  container: {
    flex: 1,
    backgroundColor: colors.background,
    direction: 'ltr',
  },
  loading: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.background, gap: 16 },
  loadingText: { color: colors.muted, fontSize: 14, textAlign: 'center' },
});
