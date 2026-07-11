import React, { useCallback, useState } from 'react';
import { View, Text, ScrollView, StyleSheet, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import {
  getCategoryDistribution,
  getColorDistribution,
  getBrandDistribution,
  getSubcategoryDistribution,
} from '@/src/services/analytics-service';
import { getGarmentCount } from '@/src/services/garment-service';
import { GARMENT_COLORS } from '@/src/constants/colors';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useTranslation } from '@/src/i18n';
import { localizeSubcategory } from '@/src/utils/localization-helpers';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

type Stat = { category: string; count: number };

const HEX_TO_COLOR = new Map<string, { key: string; hex: string }>(
  GARMENT_COLORS.map(c => [c.hex, { key: c.key, hex: c.hex }])
);

interface StatRowData {
  key: string;
  label: string;
  count: number;
  swatch?: string; // hex, or 'multi' for rainbow
}

export default function StatisticsScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);

  const [totalItems, setTotalItems] = useState(0);
  const [categories, setCategories] = useState<Stat[]>([]);
  const [colorStats, setColorStats] = useState<Stat[]>([]);
  const [brands, setBrands] = useState<Stat[]>([]);
  const [subcategories, setSubcategories] = useState<Record<string, Stat[]>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [brandSort, setBrandSort] = useState<'count' | 'alpha'>('count');

  const load = useCallback(async () => {
    try {
      const [total, cats, cols, brs, subs] = await Promise.all([
        getGarmentCount(),
        getCategoryDistribution(),
        getColorDistribution(),
        getBrandDistribution(),
        getSubcategoryDistribution(),
      ]);
      setTotalItems(total);
      setCategories(cats);
      setColorStats(cols);
      setBrands(brs);
      setSubcategories(subs);
    } catch (e) {
      console.error('Failed to load statistics:', e);
    }
  }, []);

  useFocusEffect(useCallback(() => { load(); }, [load]));

  const toggleCategory = useCallback((key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const categoryRows: StatRowData[] = categories.map(c => ({
    key: c.category,
    label: t(`categories.${c.category}`),
    count: c.count,
  }));

  const subRowsByCategory: Record<string, StatRowData[]> = {};
  for (const [category, subs] of Object.entries(subcategories)) {
    subRowsByCategory[category] = subs.map(s => ({
      key: `${category}:${s.category}`,
      label: s.category === '__none__'
        ? t('statistics.noSubcategory')
        : (localizeSubcategory(s.category, t) ?? s.category),
      count: s.count,
    }));
  }

  const colorRows: StatRowData[] = colorStats.map(c => {
    const match = HEX_TO_COLOR.get(c.category);
    return {
      key: c.category,
      label: match ? t(`colors.${match.key}`) : c.category,
      count: c.count,
      swatch: match?.key === 'multi' ? 'multi' : (match?.hex ?? c.category),
    };
  });

  const brandRows: StatRowData[] = brands.map(b => ({
    key: b.category,
    label: b.category,
    count: b.count,
  }));

  const sortedBrandRows: StatRowData[] = brandSort === 'alpha'
    ? [...brandRows].sort((a, b) => a.label.localeCompare(b.label))
    : brandRows;

  const isEmpty = totalItems === 0;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.subtitle}>{t('statistics.subtitle')}</Text>

      <View style={styles.summaryRow}>
        <SummaryTile styles={styles} value={totalItems} label={t('statistics.totalItems')} />
        <SummaryTile styles={styles} value={categories.length} label={t('statistics.distinctCategories')} />
      </View>
      <View style={styles.summaryRow}>
        <SummaryTile styles={styles} value={colorStats.length} label={t('statistics.distinctColors')} />
        <SummaryTile styles={styles} value={brands.length} label={t('statistics.distinctBrands')} />
      </View>

      {isEmpty ? (
        <Text style={styles.emptyText}>{t('statistics.empty')}</Text>
      ) : (
        <>
          <StatSection
            title={t('statistics.byCategory')}
            hint={t('statistics.tapToExpand')}
            rows={categoryRows}
            styles={styles}
            colors={colors}
            expandedKeys={expanded}
            onToggle={toggleCategory}
            subRows={subRowsByCategory}
          />
          <StatSection title={t('statistics.byColor')} rows={colorRows} styles={styles} colors={colors} />
          {brandRows.length > 0 && (
            <StatSection
              title={t('statistics.byBrand')}
              rows={sortedBrandRows}
              styles={styles}
              colors={colors}
              headerAction={
                <SortToggle
                  mode={brandSort}
                  onChange={setBrandSort}
                  styles={styles}
                  countLabel={t('statistics.sortByCount')}
                  alphaLabel={t('statistics.sortByName')}
                />
              }
            />
          )}
        </>
      )}
    </ScrollView>
  );
}

