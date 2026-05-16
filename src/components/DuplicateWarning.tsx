import React from 'react';
import { View, Text, Image, Pressable, StyleSheet, Modal } from 'react-native';
import { Spacing, BorderRadius, FontSize } from '../constants/theme';
import { useTranslation } from '../i18n';
import { localizeGarmentLabel } from '../utils/localization-helpers';
import type { DuplicateMatch } from '../services/duplicate-detector';
import { getGarmentDisplayImage } from '../utils/garment-fields';
import { useTheme } from '../theme';
import type { ThemeColors } from '../theme';

interface DuplicateWarningProps {
  visible: boolean;
  matches: DuplicateMatch[];
  onContinue: () => void;
  onCancel: () => void;
}

export function DuplicateWarning({ visible, matches, onContinue, onCancel }: DuplicateWarningProps) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  if (matches.length === 0) return null;

  return (
    <Modal visible={visible} transparent animationType="slide">
      <View style={styles.overlay}>
        <View style={styles.modal}>
          <Text style={styles.title}>{t('duplicateWarning.title')}</Text>
          <Text style={styles.subtitle}>{t('duplicateWarning.subtitle')}</Text>
          {matches.slice(0, 3).map(match => (
            <View key={match.garment.id} style={styles.matchRow}>
              <Image source={{ uri: getGarmentDisplayImage(match.garment) }} style={styles.matchImage} resizeMode="cover" />
              <View style={styles.matchInfo}>
                <Text style={styles.matchCategory}>{localizeGarmentLabel(match.garment.category, match.garment.subcategories, t)}</Text>
                {match.garment.brand && <Text style={styles.matchBrand}>{match.garment.brand}</Text>}
                <Text style={styles.matchReason}>
                  {match.reason.split(', ').map(key => t(key)).join(', ')}
                </Text>
                <Text style={styles.matchScore}>{t('duplicateWarning.matchScore', { score: Math.round(match.score * 100) })}</Text>
              </View>
            </View>
          ))}
          <View style={styles.buttons}>
            <Pressable style={styles.cancelButton} onPress={onCancel}>
              <Text style={styles.cancelText}>{t('duplicateWarning.goBack')}</Text>
            </Pressable>
            <Pressable style={styles.continueButton} onPress={onContinue}>
              <Text style={styles.continueText}>{t('duplicateWarning.addAnyway')}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' },
  modal: { backgroundColor: colors.surface, borderTopLeftRadius: BorderRadius.xl, borderTopRightRadius: BorderRadius.xl, padding: Spacing.lg, maxHeight: '70%' },
  title: { fontSize: FontSize.xl, fontWeight: '700', color: colors.warning, marginBottom: Spacing.xs },
  subtitle: { fontSize: FontSize.md, color: colors.textSecondary, marginBottom: Spacing.lg },
  matchRow: { flexDirection: 'row', backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.md, padding: Spacing.sm, marginBottom: Spacing.sm },
  matchImage: { width: 60, height: 80, borderRadius: BorderRadius.sm },
  matchInfo: { flex: 1, marginLeft: Spacing.md, justifyContent: 'center' },
  matchCategory: { fontSize: FontSize.md, fontWeight: '600', color: colors.text },
  matchBrand: { fontSize: FontSize.sm, color: colors.textSecondary },
  matchReason: { fontSize: FontSize.xs, color: colors.textTertiary, marginTop: 2 },
  matchScore: { fontSize: FontSize.xs, fontWeight: '600', color: colors.warning, marginTop: 2 },
  buttons: { flexDirection: 'row', gap: Spacing.md, marginTop: Spacing.lg },
  cancelButton: { flex: 1, backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center' },
  cancelText: { fontSize: FontSize.md, fontWeight: '600', color: colors.text },
  continueButton: { flex: 1, backgroundColor: colors.primary, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center' },
  continueText: { fontSize: FontSize.md, fontWeight: '600', color: '#fff' },
});
