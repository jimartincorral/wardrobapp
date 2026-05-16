import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, Pressable, StyleSheet, Alert, ActivityIndicator } from 'react-native';
import { getTotalImageStorage } from '@/src/services/image-service';
import { getGarmentCount } from '@/src/services/garment-service';
import {
  type BackupProgress,
  connectGoogleDrive,
  createBackup,
  createGoogleDriveBackup,
  disconnectGoogleDrive,
  getGoogleDriveStatus,
  listBackups,
  listGoogleDriveBackups,
  restoreBackup,
  restoreGoogleDriveBackup,
  type GoogleDriveBackupFile,
} from '@/src/services/backup-service';
import { Spacing, BorderRadius, FontSize } from '@/src/constants/theme';
import { useTranslation } from '@/src/i18n';
import { useTheme } from '@/src/theme';
import type { ThemeColors } from '@/src/theme';

export default function SettingsScreen() {
  const { t, language, setLanguage, currency, setCurrency } = useTranslation();
  const { colors, mode, setMode } = useTheme();
  const styles = createStyles(colors);
  const [storageUsed, setStorageUsed] = useState(0);
  const [itemCount, setItemCount] = useState(0);
  const [backingUp, setBackingUp] = useState(false);
  const [backupProgress, setBackupProgress] = useState<BackupProgress | null>(null);
  const [driveBusy, setDriveBusy] = useState(false);
  const [backups, setBackups] = useState<{ name: string; uri: string }[]>([]);
  const [driveConnectedEmail, setDriveConnectedEmail] = useState<string | null>(null);
  const [driveBackups, setDriveBackups] = useState<GoogleDriveBackupFile[]>([]);

  useEffect(() => {
    loadStats();
    loadBackups();
    loadDriveState();
  }, []);

  const loadStats = async () => {
    const [storage, count] = await Promise.all([getTotalImageStorage(), getGarmentCount()]);
    setStorageUsed(storage);
    setItemCount(count);
  };

  const getErrorMessage = (error: unknown) => {
    if (error instanceof Error && error.message) {
      return `\n\n${error.message}`;
    }
    return '';
  };

  const loadBackups = async () => setBackups(await listBackups());
  const loadDriveBackups = async () => setDriveBackups(await listGoogleDriveBackups());

  const loadDriveState = async () => {
    const status = await getGoogleDriveStatus();
    setDriveConnectedEmail(status.connected ? status.email ?? null : null);
    if (status.connected) {
      await loadDriveBackups();
    } else {
      setDriveBackups([]);
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
            try {
              await restoreBackup(backup.uri);
              Alert.alert(t('settings.alerts.restored'), t('settings.alerts.restoredMsg'));
            } catch (error) {
              Alert.alert(t('settings.alerts.restoreFailed'), `${t('settings.alerts.restoreFailedMsg')}${getErrorMessage(error)}`);
            }
          },
        },
      ]
    );
  };

  const handleConnectDrive = async () => {
    setDriveBusy(true);
    try {
      const { email } = await connectGoogleDrive();
      setDriveConnectedEmail(email);
      await loadDriveBackups();
      Alert.alert(t('settings.alerts.driveConnected'), t('settings.alerts.driveConnectedMsg', { email }));
    } catch (error) {
      Alert.alert(t('settings.alerts.driveBackupFailed'), `${t('settings.alerts.driveBackupFailedMsg')}${getErrorMessage(error)}`);
    } finally {
      setDriveBusy(false);
    }
  };

  const handleDisconnectDrive = async () => {
    setDriveBusy(true);
    try {
      await disconnectGoogleDrive();
      setDriveConnectedEmail(null);
      setDriveBackups([]);
      Alert.alert(t('settings.alerts.driveDisconnected'), t('settings.alerts.driveDisconnectedMsg'));
    } catch (error) {
      Alert.alert(t('settings.alerts.driveBackupFailed'), `${t('settings.alerts.driveBackupFailedMsg')}${getErrorMessage(error)}`);
    } finally {
      setDriveBusy(false);
    }
  };

  const handleDriveBackup = async () => {
    setDriveBusy(true);
    try {
      const { size } = await createGoogleDriveBackup();
      Alert.alert(t('settings.alerts.driveBackupCreated'), t('settings.alerts.driveBackupSaved', { size: (size / 1024 / 1024).toFixed(1) }));
      await loadDriveState();
    } catch (error) {
      Alert.alert(t('settings.alerts.driveBackupFailed'), `${t('settings.alerts.driveBackupFailedMsg')}${getErrorMessage(error)}`);
    } finally {
      setDriveBusy(false);
    }
  };

  const handleDriveRestore = (backup: GoogleDriveBackupFile) => {
    Alert.alert(
      t('settings.alerts.restoreTitle'),
      t('settings.alerts.restoreMsg', { name: backup.name }),
      [
        { text: t('settings.alerts.cancel'), style: 'cancel' },
        {
          text: t('settings.alerts.restoreConfirm'),
          style: 'destructive',
          onPress: async () => {
            setDriveBusy(true);
            try {
              await restoreGoogleDriveBackup(backup.id);
              Alert.alert(t('settings.alerts.restored'), t('settings.alerts.restoredMsg'));
            } catch (error) {
              Alert.alert(t('settings.alerts.restoreFailed'), `${t('settings.alerts.driveRestoreFailed')}${getErrorMessage(error)}`);
            } finally {
              setDriveBusy(false);
            }
          },
        },
      ]
    );
  };

  return (
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

      <Text style={styles.sectionTitle}>{t('settings.currencyTitle')}</Text>
      <View style={styles.card}>
        <View style={styles.languageRow}>
          {(['USD', 'EUR', 'GBP', 'MXN'] as const).map(code => (
            <Pressable
              key={code}
              style={[styles.langButton, currency === code && styles.langButtonActive, styles.currencyButton]}
              onPress={() => setCurrency(code)}
            >
              <Text style={[styles.langButtonText, currency === code && styles.langButtonTextActive]}>
                {t(`settings.currencies.${code.toLowerCase()}`)}
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
          style={[styles.button, styles.buttonSecondary, { marginTop: Spacing.md }, driveBusy && { opacity: 0.6 }]}
          onPress={driveConnectedEmail ? handleDisconnectDrive : handleConnectDrive}
          disabled={driveBusy}
        >
          {driveBusy ? (
            <ActivityIndicator color={colors.primary} />
          ) : (
            <Text style={[styles.buttonText, styles.buttonTextSecondary]}>
              {driveConnectedEmail ? t('settings.disconnectDrive') : t('settings.connectDrive')}
            </Text>
          )}
        </Pressable>
        <Text style={styles.hint}>
          {driveConnectedEmail
            ? t('settings.driveConnectedAs', { email: driveConnectedEmail })
            : t('settings.driveInfo')}
        </Text>
        {driveConnectedEmail && (
          <>
            <Pressable
              style={[styles.button, { marginTop: Spacing.md }, driveBusy && { opacity: 0.6 }]}
              onPress={handleDriveBackup}
              disabled={driveBusy}
            >
              {driveBusy ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>{t('settings.createDriveBackup')}</Text>}
            </Pressable>
            {driveBackups.length > 0 && (
              <View style={{ marginTop: Spacing.lg }}>
                <Text style={styles.subTitle}>{t('settings.driveBackupsTitle')}</Text>
                {driveBackups.map(backup => (
                  <Pressable key={backup.id} style={styles.backupRow} onPress={() => handleDriveRestore(backup)}>
                    <Text style={styles.backupName} numberOfLines={1}>{backup.name}</Text>
                    <Text style={styles.restoreText}>{t('settings.restore')}</Text>
                  </Pressable>
                ))}
              </View>
            )}
          </>
        )}
        {backups.length > 0 && (
          <View style={{ marginTop: Spacing.lg }}>
            <Text style={styles.subTitle}>{t('settings.availableBackups')}</Text>
            {backups.map(backup => (
              <Pressable key={backup.name} style={styles.backupRow} onPress={() => handleRestore(backup)}>
                <Text style={styles.backupName} numberOfLines={1}>{backup.name}</Text>
                <Text style={styles.restoreText}>{t('settings.restore')}</Text>
              </Pressable>
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
  currencyButton: { minWidth: '47%', minHeight: 72, justifyContent: 'center' },
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
});
