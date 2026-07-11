import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, ScrollView, Pressable } from 'react-native';
import { useRouter } from 'expo-router';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { getGarmentCount, getUnavailableGarmentCount } from '@/src/services/garment-service';
import { useTranslation } from '@/src/i18n';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function HomeScreen() {
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [stats, setStats] = useState({ totalItems: 0, archivedItems: 0 });

  useEffect(() => { loadStats(); }, []);

  const loadStats = async () => {
    try {
      const [totalItems, archivedItems] = await Promise.all([
        getGarmentCount(), getUnavailableGarmentCount(),
      ]);
      setStats({ totalItems, archivedItems });
    } catch (e) { console.error(e); }
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.greeting}>{t('home.title')}</Text>
      <Text style={styles.subtitle}>{t('home.subtitle')}</Text>
      <View style={styles.statsGrid}>
        <View style={styles.statCard}>
          <Text style={styles.statNumber}>{stats.totalItems}</Text>
          <Text style={styles.statLabel}>{t('home.stats.items')}</Text>
        </View>
        <View style={styles.statCard}>
          <Text style={styles.statNumber}>{stats.archivedItems}</Text>
          <Text style={styles.statLabel}>{t('home.stats.archived')}</Text>
        </View>
      </View>
      <Pressable style={styles.addButton} onPress={() => router.push('/garment/add')}>
        <Text style={styles.addButtonText}>{t('home.addButton')}</Text>
      </Pressable>
      <View style={styles.quickActions}>
        <Pressable style={styles.actionCard} onPress={() => router.push('/(tabs)/outfits')}>
          <Text style={styles.actionIcon}>{'\u2666'}</Text>
          <Text style={styles.actionTitle}>{t('home.actions.outfitIdeas')}</Text>
          <Text style={styles.actionDesc}>{t('home.actions.outfitDesc')}</Text>
        </Pressable>
        <Pressable style={styles.actionCard} onPress={() => router.push('/(tabs)/analytics')}>
          <Text style={styles.actionIcon}>{'\u2603'}</Text>
          <Text style={styles.actionTitle}>{t('home.actions.analytics')}</Text>
          <Text style={styles.actionDesc}>{t('home.actions.analyticsDesc')}</Text>
        </Pressable>
        <Pressable style={styles.actionCard} onPress={() => router.push('/statistics')}>
          <Text style={styles.actionIcon}>{'∑'}</Text>
          <Text style={styles.actionTitle}>{t('home.actions.statistics')}</Text>
          <Text style={styles.actionDesc}>{t('home.actions.statisticsDesc')}</Text>
        </Pressable>
        <Pressable style={styles.actionCard} onPress={() => router.push('/settings')}>
          <Text style={styles.actionIcon}>{'\u2699'}</Text>
          <Text style={styles.actionTitle}>{t('screens.settings')}</Text>
          <Text style={styles.actionDesc}>{t('settings.languageTitle')}</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg },
  greeting: { fontSize: FontSize.title, fontWeight: '800', color: colors.text, marginTop: Spacing.md },
  subtitle: { fontSize: FontSize.md, color: colors.textSecondary, marginTop: Spacing.xs, marginBottom: Spacing.xl },
  statsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.sm, marginBottom: Spacing.xl },
  statCard: {
    flex: 1, minWidth: '45%', backgroundColor: colors.surface,
    borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center',
    elevation: 1, shadowColor: colors.shadow, shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1, shadowRadius: 2,
  },
  statNumber: { fontSize: FontSize.xxl, fontWeight: '800', color: colors.primary },
  statLabel: { fontSize: FontSize.xs, color: colors.textSecondary, marginTop: Spacing.xs },
  addButton: { backgroundColor: colors.primary, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center', marginBottom: Spacing.xl },
  addButtonText: { color: '#fff', fontSize: FontSize.lg, fontWeight: '700' },
  quickActions: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.md },
  actionCard: {
    minWidth: '45%',
    flex: 1,
    backgroundColor: colors.surface, borderRadius: BorderRadius.md,
    padding: Spacing.md, alignItems: 'center', elevation: 1,
    shadowColor: colors.shadow, shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1, shadowRadius: 2,
  },
  actionIcon: { fontSize: 32, marginBottom: Spacing.xs },
  actionTitle: { fontSize: FontSize.sm, fontWeight: '700', color: colors.text, textAlign: 'center' },
  actionDesc: { fontSize: FontSize.xs, color: colors.textSecondary, marginTop: 2, textAlign: 'center' },
});
