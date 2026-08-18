import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { getExistingBrands } from '../services/garment-service';
import {
  detectDominantColor,
  getSeasonsForSubcategories,
  isGarmentAnalysisAvailable,
} from '../services/garment-analysis';
import {
  isBackgroundRemovalAvailable,
  removeImageBackground,
  type BackgroundRemovalProgress,
} from '../services/background-removal';
import type { ImportedGarmentPreview } from '../services/url-import-service';
import type { Garment } from '../types';
import type { SeasonOption } from '../constants/style-filters';
import { CATEGORIES } from '../constants/categories';
import { splitStructuredTags } from '../utils/style-tags';

type Translate = (key: string, options?: Record<string, unknown>) => string;

export interface GarmentFormData {
  imageUris: string[];
  bgRemovedUris: string[];
  category: string;
  subcategories: string[];
  tags: string[];
  seasons: SeasonOption[];
  brand: string;
  colorPalette: string[];
  size: string;
}

const DEFAULT_FORM_DATA: GarmentFormData = {
  imageUris: [],
  bgRemovedUris: [],
  category: 'tops',
  subcategories: [],
  tags: [],
  seasons: [],
  brand: '',
  colorPalette: ['#000000'],
  size: '',
};

function normalizeBgRemovedUris(imageUris: string[], bgRemovedUris?: string[]): string[] {
  return imageUris.map((_, index) => bgRemovedUris?.[index] ?? '');
}

function normalizeFormData(data?: Partial<GarmentFormData>): GarmentFormData {
  const imageUris = data?.imageUris ?? DEFAULT_FORM_DATA.imageUris;
  const colorPalette = data?.colorPalette?.length ? data.colorPalette : DEFAULT_FORM_DATA.colorPalette;

  return {
    imageUris,
    bgRemovedUris: normalizeBgRemovedUris(imageUris, data?.bgRemovedUris),
    category: data?.category ?? DEFAULT_FORM_DATA.category,
    subcategories: data?.subcategories ?? DEFAULT_FORM_DATA.subcategories,
    tags: data?.tags ?? DEFAULT_FORM_DATA.tags,
    seasons: data?.seasons ?? DEFAULT_FORM_DATA.seasons,
    brand: data?.brand ?? DEFAULT_FORM_DATA.brand,
    colorPalette,
    size: data?.size ?? DEFAULT_FORM_DATA.size,
  };
}

export function garmentToFormData(garment: Garment): GarmentFormData {
  const structured = splitStructuredTags(garment.tags);

  return normalizeFormData({
    imageUris: garment.image_uris,
    bgRemovedUris:
      garment.image_uris_nobg.length > 0
        ? garment.image_uris_nobg
        : garment.image_uris.map((_, index) => (index === 0 ? garment.image_uri_nobg ?? '' : '')),
    category: garment.category,
    subcategories: garment.subcategories,
    tags: structured.customTags,
    seasons: structured.seasons,
    brand: garment.brand ?? '',
    colorPalette: garment.color_palette,
    size: garment.size ?? '',
  });
}