function SummaryTile({ styles, value, label }: { styles: any; value: number; label: string }) {
  return (
    <View style={styles.summaryCard}>
      <Text style={styles.summaryNumber}>{value}</Text>
      <Text style={styles.summaryLabel}>{label}</Text>
    </View>
  );
}

function StatSection({
  title, hint, rows, styles, colors, expandedKeys, onToggle, subRows, headerAction,
}: {
  title: string;
  hint?: string;
  rows: StatRowData[];
  styles: any;
  colors: ThemeColors;
  expandedKeys?: Set<string>;
  onToggle?: (key: string) => void;
  subRows?: Record<string, StatRowData[]>;
  headerAction?: React.ReactNode;
}) {
  if (rows.length === 0) return null;
  const max = Math.max(...rows.map(r => r.count), 1);
  const expandable = !!onToggle;

  return (
    <View>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>{title}</Text>
        {headerAction ?? null}
      </View>
      {hint ? <Text style={styles.sectionHint}>{hint}</Text> : null}
      <View style={styles.chartContainer}>
        {rows.map(row => {
          const pct = (row.count / max) * 100;
          const isExpanded = expandedKeys?.has(row.key) ?? false;
          const children = subRows?.[row.key] ?? [];
          const bar = (
            <View style={styles.barRow}>
              <View style={styles.labelWrap}>
                {row.swatch ? <ColorSwatch swatch={row.swatch} colors={colors} /> : null}
                <Text style={styles.barLabel} numberOfLines={1}>{row.label}</Text>
              </View>
              <View style={styles.barTrack}>
                <View style={[styles.barFill, { width: `${pct}%` }]} />
              </View>
              <Text style={styles.barValue}>{row.count}</Text>
              {expandable ? (
                <Text style={styles.chevron}>{isExpanded ? '▾' : '▸'}</Text>
              ) : null}
            </View>
          );

          return (
            <View key={row.key}>
              {expandable ? (
                <Pressable
                  onPress={() => onToggle?.(row.key)}
                  android_ripple={{ color: colors.surfaceVariant }}
                  accessibilityRole="button"
                  accessibilityState={{ expanded: isExpanded }}
                >
                  {bar}
                </Pressable>
              ) : bar}

              {expandable && isExpanded ? (
                <SubcategoryBars rows={children} styles={styles} colors={colors} />
              ) : null}
            </View>
          );
        })}
      </View>
    </View>
  );
}

function SortToggle({
  mode, onChange, styles, countLabel, alphaLabel,
}: {
  mode: 'count' | 'alpha';
  onChange: (mode: 'count' | 'alpha') => void;
  styles: any;
  countLabel: string;
  alphaLabel: string;
}) {
  return (
    <View style={styles.sortToggle}>
      <Pressable
        onPress={() => onChange('count')}
        style={[styles.sortOption, mode === 'count' ? styles.sortOptionActive : null]}
        accessibilityRole="button"
        accessibilityState={{ selected: mode === 'count' }}
      >
        <Text style={[styles.sortOptionText, mode === 'count' ? styles.sortOptionTextActive : null]}>
          {countLabel}
        </Text>
      </Pressable>
      <Pressable
        onPress={() => onChange('alpha')}
        style={[styles.sortOption, mode === 'alpha' ? styles.sortOptionActive : null]}
        accessibilityRole="button"
        accessibilityState={{ selected: mode === 'alpha' }}
      >
        <Text style={[styles.sortOptionText, mode === 'alpha' ? styles.sortOptionTextActive : null]}>
          {alphaLabel}
        </Text>
      </Pressable>
    </View>
  );
}

