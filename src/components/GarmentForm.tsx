import React from 'react';
import {
  View,
  Text,
  ScrollView,
  Pressable,
  TextInput,
  Image,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import { TagInput } from './TagInput';
import { KeyboardAwareScrollView } from './KeyboardAwareScrollView';
import { ColorPicker } from './ColorPicker';
import { CATEGORIES, COMMON_SIZES, SUBCATEGORY_KEY_MAP } from '../constants/categories';
import {
  OCCASION_OPTIONS,
  SEASON_OPTIONS,
  STYLE_FILTER_COLORS,
  STYLE_FILTER_EMOJIS,
  WEATHER_OPTIONS,
} from '../constants/style-filters';
import { BorderRadius, FontSize, Spacing } from '../constants/theme';
import { isGarmentAnalysisAvailable } from '../services/garment-analysis';
import { isBackgroundRemovalAvailable } from '../services/background-removal';
import type { ThemeColors } from '../theme';
import type { ImportedGarmentPreview } from '../services/url-import-service';
import type { GarmentFormData, useGarmentForm } from '../hooks/useGarmentForm';

type Translate = (key: string, options?: Record<string, unknown>) => string;
type FormController = ReturnType<typeof useGarmentForm>;

interface UrlImportControls {
  visible: boolean;
  onToggle: () => void;
  value: string;
  onChangeValue: (value: string) => void;
  importing: boolean;
  onSubmit: () => void;
  preview: ImportedGarmentPreview | null;
}

interface GarmentFormProps {
  t: Translate;
  colors: ThemeColors;
  form: FormController;
  saving: boolean;
  submitLabel: string;
  onSubmit: () => void;
  cancelLabel?: string;
  onCancel?: () => void;
  urlImport?: UrlImportControls;
  footer?: React.ReactNode;
}

export function GarmentForm({
  t,
  colors,
  form,
  saving,
  submitLabel,
  onSubmit,
  cancelLabel,
  onCancel,
  urlImport,
  footer,
}: GarmentFormProps) {
  const styles = createStyles(colors);

  const renderUrlImport = () => {
    if (!urlImport) return null;

    return (
      <>
        <Pressable
          style={[styles.importButton, urlImport.visible && styles.importButtonActive]}
          onPress={urlImport.onToggle}
        >
          <Text style={styles.importButtonText}>{t('addGarment.importFromUrl')}</Text>
        </Pressable>
        {urlImport.visible && (
          <View style={styles.importCard}>
            <Text style={styles.importHint}>{t('addGarment.importUrlCta')}</Text>
            <TextInput
              style={styles.input}
              value={urlImport.value}
              onChangeText={urlImport.onChangeValue}
              placeholder={t('addGarment.placeholders.productUrl')}
              placeholderTextColor={colors.textTertiary}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
            />
            <Pressable
              style={[styles.importSubmitButton, urlImport.importing && styles.saveButtonDisabled]}
              onPress={urlImport.onSubmit}
              disabled={urlImport.importing}
            >
              {urlImport.importing ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.importSubmitButtonText}>{t('addGarment.importUrlButton')}</Text>
              )}
            </Pressable>
            {urlImport.preview && (
              <View style={styles.importResultCard}>
                <Text style={styles.importResultTitle}>
                  {urlImport.preview.title || t('addGarment.importUrlButton')}
                </Text>
                <Text style={styles.importResultMeta}>
                  {t('addGarment.importUrlSource', {
                    brand: urlImport.preview.brand || new URL(urlImport.preview.sourceUrl).hostname,
                  })}
                </Text>
                <Text style={styles.importResultMeta}>
                  {t('addGarment.importUrlSuccess', { count: urlImport.preview.downloadedImageUris.length })}
                </Text>
                {urlImport.preview.warnings.length > 0 && (
                  <Text style={styles.importWarningText}>
                    {t('addGarment.importUrlWarnings', { warnings: urlImport.preview.warnings.join(' ') })}
                  </Text>
                )}
              </View>
            )}
          </View>
        )}
      </>
    );
  };

  return (
    <KeyboardAwareScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.label}>{t('addGarment.labels.photos')}</Text>
      <View style={styles.imageSection}>
        {form.displayedPreviewUri ? (
          <View style={styles.fullWidthCentered}>
            <Pressable onPress={() => void form.pickImage(true)}>
              <Image source={{ uri: form.displayedPreviewUri }} style={styles.preview} resizeMode="cover" />
              <Text style={styles.changePhotoText}>{t('addGarment.tapToChange')}</Text>
            </Pressable>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.thumbnailRow}>
              {form.galleryItems.map((item, index) => (
                <View key={`${item.original}-${index}`} style={styles.thumbnailWrap}>
                  <Pressable
                    style={[styles.thumbnailButton, form.selectedImageIndex === index && styles.thumbnailButtonActive]}
                    onPress={() => form.setSelectedImageIndex(index)}
                  >
                    <Image source={{ uri: item.uri }} style={styles.thumbnailImage} resizeMode="cover" />
                  </Pressable>
                  <View style={styles.thumbnailActions}>
                    {index !== 0 && (
                      <Pressable onPress={() => form.reorderImages(index, 0)}>
                        <Text style={styles.thumbnailActionText}>{t('addGarment.useAsCover')}</Text>
                      </Pressable>
                    )}
                    {form.data.imageUris.length > 1 && (
                      <Pressable onPress={() => form.removeImageAt(index)}>
                        <Text style={[styles.thumbnailActionText, styles.removeText]}>{t('addGarment.removePhoto')}</Text>
                      </Pressable>
                    )}
                  </View>
                </View>
              ))}
            </ScrollView>
            <View style={styles.photoActionRow}>
              <Pressable style={styles.imageButton} onPress={() => void form.takePhoto(false)}>
                <Text style={styles.imageButtonText}>{t('addGarment.takePhoto')}</Text>
              </Pressable>
              <Pressable style={[styles.imageButton, styles.imageButtonSecondary]} onPress={() => void form.pickImage(false)}>
                <Text style={[styles.imageButtonText, styles.imageButtonTextSecondary]}>{t('addGarment.addAnotherPhoto')}</Text>
              </Pressable>
            </View>
            {renderUrlImport()}
            {!form.currentBgRemovedUri && (
              <Pressable
                style={[styles.bgRemoveButton, (form.removingBg || !isBackgroundRemovalAvailable()) && styles.dimmed]}
                onPress={() => void form.removeCurrentBackground()}
                disabled={form.removingBg || !isBackgroundRemovalAvailable()}
              >
                {form.removingBg ? (
                  <View style={styles.bgProgressWrap}>
                    <View style={styles.bgProgressHeader}>
                      <ActivityIndicator color={colors.primary} size="small" />
                      <Text style={styles.bgProgressText}>{form.bgProgress}%</Text>
                    </View>
                    <View style={styles.bgProgressTrack}>
                      <View style={[styles.bgProgressFill, { width: `${form.bgProgress}%` }]} />
                    </View>
                  </View>
                ) : (
                  <Text style={styles.bgRemoveText}>{t('addGarment.removeBackground')}</Text>
                )}
              </Pressable>
            )}
            {isGarmentAnalysisAvailable() && (
              <Pressable
                style={[styles.analysisButton, (form.analyzingImage || !form.currentImageUri) && styles.dimmed]}
                onPress={() => {
                  if (!form.currentImageUri || form.analyzingImage) return;
                  void form.applyImageSuggestions(form.currentImageUri);
                }}
                disabled={form.analyzingImage || !form.currentImageUri}
              >
                <Text style={styles.analysisButtonText}>{t('addGarment.analyzePhoto')}</Text>
              </Pressable>
            )}
            {form.currentBgRemovedUri && (
              <Pressable onPress={form.undoCurrentBackground}>
                <Text style={[styles.changePhotoText, { color: colors.error }]}>{t('addGarment.undoBackground')}</Text>
              </Pressable>
            )}
            {form.analyzingImage && (
              <View style={styles.analysisProgressWrap}>
                <Text style={styles.analysisText}>{`${t('addGarment.analyzingPhoto')} ${form.analyzeProgress}%`}</Text>
                <View style={styles.analysisProgressTrack}>
                  <View style={[styles.analysisProgressFill, { width: `${form.analyzeProgress}%` }]} />
                </View>
              </View>
            )}
          </View>
        ) : (
          <View style={styles.imagePlaceholder}>
            <Pressable style={styles.imageButton} onPress={() => void form.takePhoto(false)}>
              <Text style={styles.imageButtonText}>{t('addGarment.takePhoto')}</Text>
            </Pressable>
            <Pressable style={[styles.imageButton, styles.imageButtonSecondary]} onPress={() => void form.pickImage(false)}>
              <Text style={[styles.imageButtonText, styles.imageButtonTextSecondary]}>{t('addGarment.chooseGallery')}</Text>
            </Pressable>
            {renderUrlImport()}
          </View>
        )}
      </View>

      <Text style={styles.label}>{t('addGarment.labels.category')}</Text>
      <View style={styles.chipWrapRow}>
        {Object.keys(CATEGORIES).map((key) => (
          <Pressable
            key={key}
            style={[styles.chip, form.data.category === key && styles.chipActive]}
            onPress={() => {
              form.setCategory(key);
              form.setSubcategories([]);
            }}
          >
            <Text style={[styles.chipText, form.data.category === key && styles.chipTextActive]}>
              {t(`categories.${key}`)}
            </Text>
          </Pressable>
        ))}
      </View>

      {form.categoryData && (
        <>
          <Text style={styles.label}>{t('addGarment.labels.type')}</Text>
          <View style={styles.chipWrapRow}>
            {form.categoryData.subcategories.map((sub) => (
              <Pressable
                key={sub}
                style={[styles.chip, form.data.subcategories.includes(sub) && styles.chipActive]}
                onPress={() => form.setSubcategories(form.toggleArrayValue(form.data.subcategories, sub))}
              >
                <Text style={[styles.chipText, form.data.subcategories.includes(sub) && styles.chipTextActive]}>
                  {t(`subcategories.${SUBCATEGORY_KEY_MAP[sub]}`)}
                </Text>
              </Pressable>
            ))}
          </View>
        </>
      )}

      <Text style={styles.label}>{t('addGarment.labels.tags')}</Text>
      <TagInput tags={form.data.tags} onTagsChange={form.setTags} />

      <Text style={styles.label}>{t('addGarment.labels.season')}</Text>
      <View style={styles.chipWrapRow}>
        {SEASON_OPTIONS.map((option) => (
          <Pressable
            key={option}
            style={[
              styles.chip,
              styles.filterChip,
              { backgroundColor: `${STYLE_FILTER_COLORS[option]}22`, borderColor: `${STYLE_FILTER_COLORS[option]}66` },
              form.data.seasons.includes(option) && [
                styles.chipActive,
                { backgroundColor: STYLE_FILTER_COLORS[option], borderColor: STYLE_FILTER_COLORS[option] },
              ],
            ]}
            onPress={() => form.setSeasons(form.toggleArrayValue(form.data.seasons, option))}
          >
            <Text style={[styles.chipText, form.data.seasons.includes(option) && styles.chipTextActive]}>
              {`${STYLE_FILTER_EMOJIS[option]} ${t(`outfits.filterValues.season.${option}`)}`}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.label}>{t('addGarment.labels.weather')}</Text>
      <View style={styles.chipWrapRow}>
        {WEATHER_OPTIONS.map((option) => (
          <Pressable
            key={option}
            style={[
              styles.chip,
              styles.filterChip,
              { backgroundColor: `${STYLE_FILTER_COLORS[option]}22`, borderColor: `${STYLE_FILTER_COLORS[option]}66` },
              form.data.weather.includes(option) && [
                styles.chipActive,
                { backgroundColor: STYLE_FILTER_COLORS[option], borderColor: STYLE_FILTER_COLORS[option] },
              ],
            ]}
            onPress={() => form.setWeather(form.toggleArrayValue(form.data.weather, option))}
          >
            <Text style={[styles.chipText, form.data.weather.includes(option) && styles.chipTextActive]}>
              {`${STYLE_FILTER_EMOJIS[option]} ${t(`outfits.filterValues.weather.${option}`)}`}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.label}>{t('addGarment.labels.occasion')}</Text>
      <View style={styles.chipWrapRow}>
        {OCCASION_OPTIONS.map((option) => (
          <Pressable
            key={option}
            style={[
              styles.chip,
              styles.filterChip,
              { backgroundColor: `${STYLE_FILTER_COLORS[option]}22`, borderColor: `${STYLE_FILTER_COLORS[option]}66` },
              form.data.occasions.includes(option) && [
                styles.chipActive,
                { backgroundColor: STYLE_FILTER_COLORS[option], borderColor: STYLE_FILTER_COLORS[option] },
              ],
            ]}
            onPress={() => form.setOccasions(form.toggleArrayValue(form.data.occasions, option))}
          >
            <Text style={[styles.chipText, form.data.occasions.includes(option) && styles.chipTextActive]}>
              {`${STYLE_FILTER_EMOJIS[option]} ${t(`outfits.filterValues.occasion.${option}`)}`}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.label}>{t('addGarment.labels.colors')}</Text>
      <ColorPicker
        selected={form.data.colorPalette}
        onToggle={(hex) => form.setColorPalette((current) => {
          const next = form.toggleArrayValue(current, hex);
          return next.length > 0 ? next : ['#000000'];
        })}
      />

      <Text style={styles.label}>{t('addGarment.labels.brand')}</Text>
      <TextInput
        style={styles.input}
        value={form.data.brand}
        onChangeText={(text) => {
          form.setBrand(text);
          form.setShowBrandSuggestions(true);
        }}
        onFocus={() => form.setShowBrandSuggestions(true)}
        onBlur={() => setTimeout(() => form.setShowBrandSuggestions(false), 150)}
        placeholder={t('addGarment.placeholders.brand')}
        placeholderTextColor={colors.textTertiary}
      />
      {form.showBrandSuggestions && form.filteredBrandSuggestions.length > 0 && (
        <View style={styles.brandSuggestionsRow}>
          {form.filteredBrandSuggestions.map((existing) => (
            <Pressable
              key={existing}
              style={styles.brandSuggestionChip}
              onPressIn={() => form.setBrand(existing)}
              onPress={() => {
                form.setBrand(existing);
                form.setShowBrandSuggestions(false);
              }}
            >
              <Text style={styles.brandSuggestionText}>{existing}</Text>
            </Pressable>
          ))}
        </View>
      )}

      <Text style={styles.label}>{t('addGarment.labels.size')}</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chipRow}>
        {COMMON_SIZES.slice(0, 12).map((entry) => (
          <Pressable
            key={entry}
            style={[styles.chip, styles.chipSmall, form.data.size === entry && styles.chipActive]}
            onPress={() => form.setSize(form.data.size === entry ? '' : entry)}
          >
            <Text style={[styles.chipText, form.data.size === entry && styles.chipTextActive]}>{entry}</Text>
          </Pressable>
        ))}
      </ScrollView>
      <TextInput
        style={[styles.input, { marginTop: Spacing.xs }]}
        value={form.data.size}
        onChangeText={form.setSize}
        placeholder={t('addGarment.placeholders.customSize')}
        placeholderTextColor={colors.textTertiary}
      />

      {onCancel && cancelLabel && (
        <Pressable style={styles.cancelButton} onPress={onCancel} disabled={saving}>
          <Text style={styles.cancelButtonText}>{cancelLabel}</Text>
        </Pressable>
      )}
      <Pressable style={[styles.saveButton, saving && styles.saveButtonDisabled]} onPress={onSubmit} disabled={saving}>
        {saving ? <ActivityIndicator color="#fff" /> : <Text style={styles.saveButtonText}>{submitLabel}</Text>}
      </Pressable>
      {footer}
    </KeyboardAwareScrollView>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg, paddingBottom: Spacing.xxl * 2 },
  fullWidthCentered: { alignItems: 'center', width: '100%' },
  imageSection: { marginBottom: Spacing.lg, alignItems: 'center' },
  preview: { width: 200, height: 267, borderRadius: BorderRadius.lg },
  thumbnailRow: { paddingTop: Spacing.md, paddingBottom: Spacing.xs },
  thumbnailWrap: { width: 92, marginRight: Spacing.sm },
  thumbnailButton: {
    borderRadius: BorderRadius.md,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  thumbnailButtonActive: { borderColor: colors.primary },
  thumbnailImage: { width: 88, height: 112, backgroundColor: colors.surfaceVariant },
  thumbnailActions: { marginTop: Spacing.xs, gap: 4 },
  thumbnailActionText: {
    textAlign: 'center',
    color: colors.primary,
    fontSize: FontSize.xs,
    fontWeight: '600',
  },
  removeText: { color: colors.error },
  changePhotoText: {
    textAlign: 'center',
    color: colors.primary,
    fontSize: FontSize.sm,
    marginTop: Spacing.xs,
  },
  photoActionRow: { flexDirection: 'row', gap: Spacing.sm, marginTop: Spacing.sm },
  importButton: {
    marginTop: Spacing.sm,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: BorderRadius.md,
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.sm,
    backgroundColor: colors.surface,
  },
  importButtonActive: { borderColor: colors.primary },
  importButtonText: { color: colors.text, fontSize: FontSize.sm, fontWeight: '600' },
  importCard: {
    width: '100%',
    marginTop: Spacing.sm,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    gap: Spacing.sm,
  },
  importHint: { color: colors.textSecondary, fontSize: FontSize.sm },
  importSubmitButton: {
    backgroundColor: colors.primary,
    borderRadius: BorderRadius.md,
    paddingVertical: Spacing.sm,
    alignItems: 'center',
  },
  importSubmitButtonText: { color: '#fff', fontSize: FontSize.sm, fontWeight: '700' },
  importResultCard: {
    borderRadius: BorderRadius.md,
    backgroundColor: colors.surfaceVariant,
    padding: Spacing.sm,
    gap: 4,
  },
  importResultTitle: { color: colors.text, fontSize: FontSize.md, fontWeight: '700' },
  importResultMeta: { color: colors.textSecondary, fontSize: FontSize.sm },
  importWarningText: { color: colors.textTertiary, fontSize: FontSize.xs },
  analysisText: { marginTop: Spacing.xs, color: colors.textSecondary, fontSize: FontSize.xs },
  analysisProgressWrap: { marginTop: Spacing.xs, width: 190 },
  analysisProgressTrack: {
    marginTop: Spacing.xs,
    height: 6,
    backgroundColor: colors.surfaceVariant,
    borderRadius: BorderRadius.full,
    overflow: 'hidden',
  },
  analysisProgressFill: { height: '100%', backgroundColor: colors.primary },
  analysisButton: {
    marginTop: Spacing.sm,
    borderWidth: 1,
    borderColor: colors.primary,
    borderRadius: BorderRadius.md,
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.sm,
    backgroundColor: colors.surface,
  },
  analysisButtonText: { color: colors.primary, fontSize: FontSize.sm, fontWeight: '600' },
  bgRemoveButton: {
    marginTop: Spacing.sm,
    borderWidth: 1,
    borderColor: colors.primary,
    borderRadius: BorderRadius.md,
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.sm,
  },
  bgRemoveText: { color: colors.primary, fontSize: FontSize.sm, fontWeight: '600' },
  bgProgressWrap: { width: 170 },
  bgProgressHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  bgProgressText: { color: colors.primary, fontSize: FontSize.xs, fontWeight: '700' },
  bgProgressTrack: {
    marginTop: Spacing.xs,
    height: 6,
    backgroundColor: colors.surfaceVariant,
    borderRadius: BorderRadius.full,
    overflow: 'hidden',
  },
  bgProgressFill: { height: '100%', backgroundColor: colors.primary },
  imagePlaceholder: {
    width: '100%',
    minHeight: 200,
    backgroundColor: colors.surfaceVariant,
    borderRadius: BorderRadius.lg,
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 2,
    borderColor: colors.border,
    borderStyle: 'dashed',
    gap: Spacing.sm,
    paddingVertical: Spacing.lg,
    paddingHorizontal: Spacing.md,
  },
  imageButton: {
    backgroundColor: colors.primary,
    borderRadius: BorderRadius.md,
    paddingHorizontal: Spacing.lg,
    paddingVertical: Spacing.sm,
  },
  imageButtonSecondary: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.primary },
  imageButtonText: { color: '#fff', fontSize: FontSize.md, fontWeight: '600' },
  imageButtonTextSecondary: { color: colors.primary },
  label: {
    fontSize: FontSize.sm,
    fontWeight: '700',
    color: colors.text,
    marginTop: Spacing.lg,
    marginBottom: Spacing.sm,
  },
  chipRow: { maxHeight: 44 },
  chipWrapRow: { flexDirection: 'row', flexWrap: 'wrap' },
  filterChip: { borderWidth: 1 },
  chip: {
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.xs,
    borderRadius: BorderRadius.full,
    backgroundColor: colors.surfaceVariant,
    marginRight: Spacing.xs,
    marginBottom: Spacing.xs,
  },
  chipSmall: { paddingHorizontal: Spacing.sm },
  chipActive: { backgroundColor: colors.primary },
  chipText: { fontSize: FontSize.sm, color: colors.textSecondary },
  chipTextActive: { color: '#fff', fontWeight: '600' },
  input: {
    backgroundColor: colors.surface,
    borderRadius: BorderRadius.sm,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm,
    fontSize: FontSize.md,
    color: colors.text,
    borderWidth: 1,
    borderColor: colors.border,
  },
  brandSuggestionsRow: { flexDirection: 'row', flexWrap: 'wrap', marginTop: Spacing.xs },
  brandSuggestionChip: {
    backgroundColor: colors.surfaceVariant,
    borderRadius: BorderRadius.full,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.xs,
    marginRight: Spacing.xs,
    marginBottom: Spacing.xs,
    borderWidth: 1,
    borderColor: colors.border,
  },
  brandSuggestionText: { fontSize: FontSize.sm, color: colors.primary },
  saveButton: {
    backgroundColor: colors.primary,
    borderRadius: BorderRadius.md,
    padding: Spacing.md,
    alignItems: 'center',
    marginTop: Spacing.md,
  },
  cancelButton: {
    backgroundColor: 'transparent',
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: Spacing.md,
    alignItems: 'center',
    marginTop: Spacing.xl,
  },
  cancelButtonText: { color: colors.textSecondary, fontSize: FontSize.md, fontWeight: '600' },
  saveButtonDisabled: { opacity: 0.6 },
  saveButtonText: { color: '#fff', fontSize: FontSize.lg, fontWeight: '700' },
  dimmed: { opacity: 0.6 },
});
