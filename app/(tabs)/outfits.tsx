import React, { useState, useCallback } from 'react';
import { View, Text, ScrollView, Pressable, StyleSheet, ActivityIndicator, Alert } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { OutfitPreview } from '@/src/components/OutfitPreview';
import { RatingStars } from '@/src/components/RatingStars';
import { generateSuggestions } from '@/src/services/suggestion-engine';
import { createOutfit, rateOutfit, getAllOutfits, setOutfitPinned } from '@/src/services/outfit-service';
import { SEASON_OPTIONS, WEATHER_OPTIONS, OCCASION_OPTIONS } from '@/src/constants/style-filters';
import type { SeasonOption, WeatherOption, OccasionOption } from '@/src/constants/style-filters';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useTranslation } from '@/src/i18n';
import type { Garment, Outfit } from '@/src/types';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

interface Suggestion { garments: Garment[]; score: number; name: string; }

function toggleArrayValue<T>(values: T[], value: T): T[] {
  return values.includes(value)
    ? values.filter(item => item !== value)
    : [...values, value];
}

export default function OutfitsScreen() {
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [savedOutfits, setSavedOutfits] = useState<Outfit[]>([]);
  const [loading, setLoading] = useState(false);
  const [ratings, setRatings] = useState<Record<number, number>>({});
  const [seasonFilter, setSeasonFilter] = useState<SeasonOption[]>([]);
  const [weatherFilter, setWeatherFilter] = useState<WeatherOption | undefined>();
  const [occasionFilter, setOccasionFilter] = useState<OccasionOption | undefined>();

  const loadSavedOutfits = useCallback(async () => {
    try {
      setSavedOutfits(await getAllOutfits());
    } catch (e) {
      console.error(e);
    }
  }, []);

  useFocusEffect(useCallback(() => {
    loadSavedOutfits();
  }, [loadSavedOutfits]));

  const handleGenerateSuggestions = async () => {
    setLoading(true);
    setRatings({});
    try {
      setSuggestions(await generateSuggestions({
        count: 3,
        preferences: {
          seasons: seasonFilter,
          weather: weatherFilter,
          occasion: occasionFilter,
        },
      }));
    }
    catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  const handleRate = async (index: number, rating: number) => {
    setRatings({ ...ratings, [index]: rating });
    const suggestion = suggestions[index];
    try {
      const outfit = await createOutfit({ name: suggestion.name, garment_ids: suggestion.garments.map(g => g.id), is_suggested: true });
      await rateOutfit(outfit.id, rating);
      await loadSavedOutfits();
    } catch (e) { console.error(e); }
  };

  const handleSaveSuggestion = async (suggestion: Suggestion) => {
    try {
      await createOutfit({ name: suggestion.name, garment_ids: suggestion.garments.map(g => g.id), is_suggested: true });
      Alert.alert(t('outfits.saved'), t('outfits.savedMsg'));
      await loadSavedOutfits();
    } catch (e) { console.error(e); }
  };

  const handleTogglePin = async (outfit: Outfit) => {
    try {
      await setOutfitPinned(outfit.id, !outfit.is_pinned);
      await loadSavedOutfits();
    } catch (e) {
      console.error(e);
    }
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.sectionTitle}>{t('outfits.title')}</Text>
      <Text style={styles.sectionDesc}>{t('outfits.subtitle')}</Text>

      <Text style={styles.filterLabel}>{t('outfits.filters.season')}</Text>
      <View style={styles.filterRow}>
        {[undefined, ...SEASON_OPTIONS].map(value => (
          <Pressable
            key={`season-${value ?? 'any'}`}
            style={[styles.filterChip, ((value == null && seasonFilter.length === 0) || (value != null && seasonFilter.includes(value))) && styles.filterChipActive]}
            onPress={() => {
              if (value == null) {
                setSeasonFilter([]);
                return;
              }
              setSeasonFilter(current => toggleArrayValue(current, value));
            }}
          >
            <Text style={[styles.filterChipText, ((value == null && seasonFilter.length === 0) || (value != null && seasonFilter.includes(value))) && styles.filterChipTextActive]}>
              {value ? t(`outfits.filterValues.season.${value}`) : t('outfits.filters.any')}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.filterLabel}>{t('outfits.filters.weather')}</Text>
      <View style={styles.filterRow}>
        {[undefined, ...WEATHER_OPTIONS].map(value => (
          <Pressable
            key={`weather-${value ?? 'any'}`}
            style={[styles.filterChip, weatherFilter === value && styles.filterChipActive]}
            onPress={() => setWeatherFilter(weatherFilter === value ? undefined : value)}
          >
            <Text style={[styles.filterChipText, weatherFilter === value && styles.filterChipTextActive]}>
              {value ? t(`outfits.filterValues.weather.${value}`) : t('outfits.filters.any')}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.filterLabel}>{t('outfits.filters.occasion')}</Text>
      <View style={styles.filterRow}>
        {[undefined, ...OCCASION_OPTIONS].map(value => (
          <Pressable
            key={`occasion-${value ?? 'any'}`}
            style={[styles.filterChip, occasionFilter === value && styles.filterChipActive]}
            onPress={() => setOccasionFilter(occasionFilter === value ? undefined : value)}
          >
            <Text style={[styles.filterChipText, occasionFilter === value && styles.filterChipTextActive]}>
              {value ? t(`outfits.filterValues.occasion.${value}`) : t('outfits.filters.any')}
            </Text>
          </Pressable>
        ))}
      </View>

      <Pressable style={styles.generateButton} onPress={handleGenerateSuggestions}>
        <Text style={styles.generateText}>{loading ? t('outfits.generating') : t('outfits.generateButton')}</Text>
      </Pressable>
      {loading && <ActivityIndicator size="large" color={colors.primary} style={{ marginVertical: Spacing.xl }} />}
      {suggestions.map((suggestion, index) => (
        <OutfitPreview key={index} garments={suggestion.garments} name={suggestion.name} score={suggestion.score}>
          <View style={styles.ratingRow}>
            <RatingStars rating={ratings[index] ?? 0} onRate={(r) => handleRate(index, r)} />
            <Pressable style={styles.wearButton} onPress={() => handleSaveSuggestion(suggestion)}>
              <Text style={styles.wearButtonText}>{t('outfits.saveOutfit')}</Text>
            </Pressable>
          </View>
        </OutfitPreview>
      ))}
      {suggestions.length === 0 && !loading && (
        <View style={styles.nudgeCard}>
          <Text style={styles.nudgeTitle}>{t('outfits.emptyHint')}</Text>
          <Text style={styles.nudgeText}>{t('outfits.emptyNudge')}</Text>
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
      {savedOutfits.length > 0 && (
        <>
          <Text style={[styles.sectionTitle, { marginTop: Spacing.xl }]}>{t('outfits.savedTitle')}</Text>
          {savedOutfits.slice(0, 10).map(outfit => (
            <View key={outfit.id} style={styles.savedOutfit}>
              <Pressable style={styles.savedOutfitMain} onPress={() => router.push(`/outfit/${outfit.id}`)}>
                <Text style={styles.savedOutfitName}>{outfit.is_pinned ? `Pinned: ${outfit.name}` : outfit.name}</Text>
                <Text style={styles.savedOutfitMeta}>{t('outfits.itemsCount', { count: outfit.garment_ids.length })}</Text>
              </Pressable>
              <Pressable style={styles.pinButton} onPress={() => handleTogglePin(outfit)}>
                <Text style={styles.pinButtonText}>
                  {outfit.is_pinned ? t('outfits.unpin') : t('outfits.pin')}
                </Text>
              </Pressable>
            </View>
          ))}
        </>
      )}
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg },
  sectionTitle: { fontSize: FontSize.xl, fontWeight: '700', color: colors.text, marginBottom: Spacing.xs },
  sectionDesc: { fontSize: FontSize.sm, color: colors.textSecondary, marginBottom: Spacing.lg },
  filterLabel: { fontSize: FontSize.sm, fontWeight: '600', color: colors.text, marginBottom: Spacing.xs },
  filterRow: { flexDirection: 'row', flexWrap: 'wrap', marginBottom: Spacing.sm },
  filterChip: {
    maxWidth: '100%',
    alignSelf: 'flex-start',
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.xs,
    borderRadius: BorderRadius.full,
    backgroundColor: colors.surfaceVariant,
    marginRight: Spacing.xs,
    marginBottom: Spacing.xs,
  },
  filterChipActive: { backgroundColor: colors.primary },
  filterChipText: { fontSize: FontSize.sm, color: colors.textSecondary, flexShrink: 1 },
  filterChipTextActive: { color: '#fff', fontWeight: '600' },
  generateButton: { backgroundColor: colors.primary, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center', marginBottom: Spacing.lg },
  generateText: { color: '#fff', fontSize: FontSize.md, fontWeight: '700' },
  ratingRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: Spacing.md, paddingTop: Spacing.md, borderTopWidth: 1, borderTopColor: colors.border },
  wearButton: { backgroundColor: colors.success, borderRadius: BorderRadius.sm, paddingHorizontal: Spacing.md, paddingVertical: Spacing.xs },
  wearButtonText: { color: '#fff', fontSize: FontSize.sm, fontWeight: '600' },
  savedOutfit: { backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md, marginBottom: Spacing.sm, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  savedOutfitMain: { flex: 1, marginRight: Spacing.sm },
  savedOutfitName: { fontSize: FontSize.md, fontWeight: '600', color: colors.text },
  savedOutfitMeta: { fontSize: FontSize.sm, color: colors.textTertiary },
  pinButton: {
    borderWidth: 1,
    borderColor: colors.primary,
    borderRadius: BorderRadius.sm,
    paddingHorizontal: Spacing.sm,
    paddingVertical: Spacing.xs,
  },
  pinButtonText: { color: colors.primary, fontSize: FontSize.xs, fontWeight: '700' },
  nudgeCard: { marginTop: Spacing.lg, padding: Spacing.md, borderRadius: BorderRadius.md, backgroundColor: colors.surface },
  nudgeTitle: { fontSize: FontSize.md, fontWeight: '700', color: colors.text },
  nudgeText: { marginTop: Spacing.xs, color: colors.textSecondary, fontSize: FontSize.sm },
  nudgeActionsRow: { flexDirection: 'row', gap: Spacing.sm, marginTop: Spacing.md },
  nudgeButton: { flex: 1, backgroundColor: colors.primary, borderRadius: BorderRadius.sm, paddingVertical: Spacing.sm, alignItems: 'center' },
  nudgeButtonText: { color: '#fff', fontWeight: '700', fontSize: FontSize.sm },
  secondaryButton: { flex: 1, borderWidth: 1, borderColor: colors.border, borderRadius: BorderRadius.sm, paddingVertical: Spacing.sm, alignItems: 'center' },
  secondaryButtonText: { color: colors.text, fontWeight: '600', fontSize: FontSize.sm },
});
