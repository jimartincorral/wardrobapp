import React, { useCallback } from 'react';
import { View, Text, ScrollView, StyleSheet, Pressable } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { useAnalytics } from '@/src/hooks/useAnalytics';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useTranslation } from '@/src/i18n';
import { localizeGarmentLabel } from '@/src/utils/localization-helpers';
import type { Garment } from '@/src/types';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function AnalyticsScreen() {
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const analytics = useAnalytics();
  const showEmptyWardrobeNudge = analytics.totalItems === 0;

  useFocusEffect(useCallback(() => {
    analytics.refresh();
  }, [analytics.refresh]));

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.summaryRow}>
        <View style={styles.summaryCard}>
          <Text style={styles.summaryNumber}>{analytics.totalItems}</Text>
          <Text style={styles.summaryLabel}>{t('analytics.totalItems')}</Text>
        </View>
        <View style={styles.summaryCard}>
          <Text style={styles.summaryNumber}>{analytics.archivedItems}</Text>
          <Text style={styles.summaryLabel}>{t('analytics.archivedItems')}</Text>
        </View>
      </View>

      {showEmptyWardrobeNudge && (
        <View style={styles.nudgeCard}>
          <Text style={styles.nudgeTitle}>{t('analytics.getStartedTitle')}</Text>
          <Text style={styles.nudgeText}>{t('analytics.getStartedBody')}</Text>
          <View style={styles.nudgeActionsRow}>
            <Pressable style={styles.nudgeButton} onPress={() => router.push('/garment/add')}>
              <Text style={styles.nudgeButtonText}>{t('home.addButton')}</Text>
            </Pressable>
            <Pressable style={styles.secondaryButton} onPress={() => router.push('/wardrobe')}>
              <Text style={styles.secondaryButtonText}>{t('tabs.wardrobe')}</Text>
            </Pressable>
          </View>
        </View>
      )}

      <Text style={styles.sectionTitle}>{t('analytics.categoryBreakdown')}</Text>
      <View style={styles.chartContainer}>
        {analytics.categoryDistribution.map(cat => {
          const pct = (cat.count / (analytics.totalItems || 1)) * 100;
          return (
            <View key={cat.category} style={styles.barRow}>
              <Text style={styles.barLabel}>{t(`categories.${cat.category}`)}</Text>
              <View style={styles.barTrack}>
                <View style={[styles.barFill, { width: `${pct}%` }]} />
              </View>
              <Text style={styles.barValue}>{cat.count}</Text>
            </View>
          );
        })}
        {analytics.categoryDistribution.length === 0 && (
          <Text style={styles.emptyText}>{t('analytics.emptyBreakdown')}</Text>
        )}
      </View>

      <Text style={styles.sectionTitle}>{t('analytics.lifespanTitle')}</Text>
      <View style={styles.chartContainer}>
        {analytics.garmentLifespan.slice(0, 3).map((entry: { garment: Garment; days: number }) => (
          <View key={entry.garment.id} style={styles.barRow}>
            <Text style={styles.barLabel}>{localizeGarmentLabel(entry.garment.category, entry.garment.subcategories, t)}</Text>
            <View style={styles.barTrack}>
              <View style={[styles.barFill, { width: `${Math.min(100, (entry.days / 365) * 100)}%` }]} />
            </View>
            <Text style={styles.barValue}>{entry.days}d</Text>
          </View>
        ))}
        {analytics.garmentLifespan.length === 0 && <Text style={styles.emptyText}>{t('analytics.emptyLifespan')}</Text>}
      </View>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg, paddingBottom: Spacing.xxl },
  summaryRow: { flexDirection: 'row', gap: Spacing.sm, marginBottom: Spacing.xl },
  summaryCard: { flex: 1, backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center' },
  summaryNumber: { fontSize: FontSize.xl, fontWeight: '800', color: colors.primary },
  summaryLabel: { fontSize: FontSize.xs, color: colors.textSecondary, marginTop: 2 },
  sectionTitle: { fontSize: FontSize.lg, fontWeight: '700', color: colors.text, marginTop: Spacing.lg, marginBottom: Spacing.md },
  chartContainer: { backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md },
  barRow: { flexDirection: 'row', alignItems: 'center', marginBottom: Spacing.sm },
  barLabel: { width: 90, fontSize: FontSize.sm, color: colors.text },
  barTrack: { flex: 1, height: 20, backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.sm, overflow: 'hidden', marginHorizontal: Spacing.sm },
  barFill: { height: '100%', backgroundColor: colors.primary, borderRadius: BorderRadius.sm },
  barValue: { width: 30, fontSize: FontSize.sm, fontWeight: '600', color: colors.textSecondary, textAlign: 'right' },
  emptyText: { textAlign: 'center', color: colors.textTertiary, fontSize: FontSize.sm, padding: Spacing.md },
  nudgeCard: { marginBottom: Spacing.lg, backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md },
  nudgeTitle: { fontSize: FontSize.md, fontWeight: '700', color: colors.text },
  nudgeText: { fontSize: FontSize.sm, color: colors.textSecondary, marginTop: Spacing.xs },
  nudgeActionsRow: { flexDirection: 'row', gap: Spacing.sm, marginTop: Spacing.md },
  nudgeButton: { flex: 1, backgroundColor: colors.primary, borderRadius: BorderRadius.sm, paddingVertical: Spacing.sm, alignItems: 'center' },
  nudgeButtonText: { color: '#fff', fontSize: FontSize.sm, fontWeight: '700' },
  secondaryButton: { flex: 1, borderWidth: 1, borderColor: colors.border, borderRadius: BorderRadius.sm, paddingVertical: Spacing.sm, alignItems: 'center' },
  secondaryButtonText: { color: colors.text, fontSize: FontSize.sm, fontWeight: '600' },
});
