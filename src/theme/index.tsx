import { Platform, useColorScheme } from 'react-native';
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Colors } from '../constants/theme';

const THEME_STORAGE_KEY = 'wardrobapp_theme_mode';

export type ThemeMode = 'system' | 'light' | 'dark';
export type ThemeName = 'light' | 'dark';
export type ThemeColors = typeof Colors.light;

interface ThemeContextValue {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  theme: ThemeName;
  isDark: boolean;
  colors: ThemeColors;
}

const ThemeContext = createContext<ThemeContextValue>({
  mode: 'system',
  setMode: () => {},
  theme: 'light',
  isDark: false,
  colors: Colors.light,
});

async function loadSavedThemeMode(): Promise<ThemeMode | null> {
  try {
    if (Platform.OS === 'web') {
      const value = typeof localStorage !== 'undefined' ? localStorage.getItem(THEME_STORAGE_KEY) : null;
      return value === 'light' || value === 'dark' || value === 'system' ? value : null;
    }
    const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
    const value = await AsyncStorage.getItem(THEME_STORAGE_KEY);
    return value === 'light' || value === 'dark' || value === 'system' ? value : null;
  } catch {
    return null;
  }
}

async function saveThemeMode(mode: ThemeMode): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      if (typeof localStorage !== 'undefined') localStorage.setItem(THEME_STORAGE_KEY, mode);
      return;
    }
    const AsyncStorage = (await import('@react-native-async-storage/async-storage')).default;
    await AsyncStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {}
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const systemScheme = useColorScheme();
  const [mode, setModeState] = useState<ThemeMode>('system');

  useEffect(() => {
    loadSavedThemeMode().then((savedMode) => {
      if (savedMode) setModeState(savedMode);
    });
  }, []);

  const theme: ThemeName = mode === 'system' ? (systemScheme === 'dark' ? 'dark' : 'light') : mode;
  const colors = theme === 'dark' ? Colors.dark : Colors.light;

  const setMode = (nextMode: ThemeMode) => {
    setModeState(nextMode);
    saveThemeMode(nextMode);
  };

  const value = useMemo(
    () => ({ mode, setMode, theme, isDark: theme === 'dark', colors }),
    [mode, theme, colors]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