function SubcategoryBars({
  rows, styles, colors,
}: { rows: StatRowData[]; styles: any; colors: ThemeColors }) {
  if (rows.length === 0) {
    return (
      <View style={styles.subGroup}>
        <Text style={styles.subEmpty}>—</Text>
      </View>
    );
  }
  const subMax = Math.max(...rows.map(r => r.count), 1);
  return (
    <View style={styles.subGroup}>
      {rows.map(row => {
        const pct = (row.count / subMax) * 100;
        return (
          <View key={row.key} style={styles.barRow}>
            <Text style={styles.subLabel} numberOfLines={1}>{row.label}</Text>
            <View style={styles.barTrack}>
              <View style={[styles.barFill, styles.subFill, { width: `${pct}%` }]} />
            </View>
            <Text style={styles.barValue}>{row.count}</Text>
          </View>
        );
      })}
    </View>
  );
}

function ColorSwatch({ swatch, colors }: { swatch: string; colors: ThemeColors }) {
  if (swatch === 'multi') {
    return (
      <View style={[swatchBase, { borderColor: colors.border, overflow: 'hidden', flexDirection: 'row', flexWrap: 'wrap' }]}>
        {['#FF0000', '#FFD700', '#0066CC', '#228B22'].map(c => (
          <View key={c} style={{ width: '50%', height: '50%', backgroundColor: c }} />
        ))}
      </View>
    );
  }
  return <View style={[swatchBase, { backgroundColor: swatch, borderColor: colors.border }]} />;
}

const swatchBase = {
  width: 16,
  height: 16,
  borderRadius: 8,
  borderWidth: 1,
  marginRight: Spacing.xs,
} as const;

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg, paddingBottom: Spacing.xxl },
  subtitle: { fontSize: FontSize.sm, color: colors.textSecondary, marginBottom: Spacing.lg },
  summaryRow: { flexDirection: 'row', gap: Spacing.sm, marginBottom: Spacing.sm },
  summaryCard: { flex: 1, backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center' },
  summaryNumber: { fontSize: FontSize.xl, fontWeight: '800', color: colors.primary },
  summaryLabel: { fontSize: FontSize.xs, color: colors.textSecondary, marginTop: 2, textAlign: 'center' },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: Spacing.lg, marginBottom: Spacing.xs },
  sectionTitle: { fontSize: FontSize.lg, fontWeight: '700', color: colors.text },
  sectionHint: { fontSize: FontSize.xs, color: colors.textTertiary, marginBottom: Spacing.md },
  sortToggle: { flexDirection: 'row', backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.full, padding: 2 },
  sortOption: { paddingVertical: 4, paddingHorizontal: Spacing.sm, borderRadius: BorderRadius.full },
  sortOptionActive: { backgroundColor: colors.primary },
  sortOptionText: { fontSize: FontSize.xs, fontWeight: '600', color: colors.textSecondary },
  sortOptionTextActive: { color: '#fff' },
  chartContainer: { backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md },
  barRow: { flexDirection: 'row', alignItems: 'center', marginBottom: Spacing.sm },
  labelWrap: { width: 110, flexDirection: 'row', alignItems: 'center' },
  barLabel: { flex: 1, fontSize: FontSize.sm, color: colors.text },
  barTrack: { flex: 1, height: 20, backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.sm, overflow: 'hidden', marginHorizontal: Spacing.sm },
  barFill: { height: '100%', backgroundColor: colors.primary, borderRadius: BorderRadius.sm },
  barValue: { width: 30, fontSize: FontSize.sm, fontWeight: '600', color: colors.textSecondary, textAlign: 'right' },
  chevron: { width: 18, fontSize: FontSize.sm, color: colors.textTertiary, textAlign: 'right' },
  subGroup: { paddingLeft: Spacing.md, marginBottom: Spacing.sm, borderLeftWidth: 2, borderLeftColor: colors.surfaceVariant },
  subLabel: { width: 94, fontSize: FontSize.xs, color: colors.textSecondary },
  subFill: { backgroundColor: colors.primaryLight },
  subEmpty: { fontSize: FontSize.xs, color: colors.textTertiary },
  emptyText: { textAlign: 'center', color: colors.textTertiary, fontSize: FontSize.sm, padding: Spacing.xl },
});
