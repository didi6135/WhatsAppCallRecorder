import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { I18nManager, NativeModules } from 'react-native';
import { createLanguageController, directionalStyles, Language, LanguageSnapshot, t } from './core';

const deviceLocale = () => {
  const app = NativeModules.AppLanguage;
  const settings = NativeModules.SettingsManager?.settings;
  return app?.getConstants?.().deviceLocale || app?.deviceLocale ||
    I18nManager.getConstants().localeIdentifier || settings?.AppleLanguages?.[0] || settings?.AppleLocale || 'en';
};
const syncNative = async (language: Language) => { if (NativeModules.AppLanguage?.setLanguage) await NativeModules.AppLanguage.setLanguage(language); };

interface LocalizationValue extends LanguageSnapshot { selectLanguage(language: Language): Promise<void>; }
const LocalizationContext = createContext<LocalizationValue | null>(null);

export function LocalizationProvider({ children }: { children: React.ReactNode }) {
  const controllerRef = useRef<ReturnType<typeof createLanguageController> | null>(null);
  if (!controllerRef.current) controllerRef.current = createLanguageController(AsyncStorage, deviceLocale(), syncNative);
  const controller = controllerRef.current;
  const [snapshot, setSnapshot] = useState(controller.getSnapshot());
  useEffect(() => {
    const unsubscribe = controller.subscribe(setSnapshot);
    void controller.initialize();
    return () => { unsubscribe(); controller.cancelPending(); };
  }, [controller]);
  const value = useMemo(() => ({ ...snapshot, selectLanguage: controller.select }), [snapshot, controller]);
  return <LocalizationContext.Provider value={value}>{children}</LocalizationContext.Provider>;
}
export function useLocalization() {
  const context = useContext(LocalizationContext);
  if (!context) throw new Error('useLocalization requires LocalizationProvider');
  return { ...context, isRTL: context.language === 'he', t };
}
export function useLocalizedStyles<T extends Record<string, object>>(styles: T): T {
  const { language } = useLocalization();
  return useMemo(() => directionalStyles(styles, language), [styles, language]);
}
