import React from 'react';
import { View, Text, Image, StyleSheet, Pressable } from 'react-native';
import { Spacing, BorderRadius, FontSize } from '../constants/theme';
import { useTranslation } from '../i18n';
import { localizeSubcategories } from '../utils/localization-helpers';
import type { Garment } from '../types';
import { getGarmentColorPalette, getGarmentDisplayImage } from '../utils/garment-fields';
import { useTheme } from '../theme';
import type { ThemeColors } from '../theme';

interface GarmentCardProps {
  garment: Garment;
  onPress: (garment: Garment) => void;
  compact?: boolean;
}

export function GarmentCard({ garment, onPress, compact }: GarmentCardProps) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const garmentColors = getGarmentColorPalette(garment).slice(0, 4);
  const localizedSubcategories = localizeSubcategories(garment.subcategories, t);

  return (
    <Pressable
      style={({ pressed }) => [styles.container, compact && styles.containerCompact, pressed && styles.pressed]}
      onPress={() => onPress(garment)}
    >
      <Image
        source={{ uri: getGarmentDisplayImage(garment) }}
        style={[styles.image, compact && styles.imageCompact]}
        resizeMode="cover"
      />
      {!garment.is_available && (
        <View style={styles.unavailableBadge}>
          <Text style={styles.unavailableText}>{t('garmentCard.unavailable')}</Text>
        </View>
      )}
      <View style={styles.info}>
        <Text style={styles.category} numberOfLines={1}>{t(`categories.${garment.category}`)}</Text>
        {localizedSubcategories.length > 0 && (
          <Text style={styles.subcategory} numberOfLines={2}>{localizedSubcategories.join(', ')}</Text>
        )}
        {!compact && garment.brand && <Text style={styles.brand} numberOfLines={1}>{garment.brand}</Text>}
        {!compact && (
          <View style={styles.colorRow}>
            {garmentColors.map(color => (
              <View key={color} style={[styles.colorDot, { backgroundColor: color }]} />
            ))}
          </View>
        )}
      </View>
    </Pressable>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: {
    backgroundColor: colors.surface, borderRadius: BorderRadius.md, overflow: 'hidden',
    elevation: 2, shadowColor: colors.shadow, shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1, shadowRadius: 4, flex: 1, margin: Spacing.xs,
  },
  containerCompact: { width: 100, flex: 0 },
  pressed: { opacity: 0.8, transform: [{ scale: 0.98 }] },
  image: { width: '100%', aspectRatio: 3 / 4 },
  imageCompact: { aspectRatio: 1 },
  unavailableBadge: { position: 'absolute', top: Spacing.xs, right: Spacing.xs, backgroundColor: colors.error, borderRadius: BorderRadius.sm, paddingHorizontal: Spacing.sm, paddingVertical: 2 },
  unavailableText: { color: '#fff', fontSize: FontSize.xs, fontWeight: '600' },
  info: { padding: Spacing.sm },
  category: { fontSize: FontSize.sm, fontWeight: '600', color: colors.text },
  subcategory: { fontSize: FontSize.xs, color: colors.textSecondary, marginTop: 2 },
  brand: { fontSize: FontSize.xs, color: colors.textTertiary, marginTop: 2 },
  colorRow: { flexDirection: 'row', marginTop: Spacing.xs, gap: 4 },
  colorDot: { width: 14, height: 14, borderRadius: 7, borderWidth: 1, borderColor: colors.border },
});
