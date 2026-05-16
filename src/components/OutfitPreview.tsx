import React from 'react';
import { View, Text, Image, StyleSheet, Pressable } from 'react-native';
import { Spacing, BorderRadius, FontSize } from '../constants/theme';
import { useTranslation } from '../i18n';
import { localizeGarmentLabel } from '../utils/localization-helpers';
import type { Garment } from '../types';
import { getGarmentDisplayImage } from '../utils/garment-fields';
import { useTheme } from '../theme';
import type { ThemeColors } from '../theme';

interface OutfitPreviewProps {
  garments: Garment[];
  name?: string;
  score?: number;
  onPress?: () => void;
  children?: React.ReactNode;
}

export function OutfitPreview({ garments, name, score, onPress, children }: OutfitPreviewProps) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);

  return (
    <Pressable
      style={({ pressed }) => [styles.container, pressed && onPress && styles.pressed]}
      onPress={onPress}
      disabled={!onPress}
    >
      {name && <Text style={styles.name}>{name}</Text>}
      <View style={styles.garmentRow}>
        {garments.map((garment, index) => (
          <View key={garment.id + index} style={styles.garmentItem}>
            <Image
              source={{ uri: getGarmentDisplayImage(garment) }}
              style={styles.garmentImage}
              resizeMode="cover"
            />
            <Text style={styles.garmentLabel} numberOfLines={1}>
              {localizeGarmentLabel(garment.category, garment.subcategories, t)}
            </Text>
          </View>
        ))}
      </View>
      {score !== undefined && (
        <Text style={styles.score}>{t('outfitPreview.matchScore', { score: Math.round(score * 100) })}</Text>
      )}
      {children}
    </Pressable>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: {
    backgroundColor: colors.surface,
    borderRadius: BorderRadius.lg,
    padding: Spacing.md,
    marginBottom: Spacing.md,
    elevation: 2,
    shadowColor: colors.shadow,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  pressed: {
    opacity: 0.9,
    transform: [{ scale: 0.99 }],
  },
  name: {
    fontSize: FontSize.lg,
    fontWeight: '600',
    color: colors.text,
    marginBottom: Spacing.sm,
  },
  garmentRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    gap: Spacing.sm,
  },
  garmentItem: {
    alignItems: 'center',
    flex: 1,
  },
  garmentImage: {
    width: 80,
    height: 100,
    borderRadius: BorderRadius.sm,
    backgroundColor: colors.surfaceVariant,
  },
  garmentLabel: {
    fontSize: FontSize.xs,
    color: colors.textSecondary,
    marginTop: Spacing.xs,
    textAlign: 'center',
  },
  score: {
    fontSize: FontSize.xs,
    color: colors.textTertiary,
    marginTop: Spacing.sm,
    textAlign: 'center',
  },
});
