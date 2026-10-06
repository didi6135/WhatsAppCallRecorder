import React from 'react';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, View } from 'react-native';
import { RecordingProvider } from './src/context/RecordingContext';
import AppNavigator from './src/navigation/AppNavigator';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { colors } from './src/theme';
import { SetupProvider } from './src/setup/SetupContext';

export default function App() {
  return (
    <GestureHandlerRootView style={styles.container}>
    <SafeAreaProvider>
    <RecordingProvider>
    <SetupProvider>
      <View style={styles.container}>
        <AppNavigator />
        <StatusBar style="dark" backgroundColor={colors.background} />
      </View>
    </SetupProvider>
    </RecordingProvider>
    </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({ 
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
});
