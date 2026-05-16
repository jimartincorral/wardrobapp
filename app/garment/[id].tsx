import React, { useState, useEffect } from 'react';
import { View, Text, Image, ScrollView, Pressable, StyleSheet, Alert, Platform, ActivityIndicator } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { getGarment, markUnavailable, markAvailable, deleteGarment, updateGarment } from '@/src/services/garment-service';
import { isBackgroundRemovalAvailable, removeImageBackground } from '@/src/services/background-removal';
import { CATEGORIES } from '@/src/constants/categories';
import { GARMENT_COLORS } from '@/src/constants/colors';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { formatDate } from '@/src/utils/date-helpers';
import { splitStructuredTags } from '@/src/utils/style-tags';
import { localizeSubcategories } from '@/src/utils/localization-helpers';
import { useTranslation } from '@/src/i18n';
import type { Garment } from '@/src/types';
import { getGarmentColorPalette, getGarmentImageUris, getGarmentNoBgImageUris } from '@/src/utils/garment-fields';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function GarmentDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [garment, setGarment] = useState<Garment | null>(null);
  const [removingBg, setRemovingBg] = useState(false);
  const [selectedImageIndex, setSelectedImageIndex] = useState(0);

  useEffect(() => { loadGarment(); }, [id]);

  const loadGarment = async () => {
    if (!id) return;
    const g = await getGarment(id);
    setGarment(g);
    setSelectedImageIndex(0);
  };

  const confirmAction = (
    title: string,
    message: string,
    confirmLabel: string,
    onConfirm: () => Promise<void>
  ) => {
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
        { text: t('garmentDetail.alerts.cancel'), style: 'cancel' },
        {
          text: confirmLabel,
          style: 'destructive',
          onPress: () => { void onConfirm(); },
        },
      ]
    );
  };

  const handleToggleAvailability = async () => {
    if (!garment) return;
    if (garment.is_available) {
      confirmAction(
        t('garmentDetail.alerts.markUnavailableTitle'),
        t('garmentDetail.alerts.markUnavailableMsg'),
        t('garmentDetail.alerts.markUnavailableConfirm'),
        async () => { await markUnavailable(garment.id); await loadGarment(); }
      );
    } else {
      await markAvailable(garment.id);
      await loadGarment();
    }
  };

  const handleDelete = () => {
    if (!garment) return;
    confirmAction(
      t('garmentDetail.alerts.deleteTitle'),
      t('garmentDetail.alerts.deleteMsg'),
      t('garmentDetail.alerts.deleteConfirm'),
      async () => {
        await deleteGarment(garment.id);
        if (router.canGoBack()) {
          router.back();
        } else {
          router.replace('/(tabs)/wardrobe');
        }
      }
    );
  };

  const handleRemoveBackground = async () => {
    if (!garment || removingBg) return;

    if (!isBackgroundRemovalAvailable()) {
      Alert.alert(t('addGarment.bgRemoval.errorTitle'), t('addGarment.bgRemoval.unavailable'));
      return;
    }

    try {
      setRemovingBg(true);
      const imageUris = getGarmentImageUris(garment);
      const imageUrisNoBg = [...getGarmentNoBgImageUris(garment)];
      const sourceUri = imageUris[selectedImageIndex] || garment.image_uri;
      const result = await removeImageBackground(sourceUri);
      imageUrisNoBg[selectedImageIndex] = result;
      await updateGarment(garment.id, {
        image_uri_nobg: imageUrisNoBg[0] || null,
        image_uris_nobg: imageUrisNoBg,
      });
      await loadGarment();
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      Alert.alert(t('addGarment.bgRemoval.errorTitle'), `${t('addGarment.bgRemoval.failed')}\n\n${message}`);
    } finally {
      setRemovingBg(false);
    }
  };

  const handleUndoBackground = async () => {
    if (!garment || removingBg) return;
    try {
      const imageUrisNoBg = [...getGarmentNoBgImageUris(garment)];
      imageUrisNoBg[selectedImageIndex] = '';
      await updateGarment(garment.id, {
        image_uri_nobg: imageUrisNoBg[0] || null,
        image_uris_nobg: imageUrisNoBg,
      });
      await loadGarment();
    } catch {
      Alert.alert(t('addGarment.errors.errorTitle'), t('addGarment.errors.saveFailed'));
    }
  };

  if (!garment) {
    return <View style={styles.loading}><Text>{t('garmentDetail.loading')}</Text></View>;
  }

  const garmentImages = getGarmentImageUris(garment);
  const garmentNoBgImages = getGarmentNoBgImageUris(garment);
  const displayedImage = garmentNoBgImages[selectedImageIndex] || garmentImages[selectedImageIndex] || garmentImages[0];
  const colorLabels = getGarmentColorPalette(garment).map(color => {
    const colorKey = GARMENT_COLORS.find(c => c.hex === color)?.key;
    return colorKey ? t(`colors.${colorKey}`) : color;
  });
  const localizedSubcategories = localizeSubcategories(garment.subcategories, t);
  const { seasons, weather, occasions, customTags } = splitStructuredTags(garment.tags);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.imageFrame}>
        <Image source={{ uri: displayedImage }} style={styles.image} resizeMode="contain" />
      </View>
      {garmentImages.length > 1 && (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.thumbnailRow}>
          {garmentImages.map((uri, index) => (
            <Pressable
              key={`${uri}-${index}`}
              style={[styles.thumbnailButton, selectedImageIndex === index && styles.thumbnailButtonActive]}
              onPress={() => setSelectedImageIndex(index)}
            >
              <Image source={{ uri: garmentNoBgImages[index] || uri }} style={styles.thumbnailImage} resizeMode="cover" />
            </Pressable>
          ))}
        </ScrollView>
      )}
      {!garment.is_available && (
        <View style={styles.unavailableBanner}>
          <Text style={styles.unavailableBannerText}>
            {t('garmentDetail.unavailableSince', {
              date: garment.unavailable_date ? formatDate(garment.unavailable_date) : '—'
            })}
          </Text>
        </View>
      )}
      <View style={styles.details}>
        <Text style={styles.category}>
          {t(`categories.${garment.category}`)}
          {localizedSubcategories.length > 0 ? ` \u2022 ${localizedSubcategories.join(', ')}` : ''}
        </Text>
        {garment.brand && <Text style={styles.brand}>{garment.brand}</Text>}
        <View style={styles.propsSection}>
          <View style={styles.propRow}>
            <Text style={styles.propLabel}>{t('garmentDetail.props.colors')}</Text>
            <View style={styles.propValueWrap}>
              <View style={styles.propValueRow}>
                {getGarmentColorPalette(garment).map(color => (
                  <View key={color} style={[styles.colorDot, { backgroundColor: color }]} />
                ))}
              </View>
              <Text style={styles.propValue}>{colorLabels.join(', ')}</Text>
            </View>
          </View>
          {garment.size && (
            <View style={styles.propRow}>
              <Text style={styles.propLabel}>{t('garmentDetail.props.size')}</Text>
              <Text style={styles.propValue}>{garment.size}</Text>
            </View>
          )}
          {localizedSubcategories.length > 0 && (
            <View style={styles.propRow}>
              <Text style={styles.propLabel}>{t('addGarment.labels.type')}</Text>
              <Text style={styles.propValue}>{localizedSubcategories.join(', ')}</Text>
            </View>
          )}
          {seasons.length > 0 && (
            <View style={styles.propRow}>
              <Text style={styles.propLabel}>{t('garmentDetail.props.seasons')}</Text>
              <Text style={styles.propValue}>{seasons.map(s => t(`outfits.filterValues.season.${s}`)).join(', ')}</Text>
            </View>
          )}
          {weather.length > 0 && (
            <View style={styles.propRow}>
              <Text style={styles.propLabel}>{t('garmentDetail.props.weather')}</Text>
              <Text style={styles.propValue}>{weather.map(w => t(`outfits.filterValues.weather.${w}`)).join(', ')}</Text>
            </View>
          )}
          {occasions.length > 0 && (
            <View style={styles.propRow}>
              <Text style={styles.propLabel}>{t('garmentDetail.props.occasions')}</Text>
              <Text style={styles.propValue}>{occasions.map(o => t(`outfits.filterValues.occasion.${o}`)).join(', ')}</Text>
            </View>
          )}
          {garment.purchase_date && (
            <View style={styles.propRow}>
              <Text style={styles.propLabel}>{t('garmentDetail.props.added')}</Text>
              <Text style={styles.propValue}>{formatDate(garment.purchase_date)}</Text>
            </View>
          )}
        </View>
        {customTags.length > 0 && (
          <View style={styles.tagsSection}>
            <Text style={styles.tagsTitle}>{t('garmentDetail.tags')}</Text>
            <View style={styles.tagsContainer}>
              {customTags.map(tag => (
                <View key={tag} style={styles.tag}><Text style={styles.tagText}>{tag}</Text></View>
              ))}
            </View>
          </View>
        )}
        <Pressable
          style={[styles.actionButton, !garment.is_available && styles.actionButtonPositive]}
          onPress={handleToggleAvailability}
        >
          <Text style={[styles.actionButtonText, !garment.is_available && styles.actionButtonTextPositive]}>
            {garment.is_available ? t('garmentDetail.markUnavailable') : t('garmentDetail.markAvailable')}
          </Text>
        </Pressable>
        <Pressable style={styles.editButton} onPress={() => router.push(`/garment/edit/${garment.id}`)}>
          <Text style={styles.editButtonText}>{t('garmentDetail.editGarment')}</Text>
        </Pressable>
        {!garmentNoBgImages[selectedImageIndex] ? (
          <Pressable style={styles.editButton} onPress={handleRemoveBackground} disabled={removingBg}>
            {removingBg ? (
              <ActivityIndicator color={colors.primary} />
            ) : (
              <Text style={styles.editButtonText}>{t('addGarment.removeBackground')}</Text>
            )}
          </Pressable>
        ) : (
          <Pressable style={styles.actionButton} onPress={handleUndoBackground} disabled={removingBg}>
            <Text style={styles.actionButtonText}>{t('addGarment.undoBackground')}</Text>
          </Pressable>
        )}
        <Pressable style={styles.deleteButton} onPress={handleDelete}>
          <Text style={styles.deleteButtonText}>{t('garmentDetail.deleteGarment')}</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { paddingBottom: Spacing.xxl * 2 },
  loading: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  imageFrame: {
    width: '100%',
    height: 400,
    backgroundColor: colors.surfaceVariant,
    justifyContent: 'center',
    alignItems: 'center',
  },
  image: { width: '100%', height: '100%' },
  thumbnailRow: {
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm,
    gap: Spacing.sm,
  },
  thumbnailButton: {
    borderWidth: 2,
    borderColor: 'transparent',
    borderRadius: BorderRadius.md,
    overflow: 'hidden',
    marginRight: Spacing.sm,
  },
  thumbnailButtonActive: {
    borderColor: colors.primary,
  },
  thumbnailImage: {
    width: 72,
    height: 96,
    backgroundColor: colors.surfaceVariant,
  },
  unavailableBanner: { backgroundColor: colors.error, padding: Spacing.sm, alignItems: 'center' },
  unavailableBannerText: { color: '#fff', fontSize: FontSize.sm, fontWeight: '600' },
  details: { padding: Spacing.lg },
  category: { fontSize: FontSize.xl, fontWeight: '700', color: colors.text },
  brand: { fontSize: FontSize.lg, color: colors.textSecondary, marginTop: Spacing.xs },
  propsSection: { marginTop: Spacing.lg },
  propRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: Spacing.sm, borderBottomWidth: 1, borderBottomColor: colors.border },
  propLabel: { fontSize: FontSize.md, color: colors.textSecondary },
  propValue: { fontSize: FontSize.md, fontWeight: '600', color: colors.text },
  propValueWrap: { alignItems: 'flex-end', maxWidth: '60%' },
  propValueRow: { flexDirection: 'row', alignItems: 'center', gap: Spacing.sm },
  colorDot: { width: 20, height: 20, borderRadius: 10, borderWidth: 1, borderColor: colors.border },
  tagsSection: { marginTop: Spacing.lg },
  tagsTitle: { fontSize: FontSize.sm, fontWeight: '600', color: colors.text, marginBottom: Spacing.sm },
  tagsContainer: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.xs },
  tag: { backgroundColor: colors.primaryLight + '30', borderRadius: BorderRadius.full, paddingHorizontal: Spacing.md, paddingVertical: Spacing.xs },
  tagText: { fontSize: FontSize.sm, color: colors.primary },
  actionButton: { backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center', marginTop: Spacing.sm },
  actionButtonPositive: { backgroundColor: colors.success + '20' },
  actionButtonText: { fontSize: FontSize.md, fontWeight: '600', color: colors.textSecondary },
  actionButtonTextPositive: { color: colors.success },
  editButton: { backgroundColor: colors.primary + '10', borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center', marginTop: Spacing.sm },
  editButtonText: { fontSize: FontSize.md, fontWeight: '600', color: colors.primary },
  deleteButton: { backgroundColor: colors.error + '10', borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center', marginTop: Spacing.sm },
  deleteButtonText: { fontSize: FontSize.md, fontWeight: '600', color: colors.error },
});
