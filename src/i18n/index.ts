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
const CURRENCY_STORAGE_KEY = 'wardrobapp_currency';

const SUPPORTED_CURRENCIES = ['USD', 'EUR', 'GBP', 'MXN'] as const;
export type CurrencyCode = typeof SUPPORTED_CURRENCIES[number];

function isCurrencyCode(value: string | null): value is CurrencyCode {
  return !!value && SUPPORTED_CURRENCIES.includes(value as CurrencyCode);
}

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

async function loadSavedCurrency(): Promise<CurrencyCode | null> {
  try {
    if (Platform.OS === 'web') {
      const value = typeof localStorage !== 'undefined' ? localStorage.getItem(CURRENCY_STORAGE_KEY) : null;
      return isCurrencyCode(value) ? value : null;
    }
    const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
    const value = await AsyncStorage.getItem(CURRENCY_STORAGE_KEY);
    return isCurrencyCode(value) ? value : null;
  } catch {
    return null;
  }
}

async function saveCurrency(currency: CurrencyCode): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      if (typeof localStorage !== 'undefined') localStorage.setItem(CURRENCY_STORAGE_KEY, currency);
    } else {
      const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
      await AsyncStorage.setItem(CURRENCY_STORAGE_KEY, currency);
    }
  } catch {}
}

function getLocaleTag(language: string): string {
  return language === 'es' ? 'es-ES' : 'en-US';
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
  currency: CurrencyCode;
  setCurrency: (currency: CurrencyCode) => void;
  formatCurrency: (value: number, minimumFractionDigits?: number, maximumFractionDigits?: number) => string;
  t: (key: string, options?: Record<string, any>) => string;
}

const LanguageContext = createContext<LanguageContextValue>({
  language: 'en',
  setLanguage: () => {},
  currency: 'USD',
  setCurrency: () => {},
  formatCurrency: (value) => `$${value.toFixed(2)}`,
  t,
});

// ── Hook ──────────────────────────────────────────────────────
export function useTranslation(): LanguageContextValue {
  return useContext(LanguageContext);
}

// ── Provider ──────────────────────────────────────────────────
export function LanguageProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<string>('en');
  const [currency, setCurrencyState] = useState<CurrencyCode>('USD');

  useEffect(() => {
    Promise.all([loadSavedLanguage(), loadSavedCurrency()]).then(([savedLanguage, savedCurrency]) => {
      const lang = savedLanguage ?? getSupportedLocale(getDeviceLocale());
      i18n.locale = lang;
      setLanguageState(lang);
      setCurrencyState(savedCurrency ?? 'USD');
    });
  }, []);

  const setLanguage = (lang: string) => {
    i18n.locale = lang;
    setLanguageState(lang);
    saveLanguage(lang);
  };

  const setCurrency = (nextCurrency: CurrencyCode) => {
    setCurrencyState(nextCurrency);
    saveCurrency(nextCurrency);
  };

  const formatCurrency = (
    value: number,
    minimumFractionDigits = 2,
    maximumFractionDigits = 2
  ): string => {
    try {
      return new Intl.NumberFormat(getLocaleTag(language), {
        style: 'currency',
        currency,
        minimumFractionDigits,
        maximumFractionDigits,
      }).format(value);
    } catch {
      return `${currency} ${value.toFixed(maximumFractionDigits)}`;
    }
  };

  // Reactive `t` bound to current language
  const translate = (key: string, options?: Record<string, any>): string =>
    i18n.t(key, options);

  return createElement(
    LanguageContext.Provider,
    { value: { language, setLanguage, currency, setCurrency, formatCurrency, t: translate } },
    children
  );
}
