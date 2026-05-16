import React, { useEffect, useRef, useState } from 'react';
import { Alert } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { GarmentForm } from '@/src/components/GarmentForm';
import { DuplicateWarning } from '@/src/components/DuplicateWarning';
import { useGarmentForm } from '@/src/hooks/useGarmentForm';
import { compressAndSaveImage, saveBgRemovedImage } from '@/src/services/image-service';
import { createGarment } from '@/src/services/garment-service';
import { findDuplicates, type DuplicateMatch } from '@/src/services/duplicate-detector';
import {
  importGarmentFromUrl,
  normalizeImportUrl,
  type ImportedGarmentPreview,
} from '@/src/services/url-import-service';
import { mergeStructuredTags } from '@/src/utils/style-tags';
import { useTranslation } from '@/src/i18n';
import { useTheme } from '@/src/theme';

export default function AddGarmentScreen() {
  const params = useLocalSearchParams<{ importUrl?: string | string[] }>();
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  const form = useGarmentForm(t);
  const { applyImportedPreview, data } = form;

  const [productUrl, setProductUrl] = useState('');
  const [showUrlImporter, setShowUrlImporter] = useState(false);
  const [importingFromUrl, setImportingFromUrl] = useState(false);
  const [importPreview, setImportPreview] = useState<ImportedGarmentPreview | null>(null);
  const [saving, setSaving] = useState(false);
  const [duplicates, setDuplicates] = useState<DuplicateMatch[]>([]);
  const [showDuplicateWarning, setShowDuplicateWarning] = useState(false);
  const lastImportedUrlRef = useRef<string | null>(null);

  const importGarmentUrl = async (inputUrl: string) => {
    let normalizedUrl: string;
    try {
      normalizedUrl = normalizeImportUrl(inputUrl);
    } catch (error) {
      const message = error instanceof Error ? error.message : t('addGarment.errors.invalidUrl');
      Alert.alert(t('addGarment.errors.importTitle'), message);
      return;
    }

    setImportingFromUrl(true);
    setShowUrlImporter(true);

    try {
      const preview = await importGarmentFromUrl(normalizedUrl);
      applyImportedPreview(preview);
      setImportPreview(preview);
      setProductUrl(preview.sourceUrl);
      lastImportedUrlRef.current = preview.sourceUrl;
    } catch (error) {
      const message = error instanceof Error ? error.message : t('addGarment.errors.importFailed');
      Alert.alert(t('addGarment.errors.importTitle'), `${t('addGarment.errors.importFailed')}\n\n${message}`);
    } finally {
      setImportingFromUrl(false);
    }
  };

  useEffect(() => {
    const sharedUrl = Array.isArray(params.importUrl) ? params.importUrl[0] : params.importUrl;
    if (!sharedUrl || lastImportedUrlRef.current === sharedUrl) return;
    setProductUrl(sharedUrl);
    void importGarmentUrl(sharedUrl);
  }, [applyImportedPreview, params.importUrl]);

  const saveGarment = async () => {
    if (data.imageUris.length === 0) return;

    setSaving(true);
    try {
      const savedImageUris = await Promise.all(data.imageUris.map((uri) => compressAndSaveImage(uri)));
      const savedBgRemovedUris = await Promise.all(
        data.bgRemovedUris.map(async (uri) => {
          if (!uri) return '';
          try {
            return await saveBgRemovedImage(uri);
          } catch (error) {
            console.warn('Failed to persist background-removed image, saving original only:', error);
            return '';
          }
        })
      );

      await createGarment({
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
        price: null,
        purchase_date: new Date().toISOString().split('T')[0],
      });

      router.back();
    } catch (error) {
      console.error(error);
      const message = error instanceof Error ? error.message : String(error);
      Alert.alert(t('addGarment.errors.errorTitle'), `${t('addGarment.errors.saveFailed')}\n\n${message}`);
    } finally {
      setSaving(false);
    }
  };

  const checkDuplicatesAndSave = async () => {
    if (data.imageUris.length === 0) {
      Alert.alert(t('addGarment.errors.photoRequiredTitle'), t('addGarment.errors.photoRequired'));
      return;
    }

    setSaving(true);

    let matches: DuplicateMatch[] = [];
    try {
      matches = await findDuplicates({
        category: data.category,
        tags: mergeStructuredTags(data.tags, data.seasons, data.weather, data.occasions),
        color_primary: data.colorPalette[0] ?? '#000000',
        color_palette: data.colorPalette,
        size: data.size || null,
      });
    } catch (error) {
      console.warn('Duplicate check failed, continuing with save:', error);
    }

    if (matches.length > 0) {
      setDuplicates(matches);
      setShowDuplicateWarning(true);
      setSaving(false);
      return;
    }

    await saveGarment();
  };

  return (
    <GarmentForm
      t={t}
      colors={colors}
      form={form}
      saving={saving}
      submitLabel={t('addGarment.saveButton')}
      onSubmit={() => void checkDuplicatesAndSave()}
      cancelLabel={t('addGarment.cancelButton')}
      onCancel={() => router.back()}
      urlImport={{
        visible: showUrlImporter,
        onToggle: () => setShowUrlImporter((current) => !current),
        value: productUrl,
        onChangeValue: setProductUrl,
        importing: importingFromUrl,
        onSubmit: () => void importGarmentUrl(productUrl),
        preview: importPreview,
      }}
      footer={(
        <DuplicateWarning
          visible={showDuplicateWarning}
          matches={duplicates}
          onContinue={() => {
            setShowDuplicateWarning(false);
            void saveGarment();
          }}
          onCancel={() => setShowDuplicateWarning(false)}
        />
      )}
    />
  );
}
