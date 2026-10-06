import React from 'react';
import { DefaultTheme, NavigationContainer } from '@react-navigation/native';
import { createStackNavigator } from '@react-navigation/stack';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import HomeScreen from '../screens/HomeScreen';
import RecordingsScreen from '../screens/RecordingsScreen';
import PlaybackScreen from '../screens/PlaybackScreen';
import SettingsScreen from '../screens/SettingsScreen';
import { colors } from '../theme';
import SetupScreen from '../screens/SetupScreen';
import { useSetup } from '../setup/SetupContext';

export type RootStackParamList = {
  Home: undefined;
  Recordings: undefined;
  Playback: { recordingId: string };
  Settings: undefined;
  Setup: undefined;
};

const Stack = createStackNavigator<RootStackParamList>();

export default function AppNavigator() {
  const { loading, completed } = useSetup();
  if (loading) return <View style={styles.loading}><ActivityIndicator color={colors.green} /><Text style={styles.loadingText}>בודקים את הגדרות המקליט…</Text></View>;
  return (
    <NavigationContainer theme={{ ...DefaultTheme, colors: { ...DefaultTheme.colors, primary: colors.green, background: colors.background, card: colors.background, text: colors.ink, border: colors.border, notification: colors.danger } }}>
      <Stack.Navigator
        initialRouteName={completed ? 'Home' : 'Setup'}
        screenOptions={{
          headerStyle: { backgroundColor: colors.background, elevation: 0, shadowOpacity: 0, borderBottomWidth: 0 },
          headerTintColor: colors.ink,
          headerTitleStyle: { fontWeight: '700', fontSize: 17 },
          headerTitleAlign: 'center',
          headerBackTitle: 'חזרה',
        }}>
        <Stack.Screen name="Home" component={HomeScreen} options={{ title: 'מקליט שיחות', headerShown: false }} />
        <Stack.Screen name="Recordings" component={RecordingsScreen} options={{ title: 'ההקלטות שלי', headerShown: false }} />
        <Stack.Screen name="Playback" component={PlaybackScreen} options={{ title: 'האזנה' }} />
        <Stack.Screen name="Settings" component={SettingsScreen} options={{ title: 'הגדרות', headerShown: false }} />
        <Stack.Screen name="Setup" component={SetupScreen} options={{ title: 'הגדרה מונחית', headerShown: false }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  loading: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: colors.background, gap: 16 },
  loadingText: { color: colors.muted, fontSize: 14, writingDirection: 'rtl', textAlign: 'center' },
});
