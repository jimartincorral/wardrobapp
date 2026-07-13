import React, { useEffect, useState } from 'react';
import { Alert, ActivityIndicator, Text, View, StyleSheet } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { GarmentForm } from '@/src/components/GarmentForm';
import { garmentToFormData, useGarmentForm } from '@/src/hooks/useGarmentForm';
import { compressAndSaveImage, deleteImage, saveBgRemovedImage } from '@/src/services/image-service';
import { getGarment, updateGarment } from '@/src/services/garment-service';
import { mergeStructuredTags } from '@/src/utils/style-tags';
import { useTranslation } from '@/src/i18n';
import { useTheme } from '@/src/theme';
import type { Garment } from '@/src/types';
import { Spacing } from '@/src/constants/theme';

export default function EditGarmentScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const form = useGarmentForm(t);
  const { replaceData, data } = form;

  const [garment, setGarment] = useState<Garment | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const loadGarment = async () => {
      if (!id) return;
      setLoading(true);
      try {
        const current = await getGarment(id);
        if (!current) {
          router.back();
          return;
        }
        setGarment(current);
        replaceData(garmentToFormData(current));
      } catch (error) {
        console.error(error);
        Alert.alert(t('addGarment.errors.errorTitle'), t('addGarment.errors.saveFailed'));
      } finally {
        setLoading(false);
      }
    };

    void loadGarment();
  }, [id, replaceData, router, t]);

  const handleSave = async () => {
    if (!id || !garment || data.imageUris.length === 0) return;

    setSaving(true);
    try {
      // Store only the cut-out for slots whose background was removed; never
      // persist the original alongside it. Reuse already-saved files as-is.
      const saved = await Promise.all(
        data.imageUris.map(async (uri, index) => {
          const bgSource = data.bgRemovedUris[index];
          if (bgSource) {
            if (garment.image_uris_nobg.includes(bgSource)) {
              return { image: bgSource, nobg: bgSource };
            }
            try {
              const cutout = await saveBgRemovedImage(bgSource);
              return { image: cutout, nobg: cutout };
            } catch (error) {
              console.warn('Failed to persist background-removed image, keeping original only:', error);
            }
          }
          const image = garment.image_uris.includes(uri) ? uri : await compressAndSaveImage(uri);
          return { image, nobg: '' };
        })
      );
      const savedImageUris = saved.map((s) => s.image);
      const savedBgRemovedUris = saved.map((s) => s.nobg);

      // Any file the garment referenced before but no longer does — the dropped
      // original of a newly cut-out image, a discarded cut-out, or a removed
      // photo — is now orphaned. Delete it after the DB write succeeds.
      const keptFiles = new Set([...savedImageUris, ...savedBgRemovedUris].filter(Boolean));
      const orphanedFiles = [...new Set([...garment.image_uris, ...garment.image_uris_nobg])]
        .filter((f) => f && !keptFiles.has(f));

      await updateGarment(id, {
        image_uri: savedImageUris[0],
        image_uri_nobg: savedBgRemovedUris[0] || null,
        image_uris: savedImageUris,
        image_uris_nobg: savedBgRemovedUris,
        category: data.category,
        subcategory: data.subcategories[0] || null,
        subcategories: data.subcategories,
        tags: mergeStructuredTags(data.tags, data.seasons, data.weather, data.occasions),
        brand: data.brand || null,
        color_primary: data.colorPalette[0] ?? '#000000',
        color_secondary: data.colorPalette[1] ?? null,
        color_palette: data.colorPalette,
        size: data.size || null,
      });

      await Promise.all(orphanedFiles.map((uri) => deleteImage(uri)));

      router.replace(`/garment/${id}`);
    } catch (error) {
      console.error(error);
      const message = error instanceof Error ? error.message : String(error);
      Alert.alert(t('addGarment.errors.errorTitle'), `${t('addGarment.errors.saveFailed')}\n\n${message}`);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.loading}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }

  if (!garment) {
    return (
      <View style={styles.loading}>
        <Text>{t('garmentDetail.loading')}</Text>
      </View>
    );
  }

  return (
    <GarmentForm
      t={t}
      colors={colors}
      form={form}
      saving={saving}
      submitLabel={t('addGarment.saveChanges')}
      onSubmit={() => void handleSave()}
    />
  );
}

const styles = StyleSheet.create({
  loading: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: Spacing.lg,
  },
});
