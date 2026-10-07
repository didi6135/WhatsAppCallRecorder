import { en, he, TranslationKey } from './catalog';

export type Language = 'he' | 'en';
export const LANGUAGE_KEY = 'waRecoLanguage';
export const languageFromLocale = (locale: unknown): Language =>
  typeof locale === 'string' && /^(he|iw)([-_]|$)/i.test(locale) ? 'he' : 'en';
export const validLanguage = (value: unknown): value is Language => value === 'he' || value === 'en';
let currentLanguage: Language = 'en';
export const getLanguage = (): Language => currentLanguage;
export const activateLanguage = (language: Language): void => { currentLanguage = language; };
export const localeFor = (language = getLanguage()): string => language === 'he' ? 'he-IL' : 'en-US';

export function t(key: TranslationKey, values: Record<string, string | number> = {}, language = getLanguage()): string {
  return (language === 'he' ? he[key] : en[key]).replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : placeholder);
}

// Cached app-owned errors remain translatable after a language change. Unknown native errors are never displayed raw.
const exactKeys = new Map<string, TranslationKey>();
for (const key of Object.keys(he) as TranslationKey[]) { exactKeys.set(he[key], key); exactKeys.set(en[key], key); }
export function localizeText(text: string): string { const key = exactKeys.get(text); return key ? t(key) : text; }
export function displayRecordingTitle(title: string): string {
  for (const key of ['nativeCallTitle', 'nativeMicrophoneTitle', 'copy198'] as const)
    if (title === he[key] || title === en[key]) return t(key);
  return title;
}
export function localizedError(failure: unknown, fallback: TranslationKey = 'copy009'): string {
  const message = typeof failure === 'string' ? failure : failure && typeof failure === 'object' && 'message' in failure && typeof failure.message === 'string' ? failure.message : null;
  const key = message && exactKeys.get(message);
  return key ? t(key) : t(fallback);
}

export function formatDate(value: string | number | Date, language = getLanguage()): string {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString(localeFor(language)) : t('unknownDate', {}, language);
}
export function formatDuration(ms: number, language = getLanguage()): string {
  const total = Math.floor(Math.max(0, Number.isFinite(ms) ? ms : 0) / 1000);
  const number = new Intl.NumberFormat(localeFor(language), { minimumIntegerDigits: 2, useGrouping: false });
  return [Math.floor(total / 3600), Math.floor(total / 60) % 60, total % 60].map(value => number.format(value)).join(':');
}

// Styles start in the approved Hebrew layout. Numeric inputs/time/email keep their explicit LTR direction.
export function directionalStyles<T extends Record<string, object>>(styles: T, language: Language): T {
  if (language === 'he') return styles;
  return Object.fromEntries(Object.entries(styles).map(([key, original]) => {
    const style = { ...original } as Record<string, unknown>;
    if (style.flexDirection === 'row-reverse') style.flexDirection = 'row';
    if (style.writingDirection === 'rtl') {
      style.writingDirection = 'ltr';
      if (style.textAlign === 'left') style.textAlign = 'right';
    }
    if ((original as Record<string, unknown>).textAlign === 'right' && (original as Record<string, unknown>).writingDirection !== 'ltr') style.textAlign = 'left';
    if (style.alignSelf === 'flex-end') style.alignSelf = 'flex-start';
    return [key, style];
  })) as T;
}

export interface LanguageSnapshot { language: Language; ready: boolean; error: 'languageSaveFailed' | 'languageSyncFailed' | null; }
interface LanguageStorage { getItem(key: string): Promise<string | null>; setItem(key: string, value: string): Promise<void>; }

// This controller owns only language persistence. It never restarts providers, changes permissions or controls audio.
export function createLanguageController(storage: LanguageStorage, deviceLocale: unknown, syncNative: (language: Language) => Promise<void>) {
  let snapshot: LanguageSnapshot = { language: languageFromLocale(deviceLocale), ready: false, error: null };
  let revision = 0;
  let writes = Promise.resolve();
  const listeners = new Set<(value: LanguageSnapshot) => void>();
  const publish = (value: LanguageSnapshot) => { snapshot = value; activateLanguage(value.language); listeners.forEach(listener => listener(value)); };
  activateLanguage(snapshot.language);
  const sync = async (language: Language, requestedAt: number) => {
    try { await syncNative(language); }
    catch { if (requestedAt === revision) publish({ ...snapshot, error: 'languageSyncFailed' }); }
  };
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: (value: LanguageSnapshot) => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    cancelPending() { revision += 1; },
    async initialize() {
      const requestedAt = ++revision;
      let language = languageFromLocale(deviceLocale); let error: LanguageSnapshot['error'] = null;
      try { const saved = await storage.getItem(LANGUAGE_KEY); if (validLanguage(saved)) language = saved; }
      catch { error = 'languageSaveFailed'; }
      if (requestedAt !== revision) return;
      publish({ language, ready: true, error });
      await sync(language, requestedAt);
    },
    async select(language: Language) {
      if (!validLanguage(language)) return;
      const requestedAt = ++revision;
      const write = writes.then(() => storage.setItem(LANGUAGE_KEY, language));
      writes = write.catch(() => undefined);
      try { await write; }
      catch { if (requestedAt === revision) publish({ ...snapshot, error: 'languageSaveFailed' }); return; }
      if (requestedAt !== revision) return;
      publish({ language, ready: true, error: null });
      await sync(language, requestedAt);
    },
  };
}
