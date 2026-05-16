import { Stack, useRouter } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import * as Linking from 'expo-linking';
import { useEffect, useRef } from 'react';
import { LanguageProvider, useTranslation } from '@/src/i18n';
import { useTheme, ThemeProvider } from '@/src/theme';

function AppStack() {
  const { t } = useTranslation();
  const { colors, isDark } = useTheme();
  const router = useRouter();
  const lastHandledImportRef = useRef<string | null>(null);

  useEffect(() => {
    const routeImportUrl = (incomingUrl: string | null) => {
      if (!incomingUrl) return;

      let parsed: ReturnType<typeof Linking.parse>;
      try {
        parsed = Linking.parse(incomingUrl);
      } catch {
        return;
      }

      const importUrl = typeof parsed.queryParams?.importUrl === 'string'
        ? parsed.queryParams.importUrl
        : null;

      if (!importUrl || lastHandledImportRef.current === importUrl) {
        return;
      }

      lastHandledImportRef.current = importUrl;
      router.replace({ pathname: '/garment/add', params: { importUrl } });
    };

    void Linking.getInitialURL().then(routeImportUrl).catch(() => {});

    const subscription = Linking.addEventListener('url', ({ url }) => {
      routeImportUrl(url);
    });

    return () => {
      subscription.remove();
    };
  }, [router]);

  return (
    <>
      <StatusBar style={isDark ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.background },
          headerTintColor: colors.text,
          headerTitleStyle: { fontWeight: '600' },
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
      <Stack.Screen
        name="garment/add"
        options={{ title: t('screens.addGarment'), presentation: 'modal' }}
      />
      <Stack.Screen
        name="garment/[id]"
        options={{ title: t('screens.garmentDetails') }}
      />
      <Stack.Screen
        name="garment/edit/[id]"
        options={{ title: t('screens.editGarment') }}
      />
      <Stack.Screen
        name="outfit/[id]"
        options={{ title: t('screens.outfitDetails') }}
      />
      <Stack.Screen
        name="settings"
        options={{ title: t('screens.settings') }}
      />
      </Stack>
    </>
  );
}

export default function RootLayout() {
  return (
    <ThemeProvider>
      <LanguageProvider>
        <AppStack />
      </LanguageProvider>
    </ThemeProvider>
  );
}
