import React from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { GARMENT_COLORS } from '../constants/colors';
import { Spacing, BorderRadius, FontSize } from '../constants/theme';
import { useTranslation } from '../i18n';
import { useTheme } from '../theme';
import type { ThemeColors } from '../theme';

interface ColorPickerProps {
  selected: string[];
  onToggle: (hex: string) => void;
  label?: string;
}

export function ColorPicker({ selected, onToggle, label }: ColorPickerProps) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const selectedLabels = GARMENT_COLORS
    .filter(color => selected.includes(color.hex))
    .map(color => t(`colors.${color.key}`));

  return (
    <View>
      {label && <Text style={styles.label}>{label}</Text>}
      <View style={styles.grid}>
        {GARMENT_COLORS.map(color => (
          <Pressable
            key={color.hex}
            style={[
              styles.colorButton,
              { backgroundColor: color.hex === '#RAINBOW' ? undefined : color.hex },
              color.hex === '#RAINBOW' && styles.rainbow,
              selected.includes(color.hex) && styles.selected,
            ]}
            onPress={() => onToggle(color.hex)}
          >
            {color.hex === '#RAINBOW' && <Text style={styles.rainbowText}>{t('colorPicker.multiShort')}</Text>}
          </Pressable>
        ))}
      </View>
      <Text style={styles.selectedName}>
        {selectedLabels.length > 0 ? selectedLabels.join(', ') : t('colorPicker.selectColor')}
      </Text>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  label: {
    fontSize: FontSize.sm,
    fontWeight: '600',
    color: colors.text,
    marginBottom: Spacing.xs,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: Spacing.sm,
    paddingVertical: Spacing.xs,
  },
  colorButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    borderWidth: 2,
    borderColor: colors.border,
    justifyContent: 'center',
    alignItems: 'center',
  },
  rainbow: {
    borderWidth: 2,
    borderColor: colors.primary,
    backgroundColor: colors.surfaceVariant,
  },
  rainbowText: {
    fontSize: FontSize.xs,
    fontWeight: '700',
    color: colors.primary,
  },
  selected: {
    borderColor: colors.primary,
    borderWidth: 3,
    transform: [{ scale: 1.15 }],
  },
  selectedName: {
    fontSize: FontSize.xs,
    color: colors.textSecondary,
    marginTop: Spacing.xs,
  },
});
