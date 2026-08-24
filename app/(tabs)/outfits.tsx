import React, { useState, useCallback, useRef } from 'react';
import { View, Text, ScrollView, Pressable, StyleSheet, ActivityIndicator, Alert } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { OutfitPreview } from '@/src/components/OutfitPreview';
import { RatingStars } from '@/src/components/RatingStars';
import { generateSuggestions } from '@/src/services/suggestion-engine';
import { createOutfit, rateOutfit, getAllOutfits, setOutfitPinned } from '@/src/services/outfit-service';
import {
  NO_FILTERS,
  occasionChips,
  seasonChips,
  withOccasionSelected,
  withSeasonToggled,
} from '@/src/domain/outfit-filters';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useTranslation } from '@/src/i18n';
import type { Garment, Outfit } from '@/src/types';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

interface Suggestion { garments: Garment[]; score: number; name: string; }

export default function OutfitsScreen() {
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [savedOutfits, setSavedOutfits] = useState<Outfit[]>([]);
  const [loading, setLoading] = useState(false);
  const [ratings, setRatings] = useState<Record<number, number>>({});
  // Which chips are on, and what tapping one does, is decided in the domain
  // layer -- shared with the Kotlin port through a parity fixture. The screen
  // used to work out "is this chip active" twice per chip, once for its
  // background and once for its text.
  const [filters, setFilters] = useState(NO_FILTERS);
  // Each suggestion is saved as at most one outfit, keyed by its position in the
  // current batch. Held in a ref (not state) and keyed by the in-flight promise
  // so two quick taps can't both see "not created yet" and each save a copy.
  const suggestionOutfitsRef = useRef<Record<number, Promise<string>>>({});

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

  const ensureSuggestionOutfit = (index: number, suggestion: Suggestion): Promise<string> => {
    const pending = suggestionOutfitsRef.current[index];
    if (pending) return pending;

    const created = createOutfit({
      name: suggestion.name,
      garment_ids: suggestion.garments.map(g => g.id),
      is_suggested: true,
    })
      .then(outfit => outfit.id)
      .catch(error => {
        // Don't cache the failure — let the next tap retry.
        delete suggestionOutfitsRef.current[index];
        throw error;
      });

    suggestionOutfitsRef.current[index] = created;
    return created;
  };

  const handleGenerateSuggestions = async () => {
    setLoading(true);
    setRatings({});
    suggestionOutfitsRef.current = {};
    try {
      setSuggestions(await generateSuggestions({
        count: 3,
        preferences: { seasons: filters.seasons, occasion: filters.occasion },
      }));
    }
    catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  const handleRate = async (index: number, rating: number) => {
    setRatings(current => ({ ...current, [index]: rating }));
    try {
      const outfitId = await ensureSuggestionOutfit(index, suggestions[index]);
      await rateOutfit(outfitId, rating);
      await loadSavedOutfits();
    } catch (e) { console.error(e); }
  };

  const handleSaveSuggestion = async (index: number, suggestion: Suggestion) => {
    try {
      await ensureSuggestionOutfit(index, suggestion);
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
        {seasonChips(filters).map(chip => (
          <Pressable
            key={`season-${chip.value ?? 'any'}`}
            style={[styles.filterChip, chip.active && styles.filterChipActive]}
            onPress={() => setFilters(current => withSeasonToggled(current, chip.value))}
          >
            <Text style={[styles.filterChipText, chip.active && styles.filterChipTextActive]}>
              {chip.value ? t(`outfits.filterValues.season.${chip.value}`) : t('outfits.filters.any')}
            </Text>
          </Pressable>
        ))}
      </View>


      <Text style={styles.filterLabel}>{t('outfits.filters.occasion')}</Text>
      <View style={styles.filterRow}>
        {occasionChips(filters).map(chip => (
          <Pressable
            key={`occasion-${chip.value ?? 'any'}`}
            style={[styles.filterChip, chip.active && styles.filterChipActive]}
            onPress={() => setFilters(current => withOccasionSelected(current, chip.value))}
          >
            <Text style={[styles.filterChipText, chip.active && styles.filterChipTextActive]}>
              {chip.value ? t(`outfits.filterValues.occasion.${chip.value}`) : t('outfits.filters.any')}
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
            <Pressable style={styles.wearButton} onPress={() => handleSaveSuggestion(index, suggestion)}>
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
                <Text style={styles.savedOutfitName}>{outfit.is_pinned ? t('outfits.pinnedName', { name: outfit.name }) : outfit.name}</Text>
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
