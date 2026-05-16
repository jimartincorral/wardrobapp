import { Tabs } from 'expo-router';
import { Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from '@/src/i18n';
import { useTheme } from '@/src/theme';

function TabIcon({ name, focused, color }: { name: string; focused: boolean; color: string }) {
  const icons: Record<string, string> = {
    Home: '\u2302',
    Wardrobe: focused ? '\u26AB' : '\u26AA',
    Outfits: focused ? '\u2666' : '\u2662',
    Analytics: '\u2603',
  };
  return (
    <Text style={{ fontSize: 22, color }}>
      {icons[name] || '\u25CF'}
    </Text>
  );
}

export default function TabLayout() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const bottomInset = Math.max(insets.bottom, 8);

  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: colors.textTertiary,
        tabBarStyle: {
          backgroundColor: colors.surface,
          borderTopWidth: 1,
          borderTopColor: colors.border,
          paddingTop: 6,
          paddingBottom: bottomInset,
          height: 56 + bottomInset,
        },
        tabBarLabelStyle: { fontSize: 12, fontWeight: '600' },
        headerStyle: { backgroundColor: colors.background },
        headerTintColor: colors.text,
        headerTitleStyle: { fontWeight: '700' },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: t('tabs.home'),
          tabBarIcon: ({ focused, color }) => <TabIcon name="Home" focused={focused} color={color} />,
        }}
      />
      <Tabs.Screen
        name="wardrobe"
        options={{
          title: t('tabs.wardrobe'),
          tabBarIcon: ({ focused, color }) => <TabIcon name="Wardrobe" focused={focused} color={color} />,
        }}
      />
      <Tabs.Screen
        name="outfits"
        options={{
          title: t('tabs.outfits'),
          tabBarIcon: ({ focused, color }) => <TabIcon name="Outfits" focused={focused} color={color} />,
        }}
      />
      <Tabs.Screen
        name="analytics"
        options={{
          title: t('tabs.analytics'),
          tabBarIcon: ({ focused, color }) => <TabIcon name="Analytics" focused={focused} color={color} />,
        }}
      />
    </Tabs>
  );
}