export function useGarmentForm(t: Translate, initialData?: Partial<GarmentFormData>) {
  const normalizedInitialData = useMemo(() => normalizeFormData(initialData), [initialData]);
  const [imageUris, setImageUris] = useState<string[]>(normalizedInitialData.imageUris);
  const [bgRemovedUris, setBgRemovedUris] = useState<string[]>(normalizedInitialData.bgRemovedUris);
  const [selectedImageIndex, setSelectedImageIndex] = useState(0);
  const [category, setCategory] = useState<string>(normalizedInitialData.category);
  const [subcategories, setSubcategories] = useState<string[]>(normalizedInitialData.subcategories);
  const [tags, setTags] = useState<string[]>(normalizedInitialData.tags);
  const [seasons, setSeasons] = useState<SeasonOption[]>(normalizedInitialData.seasons);
  const [brand, setBrand] = useState(normalizedInitialData.brand);
  const [brandSuggestions, setBrandSuggestions] = useState<string[]>([]);
  const [showBrandSuggestions, setShowBrandSuggestions] = useState(false);
  const [colorPalette, setColorPalette] = useState<string[]>(normalizedInitialData.colorPalette);
  const [size, setSize] = useState(normalizedInitialData.size);
  const [removingBg, setRemovingBg] = useState(false);
  const [bgProgress, setBgProgress] = useState(0);
  const [analyzingImage, setAnalyzingImage] = useState(false);
  const [analyzeProgress, setAnalyzeProgress] = useState(0);

  const bgProgressRef = useRef(0);
  const bgProgressTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const animateBgProgressTo = useCallback((target: number, durationMs = 420) => new Promise<void>((resolve) => {
    if (bgProgressTimerRef.current) clearInterval(bgProgressTimerRef.current);
    const start = bgProgressRef.current;
    const distance = target - start;
    if (distance <= 0) {
      resolve();
      return;
    }
    const tickMs = 30;
    const steps = Math.max(8, Math.round(durationMs / tickMs));
    let step = 0;
    bgProgressTimerRef.current = setInterval(() => {
      step += 1;
      const next = Math.min(target, Math.round(start + (distance * step) / steps));
      bgProgressRef.current = next;
      setBgProgress(next);
      if (next >= target || step >= steps) {
        if (bgProgressTimerRef.current) {
          clearInterval(bgProgressTimerRef.current);
          bgProgressTimerRef.current = null;
        }
        resolve();
      }
    }, tickMs);
  }), []);

  useEffect(() => {
    bgProgressRef.current = bgProgress;
  }, [bgProgress]);

  useEffect(() => {
    const loadBrands = async () => {
      try {
        setBrandSuggestions(await getExistingBrands());
      } catch (error) {
        console.warn('Failed to load brand suggestions:', error);
      }
    };

    loadBrands();

    return () => {
      if (bgProgressTimerRef.current) clearInterval(bgProgressTimerRef.current);
    };
  }, []);

  const toggleArrayValue = useCallback(<T extends string>(values: T[], value: T) =>
    values.includes(value) ? values.filter((item) => item !== value) : [...values, value], []);

  const replaceData = useCallback((nextData: Partial<GarmentFormData>) => {
    const normalized = normalizeFormData(nextData);
    setImageUris(normalized.imageUris);
    setBgRemovedUris(normalized.bgRemovedUris);
    setSelectedImageIndex(0);
    setCategory(normalized.category);
    setSubcategories(normalized.subcategories);
    setTags(normalized.tags);
    setSeasons(normalized.seasons);
    setBrand(normalized.brand);
    setColorPalette(normalized.colorPalette);
    setSize(normalized.size);
  }, []);

  const applyImportedPreview = useCallback((preview: ImportedGarmentPreview) => {
    setImageUris(preview.downloadedImageUris);
    setBgRemovedUris(preview.downloadedImageUris.map(() => ''));
    setSelectedImageIndex(0);
    setBrand((current) => (current.trim() ? current : (preview.brand ?? '')));
  }, []);

  const reorderImages = useCallback((fromIndex: number, toIndex: number) => {
    if (fromIndex === toIndex) return;

    setImageUris((current) => {
      const next = [...current];
      const [movedImage] = next.splice(fromIndex, 1);
      next.splice(toIndex, 0, movedImage);
      return next;
    });
    setBgRemovedUris((current) => {
      const next = [...current];
      const [movedImage] = next.splice(fromIndex, 1);
      next.splice(toIndex, 0, movedImage);
      return next;
    });
    setSelectedImageIndex(toIndex);
  }, []);

  const removeImageAt = useCallback((index: number) => {
    setImageUris((current) => {
      const next = current.filter((_, currentIndex) => currentIndex !== index);
      setSelectedImageIndex((selected) => {
        if (next.length === 0) return 0;
        if (selected > index) return selected - 1;
        return Math.min(selected, next.length - 1);
      });
      return next;
    });
    setBgRemovedUris((current) => current.filter((_, currentIndex) => currentIndex !== index));
  }, []);

  const setOrAppendImage = useCallback((uri: string, replaceCurrent = false) => {
    if (replaceCurrent && imageUris[selectedImageIndex]) {
      setImageUris((current) => current.map((item, index) => (index === selectedImageIndex ? uri : item)));
      setBgRemovedUris((current) => current.map((item, index) => (index === selectedImageIndex ? '' : item)));
      return;
    }

    const nextIndex = imageUris.length;
    setImageUris((current) => [...current, uri]);
    setBgRemovedUris((current) => [...current, '']);
    setSelectedImageIndex(nextIndex);
  }, [imageUris, selectedImageIndex]);

  const applyImageSuggestions = useCallback(async (uri: string) => {
    if (!isGarmentAnalysisAvailable()) return;
    setAnalyzingImage(true);
    setAnalyzeProgress(2);

    try {
      const detectedColor = await detectDominantColor(uri, setAnalyzeProgress);
      if (!detectedColor) return;

      // Move the detected colour to the front rather than replacing the
      // selection, so anything the user already picked is preserved.
      setColorPalette(current =>
        current.length > 0
          ? [detectedColor, ...current.filter(color => color !== detectedColor)]
          : [detectedColor]
      );
    } finally {
      setAnalyzingImage(false);
      setTimeout(() => setAnalyzeProgress(0), 250);
    }
  }, []);

  /**
   * Choosing a garment type implies seasons (a blazer is not summerwear), so
   * they are filled in automatically -- but only while the user has not chosen
   * seasons themselves, so an explicit choice is never overwritten.
   */
  const applySubcategories = useCallback((next: string[]) => {
    setSubcategories(next);
    setSeasons(current => {
      if (current.length > 0) return current;
      return getSeasonsForSubcategories(next);
    });
  }, []);

  const pickImage = useCallback(async (replaceCurrent = false) => {
    const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert(t('addGarment.errors.permissionTitle'), t('addGarment.errors.permissionPhotos'));
      return;
    }

    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsEditing: true,
      aspect: [3, 4],
      quality: 0.8,
    });

    if (!result.canceled && result.assets[0]) {
      setOrAppendImage(result.assets[0].uri, replaceCurrent);
    }
  }, [setOrAppendImage, t]);

  const takePhoto = useCallback(async (replaceCurrent = false) => {
    const { status } = await ImagePicker.requestCameraPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert(t('addGarment.errors.permissionTitle'), t('addGarment.errors.permissionCamera'));
      return;
    }

    const result = await ImagePicker.launchCameraAsync({
      allowsEditing: true,
      aspect: [3, 4],
      quality: 0.8,
    });

    if (!result.canceled && result.assets[0]) {
      setOrAppendImage(result.assets[0].uri, replaceCurrent);
    }
  }, [setOrAppendImage, t]);

  const removeCurrentBackground = useCallback(async () => {
    const currentImageUri = imageUris[selectedImageIndex];
    if (removingBg || !currentImageUri) return;

    if (!isBackgroundRemovalAvailable()) {
      Alert.alert(t('addGarment.bgRemoval.errorTitle'), t('addGarment.bgRemoval.unavailable'));
      return;
    }

    setRemovingBg(true);
    setBgProgress(1);
    bgProgressRef.current = 1;
    if (bgProgressTimerRef.current) clearInterval(bgProgressTimerRef.current);
    bgProgressTimerRef.current = setInterval(() => {
      setBgProgress((prev) => {
        if (prev >= 92) return prev;
        if (prev < 30) return prev + 3;
        if (prev < 60) return prev + 2;
        return prev + 1;
      });
    }, 120);

    try {
      const result = await removeImageBackground(currentImageUri, (progress: BackgroundRemovalProgress) => {
        setBgProgress((prev) => Math.max(prev, Math.min(95, progress.percent)));
      });
      await animateBgProgressTo(100, 480);
      setBgRemovedUris((current) => current.map((item, index) => (index === selectedImageIndex ? result : item)));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      Alert.alert(t('addGarment.bgRemoval.errorTitle'), `${t('addGarment.bgRemoval.failed')}\n\n${message}`);
    } finally {
      if (bgProgressTimerRef.current) {
        clearInterval(bgProgressTimerRef.current);
        bgProgressTimerRef.current = null;
      }
      setRemovingBg(false);
      setTimeout(() => setBgProgress(0), 250);
    }
  }, [animateBgProgressTo, imageUris, removingBg, selectedImageIndex, t]);

  const undoCurrentBackground = useCallback(() => {
    setBgRemovedUris((current) => current.map((item, index) => (index === selectedImageIndex ? '' : item)));
  }, [selectedImageIndex]);

  const normalizedBrand = brand.trim().toLowerCase();
  const filteredBrandSuggestions = useMemo(() => brandSuggestions
    .filter((existing) => (normalizedBrand ? existing.toLowerCase().includes(normalizedBrand) : true))
    .filter((existing) => existing.toLowerCase() !== normalizedBrand)
    .slice(0, 8), [brandSuggestions, normalizedBrand]);

  return {
    data: {
      imageUris,
      bgRemovedUris,
      category,
      subcategories,
      tags,
      seasons,
      brand,
      colorPalette,
      size,
    } satisfies GarmentFormData,
    categoryData: CATEGORIES[category as keyof typeof CATEGORIES],
    galleryItems: imageUris.map((uri, index) => ({ uri: bgRemovedUris[index] || uri, original: uri })),
    selectedImageIndex,
    currentImageUri: imageUris[selectedImageIndex] ?? null,
    currentBgRemovedUri: bgRemovedUris[selectedImageIndex] ?? null,
    // A separate with-background original exists only when the slot's image
    // differs from its cut-out. For cut-out-only garments the two are the same
    // path, so background removal can't be undone/re-run.
    currentHasOriginal:
      Boolean(imageUris[selectedImageIndex]) &&
      imageUris[selectedImageIndex] !== bgRemovedUris[selectedImageIndex],
    displayedPreviewUri: bgRemovedUris[selectedImageIndex] || imageUris[selectedImageIndex] || null,
    showBrandSuggestions,
    setShowBrandSuggestions,
    filteredBrandSuggestions,
    removingBg,
    bgProgress,
    analyzingImage,
    analyzeProgress,
    setSelectedImageIndex,
    setCategory,
    setSubcategories: applySubcategories,
    setTags,
    setSeasons,
    setBrand,
    setColorPalette,
    setSize,
    toggleArrayValue,
    replaceData,
    applyImportedPreview,
    reorderImages,
    removeImageAt,
    pickImage,
    takePhoto,
    applyImageSuggestions,
    removeCurrentBackground,
    undoCurrentBackground,
  };
}
