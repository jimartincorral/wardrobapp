import { I18n } from 'i18n-js';
import { Platform } from 'react-native';
import { createContext, useContext, useState, useEffect, createElement } from 'react';
import type { ReactNode } from 'react';
import en from './en';
import es from './es';

// ── i18n-js instance ─────────────────────────────────────────
const i18n = new I18n({ en, es });
i18n.enableFallback = true;
i18n.defaultLocale = 'en';
i18n.locale = 'en';

const STORAGE_KEY = 'wardrobapp_lang';

function getSupportedLocale(locale: string): string {
  return locale.slice(0, 2).toLowerCase() === 'es' ? 'es' : 'en';
}

// ── Storage helpers ───────────────────────────────────────────
async function loadSavedLanguage(): Promise<string | null> {
  try {
    if (Platform.OS === 'web') {
      return typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null;
    }
    const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
    return AsyncStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

async function saveLanguage(lang: string): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, lang);
    } else {
      const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
      await AsyncStorage.setItem(STORAGE_KEY, lang);
    }
  } catch {}
}

function getDeviceLocale(): string {
  try {
    const { getLocales } = require('expo-localization');
    return getLocales()?.[0]?.languageCode ?? 'en';
  } catch {
    return 'en';
  }
}

// ── Non-reactive t() for use outside components ───────────────
export function t(key: string, options?: Record<string, any>): string {
  return i18n.t(key, options);
}

// ── Context ───────────────────────────────────────────────────
interface LanguageContextValue {
  language: string;
  setLanguage: (lang: string) => void;
  t: (key: string, options?: Record<string, any>) => string;
}

const LanguageContext = createContext<LanguageContextValue>({
  language: 'en',
  setLanguage: () => {},
  t,
});

// ── Hook ──────────────────────────────────────────────────────
export function useTranslation(): LanguageContextValue {
  return useContext(LanguageContext);
}

// ── Provider ──────────────────────────────────────────────────
export function LanguageProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<string>('en');

  useEffect(() => {
    loadSavedLanguage().then((savedLanguage) => {
      const lang = savedLanguage ?? getSupportedLocale(getDeviceLocale());
      i18n.locale = lang;
      setLanguageState(lang);
    });
  }, []);

  const setLanguage = (lang: string) => {
    i18n.locale = lang;
    setLanguageState(lang);
    saveLanguage(lang);
  };

  // Reactive `t` bound to current language
  const translate = (key: string, options?: Record<string, any>): string =>
    i18n.t(key, options);

  return createElement(
    LanguageContext.Provider,
    { value: { language, setLanguage, t: translate } },
    children
  );
}
