import React, { useState, useCallback, useRef } from 'react';
import { View, Text, FlatList, Pressable, TextInput, StyleSheet, ScrollView } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { GarmentCard } from '@/src/components/GarmentCard';
import { useGarments, type GarmentSortOption } from '@/src/hooks/useGarments';
import { useDebouncedValue } from '@/src/hooks/useDebouncedValue';
import { CATEGORIES } from '@/src/constants/categories';
import { GARMENT_COLORS } from '@/src/constants/colors';
import { OCCASION_OPTIONS, SEASON_OPTIONS } from '@/src/constants/style-filters';
import type { OccasionOption, SeasonOption } from '@/src/constants/style-filters';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useTranslation } from '@/src/i18n';
import { localizeSubcategory } from '@/src/utils/localization-helpers';
import type { Garment } from '@/src/types';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function WardrobeScreen() {
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [selectedCategory, setSelectedCategory] = useState<string | undefined>();
  const [selectedSubcategory, setSelectedSubcategory] = useState<string | undefined>();
  const [search, setSearch] = useState('');
  const [filtersExpanded, setFiltersExpanded] = useState(true);
  const [season, setSeason] = useState<SeasonOption | undefined>();
  const [occasion, setOccasion] = useState<OccasionOption | undefined>();
  const [brand, setBrand] = useState('');
  const [size, setSize] = useState('');
  const [color, setColor] = useState<string | undefined>();
  const [sort, setSort] = useState<GarmentSortOption>('newest');

  // Chips and swatches apply immediately; typed filters wait for a pause.
  const debouncedSearch = useDebouncedValue(search);
  const debouncedBrand = useDebouncedValue(brand);
  const debouncedSize = useDebouncedValue(size);

  const { garments, loading, count, refresh } = useGarments({
    category: selectedCategory,
    subcategory: selectedSubcategory,
    search: debouncedSearch || undefined,
    season,
    occasion,
    brand: debouncedBrand || undefined,
    size: debouncedSize || undefined,
    color,
    sort,
  });

  // Refetch when the tab regains focus. Kept off `refresh`'s identity on
  // purpose: useGarments already refetches when a filter changes, so depending
  // on it here would run a second, redundant query for every filter change.
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  useFocusEffect(useCallback(() => { refreshRef.current(); }, []));

  const handleGarmentPress = (garment: Garment) => {
    router.push(`/garment/${garment.id}`);
  };

  const categories = [
    { key: undefined, label: t('wardrobe.filterAll') },
    ...Object.entries(CATEGORIES).map(([key, val]) => ({ key, label: t(`categories.${key}`) })),
  ];

  const subcategories: (string | undefined)[] = selectedCategory
    ? [undefined, ...(CATEGORIES[selectedCategory as keyof typeof CATEGORIES]?.subcategories ?? [])]
    : [];

  const selectCategory = (key: string | undefined) => {
    const next = selectedCategory === key ? undefined : key;
    setSelectedCategory(next);
    setSelectedSubcategory(undefined);
  };

  const sortOptions: { key: GarmentSortOption; label: string }[] = [
    { key: 'newest', label: t('wardrobe.sort.newest') },
    { key: 'oldest', label: t('wardrobe.sort.oldest') },
  ];

  const activeFilterCount = [
    selectedCategory,
    selectedSubcategory,
    season,
    occasion,
    brand.trim(),
    size.trim(),
    color,
    search.trim(),
    sort !== 'newest' ? sort : undefined,
  ].filter(Boolean).length;

  // Each filter row scrolls on its own, so a swipe can leave the first option
  // ("All" / "Any") parked off the left edge. Rewind them all when the filters
  // reset, so what you see matches the cleared state.
  const filterRowRefs = useRef(new Map<string, ScrollView>());
  const registerFilterRow = (id: string) => (row: ScrollView | null) => {
    if (row) filterRowRefs.current.set(id, row);
    else filterRowRefs.current.delete(id);
  };
  const rewindFilterRows = () => {
    filterRowRefs.current.forEach(row => row.scrollTo({ x: 0, animated: false }));
  };

  const clearAllFilters = () => {
    setSelectedCategory(undefined);
    setSelectedSubcategory(undefined);
    setSearch('');
    setSeason(undefined);
    setOccasion(undefined);
    setBrand('');
    setSize('');
    setColor(undefined);
    setSort('newest');
    rewindFilterRows();
  };

  const renderFilterRow = <T,>(
    id: string,
    data: readonly T[],
    keyExtractor: (item: T, index: number) => string,
    isActive: (item: T) => boolean,
    onPress: (item: T) => void,
    getLabel: (item: T) => string
  ) => (
    <ScrollView
      horizontal
      ref={registerFilterRow(id)}
      style={styles.filterRow}
      contentContainerStyle={styles.filterContent}
      showsHorizontalScrollIndicator={false}
      keyboardShouldPersistTaps="handled"
    >
      {data.map((item, index) => {
        const active = isActive(item);
        return (
          <Pressable
            key={keyExtractor(item, index)}
            style={[styles.filterChip, active && styles.filterChipActive]}
            onPress={() => onPress(item)}
          >
            <Text style={[styles.filterChipText, active && styles.filterChipTextActive]}>
              {getLabel(item)}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );

  return (
    <View style={styles.container}>
      <TextInput
        style={styles.searchInput}
        placeholder={t('wardrobe.searchPlaceholder')}
        placeholderTextColor={colors.textTertiary}
        value={search}
        onChangeText={setSearch}
      />
      <View style={styles.toolbarRow}>
        <Pressable style={styles.toolbarButton} onPress={() => setFiltersExpanded(!filtersExpanded)}>
          <Text style={styles.toolbarButtonText}>
            {filtersExpanded ? t('wardrobe.hideFilters') : t('wardrobe.showFilters')}
            {activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
          </Text>
        </Pressable>
        <Pressable style={[styles.toolbarButton, styles.toolbarButtonClear]} onPress={clearAllFilters}>
          <Text style={[styles.toolbarButtonText, styles.toolbarButtonClearText]}>{t('wardrobe.clearAll')}</Text>
        </Pressable>
      </View>
      {filtersExpanded && (
        <>
          <View style={styles.secondaryFilterRow}>
            <TextInput
              style={[styles.searchInput, styles.inlineInput]}
              placeholder={t('wardrobe.brandPlaceholder')}
              placeholderTextColor={colors.textTertiary}
              value={brand}
              onChangeText={setBrand}
            />
            <TextInput
              style={[styles.searchInput, styles.inlineInput]}
              placeholder={t('wardrobe.sizePlaceholder')}
              placeholderTextColor={colors.textTertiary}
              value={size}
              onChangeText={setSize}
            />
          </View>
          {renderFilterRow(
            'category',
            categories,
            item => item.key ?? 'all',
            item => selectedCategory === item.key,
            item => selectCategory(item.key),
            item => item.label
          )}
          {subcategories.length > 1 && renderFilterRow(
            'subcategory',
            subcategories,
            item => `sub-${item ?? 'all'}`,
            item => selectedSubcategory === item,
            item => setSelectedSubcategory(selectedSubcategory === item ? undefined : item),
            item => item ? (localizeSubcategory(item, t) ?? item) : t('wardrobe.filterAll')
          )}
          <ScrollView
            horizontal
            ref={registerFilterRow('color')}
            style={styles.filterRow}
            contentContainerStyle={[styles.filterContent, styles.colorFilterContent]}
            showsHorizontalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          >
            <Pressable
              style={[styles.filterChip, !color && styles.filterChipActive]}
              onPress={() => setColor(undefined)}
            >
              <Text style={[styles.filterChipText, !color && styles.filterChipTextActive]}>
                {t('wardrobe.filterAll')}
              </Text>
            </Pressable>
            {GARMENT_COLORS.map(c => {
              const active = color === c.hex;
              const isMulti = c.hex === '#RAINBOW';
              return (
                <Pressable
                  key={c.hex}
                  style={[
                    styles.colorSwatch,
                    { backgroundColor: isMulti ? colors.surfaceVariant : c.hex },
                    isMulti && styles.colorSwatchMulti,
                    active && styles.colorSwatchActive,
                  ]}
                  onPress={() => setColor(active ? undefined : c.hex)}
                  accessibilityLabel={t(`colors.${c.key}`)}
                >
                  {isMulti && <Text style={styles.colorSwatchMultiText}>{t('colorPicker.multiShort')}</Text>}
                </Pressable>
              );
            })}
          </ScrollView>
          {renderFilterRow(
            'season',
            [undefined, ...SEASON_OPTIONS],
            item => `season-${item ?? 'all'}`,
            item => season === item,
            item => setSeason(season === item ? undefined : item),
            item => item ? t(`outfits.filterValues.season.${item}`) : t('outfits.filters.any')
          )}
          {renderFilterRow(
            'occasion',
            [undefined, ...OCCASION_OPTIONS],
            item => `occasion-${item ?? 'all'}`,
            item => occasion === item,
            item => setOccasion(occasion === item ? undefined : item),
            item => item ? t(`outfits.filterValues.occasion.${item}`) : t('outfits.filters.any')
          )}
          {renderFilterRow(
            'sort',
            sortOptions,
            item => item.key,
            item => sort === item.key,
            item => setSort(item.key),
            item => item.label
          )}
        </>
      )}
      <Text style={styles.countText}>{t('wardrobe.itemsCount', { count })}</Text>
      <FlatList
        data={garments}
        numColumns={2}
        keyExtractor={item => item.id}
        renderItem={({ item }) => <GarmentCard garment={item} onPress={handleGarmentPress} />}
        contentContainerStyle={styles.grid}
        ListEmptyComponent={
          <View style={styles.empty}>
            <Text style={styles.emptyText}>
              {loading
                ? t('wardrobe.emptyLoading')
                : activeFilterCount > 0
                  ? t('wardrobe.emptyFiltered')
                  : t('wardrobe.emptyState')}
            </Text>
          </View>
        }
      />
      <Pressable style={styles.fab} onPress={() => router.push('/garment/add')}>
        <Text style={styles.fabText}>+</Text>
      </Pressable>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  toolbarRow: { flexDirection: 'row', gap: Spacing.sm, paddingHorizontal: Spacing.md, marginBottom: Spacing.sm },
  toolbarButton: {
    flex: 1,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: BorderRadius.sm,
    paddingVertical: Spacing.sm,
    alignItems: 'center',
  },
  toolbarButtonText: { color: colors.text, fontSize: FontSize.sm, fontWeight: '600' },
  toolbarButtonClear: { borderColor: colors.primary },
  toolbarButtonClearText: { color: colors.primary },
  searchInput: {
    margin: Spacing.md, backgroundColor: colors.surface, borderRadius: BorderRadius.md,
    paddingHorizontal: Spacing.md, paddingVertical: Spacing.sm, fontSize: FontSize.md,
    color: colors.text, borderWidth: 1, borderColor: colors.border,
  },
  secondaryFilterRow: {
    flexDirection: 'row',
    gap: Spacing.sm,
    paddingHorizontal: Spacing.md,
    marginBottom: Spacing.sm,
  },
  inlineInput: {
    flex: 1,
    margin: 0,
  },
  // ScrollView defaults to flexGrow/flexShrink 1, which lets these rows get
  // squeezed vertically once the expanded filter panel plus the grid overflow
  // the screen — that clipped the round color swatches. Pin them instead.
  filterRow: { marginBottom: Spacing.sm, flexGrow: 0, flexShrink: 0 },
  filterContent: { paddingHorizontal: Spacing.md, paddingVertical: 2, alignItems: 'center' },
  // Room for the active swatch's 1.15 scale (32 -> 36.8) on top of its own box.
  colorFilterContent: { paddingVertical: Spacing.xs },
  filterChip: {
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.xs,
    borderRadius: BorderRadius.full,
    backgroundColor: colors.surfaceVariant,
    marginRight: Spacing.xs,
    minHeight: 32,
    justifyContent: 'center',
  },
  filterChipActive: { backgroundColor: colors.primary },
  colorSwatch: {
    width: 32,
    height: 32,
    borderRadius: 16,
    borderWidth: 2,
    borderColor: colors.border,
    marginRight: Spacing.xs,
    justifyContent: 'center',
    alignItems: 'center',
  },
  colorSwatchMulti: { borderColor: colors.primary },
  colorSwatchMultiText: { fontSize: FontSize.xs, fontWeight: '700', color: colors.primary },
  colorSwatchActive: {
    borderColor: colors.primary,
    borderWidth: 3,
    transform: [{ scale: 1.15 }],
  },
  filterChipText: { fontSize: FontSize.sm, color: colors.textSecondary, fontWeight: '500', includeFontPadding: false },
  filterChipTextActive: { color: '#fff' },
  countText: { paddingHorizontal: Spacing.md, paddingBottom: Spacing.sm, fontSize: FontSize.xs, color: colors.textTertiary },
  grid: { padding: Spacing.sm },
  empty: { padding: Spacing.xxl, alignItems: 'center' },
  emptyText: { fontSize: FontSize.md, color: colors.textSecondary, textAlign: 'center' },
  fab: {
    position: 'absolute', bottom: Spacing.lg, right: Spacing.lg, width: 56, height: 56,
    borderRadius: 28, backgroundColor: colors.primary, justifyContent: 'center',
    alignItems: 'center', elevation: 4, shadowColor: colors.primary,
    shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.3, shadowRadius: 8,
  },
  fabText: { color: '#fff', fontSize: 28, fontWeight: '300', marginTop: -2 },
});

