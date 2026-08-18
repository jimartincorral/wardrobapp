import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, Pressable, StyleSheet, Alert, ActivityIndicator, Modal } from 'react-native';
import { getTotalImageStorage, recompressLegacyBgRemovedImages } from '@/src/services/image-service';
import { getGarmentCount } from '@/src/services/garment-service';
import {
  type BackupProgress,
  createBackup,
  deleteBackup,
  listBackups,
  restoreBackup,
  restoreBackupFromFile,
} from '@/src/services/backup-service';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useAppReload } from '@/src/app-reload';
import { useTranslation } from '@/src/i18n';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function SettingsScreen() {
  const { t, language, setLanguage } = useTranslation();
  const { colors, mode, setMode } = useTheme();
  const reloadApp = useAppReload();
  const styles = createStyles(colors);
  const [storageUsed, setStorageUsed] = useState(0);
  const [itemCount, setItemCount] = useState(0);
  const [backingUp, setBackingUp] = useState(false);
  const [optimizing, setOptimizing] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [backupProgress, setBackupProgress] = useState<BackupProgress | null>(null);
  const [backups, setBackups] = useState<{ name: string; uri: string }[]>([]);

  useEffect(() => {
    loadStats();
    loadBackups();
  }, []);

  const loadStats = async () => {
    const [storage, count] = await Promise.all([getTotalImageStorage(), getGarmentCount()]);
    setStorageUsed(storage);
    setItemCount(count);
  };

  const getErrorMessage = (error: unknown) => {
    let detail = '';
    if (error instanceof Error) {
      detail = error.message || (error as any).code || error.name || '';
    } else if (typeof error === 'string') {
      detail = error;
    } else if (error != null) {
      try {
        detail = JSON.stringify(error);
      } catch {
        detail = String(error);
      }
    }
    return detail ? `\n\n${detail}` : '';
  };

  const promptRestored = () => {
    Alert.alert(
      t('settings.alerts.restored'),
      t('settings.alerts.restoredMsg'),
      [
        { text: t('settings.alerts.reloadLater'), style: 'cancel' },
        { text: t('settings.alerts.reloadNow'), onPress: () => reloadApp() },
      ]
    );
  };

  const loadBackups = async () => setBackups(await listBackups());


  const handleOptimizeStorage = async () => {
    setOptimizing(true);
    try {
      const { recompressed, bytesSaved } = await recompressLegacyBgRemovedImages();
      if (recompressed === 0) {
        Alert.alert(t('settings.alerts.optimizeNone'), t('settings.alerts.optimizeNoneMsg'));
      } else {
        Alert.alert(
          t('settings.alerts.optimizeDone'),
          t('settings.alerts.optimizeDoneMsg', {
            count: recompressed,
            mb: (bytesSaved / 1024 / 1024).toFixed(1),
          })
        );
        await loadStats();
      }
    } catch (error) {
      Alert.alert(t('settings.alerts.optimizeFailed'), `${t('settings.alerts.optimizeFailedMsg')}${getErrorMessage(error)}`);
    } finally {
      setOptimizing(false);
    }
  };

  const handleBackup = async () => {
    setBackingUp(true);
    setBackupProgress({ phase: 'preparing', percent: 0, message: 'Starting backup' });
    try {
      const { size } = await createBackup({
        onProgress: (progress) => setBackupProgress(progress),
      });
      Alert.alert(t('settings.alerts.backupCreated'), t('settings.alerts.backupSaved', { size: (size / 1024 / 1024).toFixed(1) }));
      await loadBackups();
    } catch (error) {
      Alert.alert(t('settings.alerts.backupFailed'), `${t('settings.alerts.backupFailedMsg')}${getErrorMessage(error)}`);
    } finally {
      setBackingUp(false);
      setBackupProgress(null);
    }
  };

  const handleRestore = (backup: { name: string; uri: string }) => {
    Alert.alert(
      t('settings.alerts.restoreTitle'),
      t('settings.alerts.restoreMsg', { name: backup.name }),
      [
        { text: t('settings.alerts.cancel'), style: 'cancel' },
        {
          text: t('settings.alerts.restoreConfirm'),
          style: 'destructive',
          onPress: async () => {
            setRestoring(true);
            try {
              await restoreBackup(backup.uri);
              promptRestored();
            } catch (error) {
              Alert.alert(t('settings.alerts.restoreFailed'), `${t('settings.alerts.restoreFailedMsg')}${getErrorMessage(error)}`);
            } finally {
              setRestoring(false);
            }
          },
        },
      ]
    );
  };

  const handleDelete = (backup: { name: string; uri: string }) => {
    Alert.alert(
      t('settings.alerts.deleteTitle'),
      t('settings.alerts.deleteMsg', { name: backup.name }),
      [
        { text: t('settings.alerts.cancel'), style: 'cancel' },
        {
          text: t('settings.alerts.deleteConfirm'),
          style: 'destructive',
          onPress: async () => {
            try {
              await deleteBackup(backup.uri);
              await loadBackups();
              Alert.alert(t('settings.alerts.deleted'), t('settings.alerts.deletedMsg'));
            } catch (error) {
              Alert.alert(t('settings.alerts.deleteFailed'), `${t('settings.alerts.deleteFailedMsg')}${getErrorMessage(error)}`);
            }
          },
        },
      ]
    );
  };

  const handleRestoreFromFile = () => {
    Alert.alert(
      t('settings.alerts.restoreTitle'),
      t('settings.alerts.restoreFileMsg'),
      [
        { text: t('settings.alerts.cancel'), style: 'cancel' },
        {
          text: t('settings.alerts.restoreConfirm'),
          style: 'destructive',
          onPress: async () => {
            setRestoring(true);
            try {
              const restored = await restoreBackupFromFile();
              if (restored) {
                promptRestored();
              }
            } catch (error) {
              Alert.alert(t('settings.alerts.restoreFailed'), `${t('settings.alerts.restoreFailedMsg')}${getErrorMessage(error)}`);
            } finally {
              setRestoring(false);
            }
          },
        },
      ]
    );
  };






  return (
    <>
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Language */}
      <Text style={styles.sectionTitle}>{t('settings.languageTitle')}</Text>
      <View style={styles.card}>
        <View style={styles.languageRow}>
          <Pressable
            style={[styles.langButton, language === 'en' && styles.langButtonActive]}
            onPress={() => setLanguage('en')}
          >
            <Text style={[styles.langButtonText, language === 'en' && styles.langButtonTextActive]}>
              {t('settings.english')}
            </Text>
          </Pressable>
          <Pressable
            style={[styles.langButton, language === 'es' && styles.langButtonActive]}
            onPress={() => setLanguage('es')}
          >
            <Text style={[styles.langButtonText, language === 'es' && styles.langButtonTextActive]}>
              {t('settings.spanish')}
            </Text>
          </Pressable>
        </View>
      </View>

      <Text style={styles.sectionTitle}>{t('settings.themeTitle')}</Text>
      <View style={styles.card}>
        <View style={styles.languageRow}>
          {(['system', 'light', 'dark'] as const).map(option => (
            <Pressable
              key={option}
              style={[styles.langButton, mode === option && styles.langButtonActive]}
              onPress={() => setMode(option)}
            >
              <Text style={[styles.langButtonText, mode === option && styles.langButtonTextActive]}>
                {t(`settings.themeModes.${option}`)}
              </Text>
            </Pressable>
          ))}
        </View>
      </View>

      {/* Storage */}
      <Text style={styles.sectionTitle}>{t('settings.storageTitle')}</Text>
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.label}>{t('settings.totalItems')}</Text>
          <Text style={styles.value}>{itemCount}</Text>
        </View>
        <View style={styles.row}>
          <Text style={styles.label}>{t('settings.imageStorage')}</Text>
          <Text style={styles.value}>{t('settings.storageMb', { mb: storageUsed.toFixed(1) })}</Text>
        </View>
        <Pressable
          style={[styles.button, styles.buttonSecondary, { marginTop: Spacing.md }, optimizing && { opacity: 0.6 }]}
          onPress={handleOptimizeStorage}
          disabled={optimizing}
        >
          {optimizing ? (
            <ActivityIndicator color={colors.primary} />
          ) : (
            <Text style={[styles.buttonText, styles.buttonTextSecondary]}>{t('settings.optimizeStorage')}</Text>
          )}
        </Pressable>
        <Text style={styles.hint}>{t('settings.optimizeStorageHint')}</Text>
      </View>

      {/* Backup */}
      <Text style={styles.sectionTitle}>{t('settings.backupTitle')}</Text>
      <View style={styles.card}>
        <Pressable style={[styles.button, backingUp && { opacity: 0.6 }]} onPress={handleBackup} disabled={backingUp}>
          {backingUp ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>{t('settings.createBackup')}</Text>}
        </Pressable>
        {backupProgress && (
          <View style={styles.progressWrap}>
            <View style={styles.progressTrack}>
              <View style={[styles.progressFill, { width: `${backupProgress.percent}%` }]} />
            </View>
            <View style={styles.progressRow}>
              <Text style={styles.progressText}>{backupProgress.message}</Text>
              <Text style={styles.progressText}>{backupProgress.percent}%</Text>
            </View>
          </View>
        )}
        <Text style={styles.hint}>{t('settings.backupHint')}</Text>
        <Pressable
          style={[styles.button, styles.buttonSecondary, { marginTop: Spacing.md }]}
          onPress={handleRestoreFromFile}
        >
          <Text style={[styles.buttonText, styles.buttonTextSecondary]}>{t('settings.restoreFromFile')}</Text>
        </Pressable>
        <Text style={styles.hint}>{t('settings.restoreFromFileHint')}</Text>
        {backups.length > 0 && (
          <View style={{ marginTop: Spacing.lg }}>
            <Text style={styles.subTitle}>{t('settings.availableBackups')}</Text>
            {backups.map(backup => (
              <View key={backup.name} style={styles.backupRow}>
                <Text style={styles.backupName} numberOfLines={1}>{backup.name}</Text>
                <Pressable hitSlop={8} onPress={() => handleRestore(backup)}>
                  <Text style={styles.restoreText}>{t('settings.restore')}</Text>
                </Pressable>
                <Pressable hitSlop={8} style={styles.deleteAction} onPress={() => handleDelete(backup)}>
                  <Text style={styles.deleteText}>{t('settings.delete')}</Text>
                </Pressable>
              </View>
            ))}
          </View>
        )}
      </View>

      {/* About */}
      <Text style={styles.sectionTitle}>{t('settings.aboutTitle')}</Text>
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.label}>{t('settings.version')}</Text>
          <Text style={styles.value}>1.0.0</Text>
        </View>
        <View style={styles.row}>
          <Text style={styles.label}>{t('settings.framework')}</Text>
          <Text style={styles.value}>Expo SDK 55</Text>
        </View>
      </View>
    </ScrollView>

    <Modal visible={restoring} transparent animationType="fade" statusBarTranslucent onRequestClose={() => {}}>
      <View style={styles.overlay}>
        <View style={styles.overlayCard}>
          <ActivityIndicator size="large" color={colors.primary} />
          <Text style={styles.overlayText}>{t('settings.alerts.restoring')}</Text>
          <Text style={styles.overlaySubtext}>{t('settings.alerts.restoringHint')}</Text>
        </View>
      </View>
    </Modal>
    </>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: Spacing.lg, paddingBottom: Spacing.xxl },
  sectionTitle: { fontSize: FontSize.lg, fontWeight: '700', color: colors.text, marginTop: Spacing.lg, marginBottom: Spacing.sm },
  subTitle: { fontSize: FontSize.sm, fontWeight: '600', color: colors.textSecondary, marginBottom: Spacing.sm },
  card: { backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.md },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: Spacing.sm, borderBottomWidth: 1, borderBottomColor: colors.border },
  label: { fontSize: FontSize.md, color: colors.textSecondary },
  value: { fontSize: FontSize.md, fontWeight: '600', color: colors.text },
  languageRow: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.sm },
  langButton: { flex: 1, padding: Spacing.md, borderRadius: BorderRadius.md, backgroundColor: colors.surfaceVariant, alignItems: 'center' },
  langButtonActive: { backgroundColor: colors.primary },
  langButtonText: { fontSize: FontSize.md, fontWeight: '600', color: colors.textSecondary },
  langButtonTextActive: { color: '#fff' },
  button: { backgroundColor: colors.primary, borderRadius: BorderRadius.md, padding: Spacing.md, alignItems: 'center' },
  buttonSecondary: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.primary },
  buttonText: { color: '#fff', fontSize: FontSize.md, fontWeight: '600' },
  buttonTextSecondary: { color: colors.primary },
  progressWrap: { marginTop: Spacing.md },
  progressTrack: { height: 8, borderRadius: BorderRadius.full, backgroundColor: colors.surfaceVariant, overflow: 'hidden' },
  progressFill: { height: '100%', borderRadius: BorderRadius.full, backgroundColor: colors.primary },
  progressRow: { marginTop: Spacing.xs, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  progressText: { fontSize: FontSize.xs, color: colors.textSecondary },
  hint: { fontSize: FontSize.xs, color: colors.textTertiary, marginTop: Spacing.sm, lineHeight: 18 },
  backupRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: Spacing.sm, borderBottomWidth: 1, borderBottomColor: colors.border },
  backupName: { flex: 1, fontSize: FontSize.sm, color: colors.text, marginRight: Spacing.md },
  restoreText: { fontSize: FontSize.sm, fontWeight: '600', color: colors.primary },
  deleteAction: { marginLeft: Spacing.md },
  deleteText: { fontSize: FontSize.sm, fontWeight: '600', color: colors.error },
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'center', alignItems: 'center', padding: Spacing.xl },
  overlayCard: { backgroundColor: colors.surface, borderRadius: BorderRadius.md, padding: Spacing.xl, alignItems: 'center', maxWidth: 320 },
  overlayText: { marginTop: Spacing.md, fontSize: FontSize.md, fontWeight: '600', color: colors.text, textAlign: 'center' },
  overlaySubtext: { marginTop: Spacing.xs, fontSize: FontSize.sm, color: colors.textSecondary, textAlign: 'center' },
});
