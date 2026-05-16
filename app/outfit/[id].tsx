import React, { useState, useEffect } from 'react';
import { View, Text, Image, ScrollView, Pressable, StyleSheet, Alert, Platform } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { getOutfit, getOutfitRatings, rateOutfit, deleteOutfit } from '@/src/services/outfit-service';
import { getGarment } from '@/src/services/garment-service';
import { RatingStars } from '@/src/components/RatingStars';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { formatDate } from '@/src/utils/date-helpers';
import { localizeGarmentLabel } from '@/src/utils/localization-helpers';
import { useTranslation } from '@/src/i18n';
import type { Outfit, OutfitRating, Garment } from '@/src/types';
import { getGarmentDisplayImage } from '@/src/utils/garment-fields';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function OutfitDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [outfit, setOutfit] = useState<Outfit | null>(null);
  const [garments, setGarments] = useState<Garment[]>([]);
  const [ratings, setRatings] = useState<OutfitRating[]>([]);
  const [myRating, setMyRating] = useState(0);

  useEffect(() => { loadOutfit(); }, [id]);

  const loadOutfit = async () => {
    if (!id) return;
    const o = await getOutfit(id);
    setOutfit(o);
    if (o) {
      const gs = await Promise.all(o.garment_ids.map(gid => getGarment(gid)));
      setGarments(gs.filter((g): g is Garment => g !== null));
      setRatings(await getOutfitRatings(id));
    }
  };

  const handleRate = async (rating: number) => {
    if (!id) return;
    setMyRating(rating);
    await rateOutfit(id, rating);
    await loadOutfit();
  };

  const handleDelete = () => {
    if (!outfit) return;

    const onConfirm = async () => {
      await deleteOutfit(outfit.id);
      if (router.canGoBack()) {
        router.back();
      } else {
        router.replace('/(tabs)/outfits');
      }
    };

    const title = t('outfitDetail.alerts.deleteTitle');
    const message = t('outfitDetail.alerts.deleteMsg');

    if (Platform.OS === 'web' && typeof globalThis.confirm === 'function') {
      if (globalThis.confirm(`${title}\n\n${message}`)) {
        void onConfirm();
      }
      return;
    }

    Alert.alert(
      title,
      message,
      [
        { text: t('outfitDetail.alerts.cancel'), style: 'cancel' },
        {
          text: t('outfitDetail.alerts.deleteConfirm'),
          style: 'destructive',
          onPress: () => { void onConfirm(); },
        },
      ]
    );
  };

  if (!outfit) {
    return <View style={styles.loading}><Text>{t('outfitDetail.loading')}</Text></View>;
  }

  const avgRating = ratings.length > 0
    ? ratings.reduce((sum, r) => sum + r.rating, 0) / ratings.length
    : 0;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.name}>{outfit.name}</Text>
      <Text style={styles.meta}>
        {t('outfitDetail.createdAt', { date: formatDate(outfit.created_at) })}
        {outfit.occasion ? ` \u2022 ${t(`outfits.filterValues.occasion.${outfit.occasion}`)}` : ''}
      </Text>
      <View style={styles.garmentGrid}>
        {garments.map(g => (
          <Pressable key={g.id} style={styles.garmentItem} onPress={() => router.push(`/garment/${g.id}`)}>
            <Image source={{ uri: getGarmentDisplayImage(g) }} style={styles.garmentImage} resizeMode="cover" />
            <Text style={styles.garmentLabel} numberOfLines={1}>{localizeGarmentLabel(g.category, g.subcategories, t)}</Text>
            {g.brand && <Text style={styles.garmentBrand} numberOfLines={1}>{g.brand}</Text>}
          </Pressable>
        ))}
      </View>
      {avgRating > 0 && (
        <View style={styles.avgSection}>
          <Text style={styles.avgLabel}>{t('outfitDetail.avgRating')}</Text>
          <RatingStars rating={Math.round(avgRating)} readonly size={24} />
          <Text style={styles.avgText}>{t('outfitDetail.ratingCount', { avg: avgRating.toFixed(1), count: ratings.length })}</Text>
        </View>
      )}
      <View style={styles.rateSection}>
        <Text style={styles.rateLabel}>{t('outfitDetail.rateTitle')}</Text>
        <RatingStars rating={myRating} onRate={handleRate} size={36} />
      </View>
      <Pressable style={styles.deleteButton} onPress={handleDelete}>
        <Text style={styles.deleteButtonText}>{t('outfitDetail.deleteOutfit')}</Text>
      </Pressable>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg, paddingBottom: Spacing.xxl * 2 },
  loading: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  name: { fontSize: FontSize.xxl, fontWeight: '700', color: colors.text },
  meta: { fontSize: FontSize.sm, color: colors.textSecondary, marginTop: Spacing.xs, marginBottom: Spacing.lg },
  garmentGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.md, justifyContent: 'center' },
  garmentItem: { alignItems: 'center', width: '30%' },
  garmentImage: { width: '100%', aspectRatio: 3 / 4, borderRadius: BorderRadius.md, backgroundColor: colors.surfaceVariant },
  garmentLabel: { fontSize: FontSize.sm, fontWeight: '600', color: colors.text, marginTop: Spacing.xs, textAlign: 'center' },
  garmentBrand: { fontSize: FontSize.xs, color: colors.textTertiary, textAlign: 'center' },
  avgSection: { alignItems: 'center', marginTop: Spacing.xl, padding: Spacing.md, backgroundColor: colors.surface, borderRadius: BorderRadius.md },
  avgLabel: { fontSize: FontSize.sm, color: colors.textSecondary, marginBottom: Spacing.xs },
  avgText: { fontSize: FontSize.xs, color: colors.textTertiary, marginTop: Spacing.xs },
  rateSection: { alignItems: 'center', marginTop: Spacing.lg, padding: Spacing.lg, backgroundColor: colors.surface, borderRadius: BorderRadius.md },
  rateLabel: { fontSize: FontSize.md, fontWeight: '600', color: colors.text, marginBottom: Spacing.md },
  deleteButton: { backgroundColor: colors.error + '10', borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center', marginTop: Spacing.lg },
  deleteButtonText: { fontSize: FontSize.md, fontWeight: '600', color: colors.error },
});
