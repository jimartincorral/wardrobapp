import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';
import JSZip from 'jszip';
import { closeDatabase, getDatabase } from '../db/client';

/**
 * Google Drive Backup Service
 *
 * This service handles exporting/importing the wardrobe data.
 * Full Google Drive integration requires:
 * 1. @react-native-google-signin/google-signin (needs dev build, not Expo Go)
 * 2. Google Cloud Console project with Drive API enabled
 * 3. OAuth 2.0 client ID configured
 *
 * For now, this provides local export/import functionality.
 * Google Drive upload/download can be added when building a dev APK.
 *
 * NOTE: Backup/restore uses expo-file-system which is native-only.
 * On web these functions throw a clear error.
 */

let _fs: typeof import('expo-file-system/legacy') | null = null;
const DOWNLOADS_DIR_URI_KEY = 'backup_downloads_directory_uri';
const GOOGLE_DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
const GOOGLE_DRIVE_FILES_URL = 'https://www.googleapis.com/drive/v3/files';
const GOOGLE_DRIVE_UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files';
let _googleConfigured = false;

function getFS() {
  if (!_fs) {
    _fs = require('expo-file-system/legacy');
  }
  return _fs!;
}

function getDbPath() { return `${getFS().documentDirectory}SQLite/wardrobapp.db`; }
function getImageDir() { return `${getFS().documentDirectory}garment-images/`; }
function getBackupDir() { return `${getFS().documentDirectory}backups/`; }
function getPreferredDownloadsDirUri() {
  return getFS().StorageAccessFramework.getUriForDirectoryInRoot('Download');
}

function ensureNative(action: string) {
  if (Platform.OS === 'web') {
    throw new Error(
      `${action} is not available on web. Use the native app for backup/restore.`
    );
  }
}

async function ensureBackupDir() {
  const fs = getFS();
  const dir = getBackupDir();
  const dirInfo = await fs.getInfoAsync(dir);
  if (!dirInfo.exists) {
    await fs.makeDirectoryAsync(dir, { intermediates: true });
  }
}

function getBackupFilename() {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  return `wardrobapp-backup-${timestamp}.zip`;
}

async function withClosedDatabase<T>(operation: () => Promise<T>): Promise<T> {
  if (Platform.OS === 'web') {
    return operation();
  }

  await closeDatabase();

  try {
    return await operation();
  } finally {
    await getDatabase();
  }
}

type BackupPayload = {
  version: number;
  created_at: string;
  database: string;
  images?: { name: string; data: string }[];
};

type BackupImage = {
  name: string;
  data: string;
};

export type GoogleDriveBackupFile = {
  id: string;
  name: string;
  modifiedTime?: string;
  size?: string;
};

export type BackupProgress = {
  phase: 'preparing' | 'archiving' | 'saving' | 'uploading' | 'done';
  percent: number;
  message: string;
};

type BackupProgressCallback = (progress: BackupProgress) => void;

function clampProgress(percent: number) {
  return Math.max(0, Math.min(100, Math.round(percent)));
}

function emitProgress(
  onProgress: BackupProgressCallback | undefined,
  phase: BackupProgress['phase'],
  percent: number,
  message: string
) {
  onProgress?.({
    phase,
    percent: clampProgress(percent),
    message,
  });
}

async function buildBackupData(onProgress?: BackupProgressCallback): Promise<{ payload: BackupPayload; images: BackupImage[] }> {
  const fs = getFS();
  emitProgress(onProgress, 'preparing', 5, 'Reading database');

  let dbBase64 = '';
  const dbInfo = await fs.getInfoAsync(getDbPath());
  if (dbInfo.exists) {
    dbBase64 = await fs.readAsStringAsync(getDbPath(), {
      encoding: fs.EncodingType.Base64,
    });
  }

  const imageFiles: { name: string; data: string }[] = [];
  const imgDirInfo = await fs.getInfoAsync(getImageDir());
  if (imgDirInfo.exists) {
    const files = await fs.readDirectoryAsync(getImageDir());
    const totalFiles = files.length || 1;
    for (const file of files) {
      try {
        const data = await fs.readAsStringAsync(`${getImageDir()}${file}`, {
          encoding: fs.EncodingType.Base64,
        });
        imageFiles.push({ name: file, data });
      } catch {
        // Skip files that can't be read
      }
      emitProgress(
        onProgress,
        'preparing',
        10 + (imageFiles.length / totalFiles) * 35,
        `Collecting images (${Math.min(imageFiles.length, files.length)}/${files.length})`
      );
    }
  }

  emitProgress(onProgress, 'preparing', 45, 'Preparing backup archive');

  return {
    payload: {
      version: 2,
      created_at: new Date().toISOString(),
      database: dbBase64,
    },
    images: imageFiles,
  };
}

async function buildBackupArchiveBase64(
  payload: BackupPayload,
  images: BackupImage[],
  onProgress?: BackupProgressCallback
) {
  const zip = new JSZip();

  zip.file('backup.json', JSON.stringify(payload), {
    compression: 'DEFLATE',
    compressionOptions: { level: 3 },
  });

  for (const image of images) {
    zip.file(`images/${image.name}`, image.data, {
      base64: true,
      compression: 'STORE',
    });
  }

  emitProgress(onProgress, 'archiving', 50, 'Building ZIP archive');

  return zip.generateAsync(
    {
      type: 'base64',
      compression: 'STORE',
    },
    (metadata) => {
      emitProgress(
        onProgress,
        'archiving',
        50 + metadata.percent * 0.4,
        `Building ZIP archive (${Math.round(metadata.percent)}%)`
      );
    }
  );
}

async function createBackupArchive(onProgress?: BackupProgressCallback) {
  const name = getBackupFilename();
  const { payload, images } = await withClosedDatabase(() => buildBackupData(onProgress));
  const base64 = await buildBackupArchiveBase64(payload, images, onProgress);
  const size = Math.round((base64.length * 3) / 4);
  emitProgress(onProgress, 'saving', 90, 'Archive ready');
  return { name, base64, size };
}

function getSafEntryName(uri: string) {
  const decoded = decodeURIComponent(uri);
  return decoded.slice(decoded.lastIndexOf('/') + 1);
}

async function readBackupContents(
  backupUri: string,
  encoding?: import('expo-file-system/legacy').EncodingType | 'utf8' | 'base64'
) {
  const fs = getFS();
  const options = encoding ? { encoding } : undefined;

  if (Platform.OS === 'android' && backupUri.startsWith('content://')) {
    return fs.StorageAccessFramework.readAsStringAsync(backupUri, options);
  }

  return fs.readAsStringAsync(backupUri, options);
}

async function readBackupFileName(backupUri: string) {
  if (backupUri.startsWith('content://')) {
    return getSafEntryName(backupUri);
  }

  return backupUri.slice(backupUri.lastIndexOf('/') + 1);
}

function getTempBackupDir() {
  const fs = getFS();
  return `${fs.cacheDirectory ?? fs.documentDirectory}backup-temp/`;
}

async function ensureTempBackupDir() {
  const fs = getFS();
  const dir = getTempBackupDir();
  const info = await fs.getInfoAsync(dir);
  if (!info.exists) {
    await fs.makeDirectoryAsync(dir, { intermediates: true });
  }
}

async function writeTempBackupFile(filename: string, base64: string) {
  const fs = getFS();
  await ensureTempBackupDir();
  const uri = `${getTempBackupDir()}${filename}`;
  await fs.writeAsStringAsync(uri, base64, {
    encoding: fs.EncodingType.Base64,
  });
  return uri;
}

async function cleanupTempFile(uri: string) {
  try {
    await getFS().deleteAsync(uri, { idempotent: true });
  } catch {
    // Ignore cleanup errors.
  }
}

async function loadBackupData(backupUri: string): Promise<{ payload: BackupPayload; images: BackupImage[] }> {
  const filename = (await readBackupFileName(backupUri)).toLowerCase();

  if (filename.endsWith('.zip')) {
    const zipBase64 = await readBackupContents(backupUri, getFS().EncodingType.Base64);
    const zip = await JSZip.loadAsync(zipBase64, { base64: true });
    const payloadFile = zip.file('backup.json');

    if (!payloadFile) {
      throw new Error('Invalid backup archive: missing backup.json');
    }

    const payload = JSON.parse(await payloadFile.async('string')) as BackupPayload;
    const images: BackupImage[] = [];

    for (const entry of Object.values(zip.files)) {
      if (entry.dir || !entry.name.startsWith('images/')) continue;
      images.push({
        name: entry.name.replace(/^images\//, ''),
        data: await entry.async('base64'),
      });
    }

    return { payload, images };
  }

  const content = await readBackupContents(backupUri);
  const payload = JSON.parse(content) as BackupPayload;
  return { payload, images: Array.isArray(payload.images) ? payload.images : [] };
}

async function replaceImageDirectory(images: BackupImage[]) {
  const fs = getFS();
  const imageDir = getImageDir();
  const imgDirInfo = await fs.getInfoAsync(imageDir);

  if (imgDirInfo.exists) {
    const existingFiles = await fs.readDirectoryAsync(imageDir);
    for (const file of existingFiles) {
      await fs.deleteAsync(`${imageDir}${file}`, { idempotent: true });
    }
  } else {
    await fs.makeDirectoryAsync(imageDir, { intermediates: true });
  }

  for (const img of images) {
    await fs.writeAsStringAsync(`${imageDir}${img.name}`, img.data, {
      encoding: fs.EncodingType.Base64,
    });
  }
}

function ensureGoogleDriveSupported() {
  ensureNative('Google Drive backup');

  if (Platform.OS !== 'android') {
    throw new Error('Google Drive backup is only available on Android.');
  }
}

function getGoogleSignin() {
  return require('@react-native-google-signin/google-signin') as typeof import('@react-native-google-signin/google-signin');
}

function configureGoogleSignin() {
  if (_googleConfigured) return;

  const { GoogleSignin } = getGoogleSignin();
  GoogleSignin.configure({
    scopes: [GOOGLE_DRIVE_SCOPE],
  });
  _googleConfigured = true;
}

async function ensureGoogleDriveSession(interactive = false) {
  ensureGoogleDriveSupported();
  const { GoogleSignin } = getGoogleSignin();

  configureGoogleSignin();
  await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });

  let user = GoogleSignin.getCurrentUser();

  if (!user) {
    try {
      const silent = await GoogleSignin.signInSilently();
      if (silent.type === 'success') {
        user = silent.data;
      }
    } catch {
      // Ignore silent sign-in errors and fall back to interactive sign-in if requested.
    }
  }

  if (!user && interactive) {
    const signInResult = await GoogleSignin.signIn();
    if (signInResult.type !== 'success') {
      throw new Error('Google sign-in was cancelled.');
    }
    user = signInResult.data;
  }

  if (!user) {
    return null;
  }

  if (!user.scopes?.includes(GOOGLE_DRIVE_SCOPE)) {
    const scopedResult = await GoogleSignin.addScopes({ scopes: [GOOGLE_DRIVE_SCOPE] });
    if (scopedResult?.type === 'success') {
      user = scopedResult.data;
    }
  }

  if (!user.scopes?.includes(GOOGLE_DRIVE_SCOPE)) {
    throw new Error('Google Drive permission was not granted.');
  }

  const { accessToken } = await GoogleSignin.getTokens();
  return { accessToken, user };
}

async function parseDriveError(response: Response) {
  try {
    const data = await response.json();
    return data?.error?.message ?? `Google Drive request failed (${response.status}).`;
  } catch {
    return `Google Drive request failed (${response.status}).`;
  }
}

async function driveFetch(accessToken: string, input: string, init?: RequestInit) {
  const response = await fetch(input, {
    ...init,
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    throw new Error(await parseDriveError(response));
  }

  return response;
}

async function uploadBackupToGoogleDrive(accessToken: string, fileUri: string, filename: string) {
  const fs = getFS();
  const sessionResponse = await driveFetch(
    accessToken,
    `${GOOGLE_DRIVE_UPLOAD_URL}?uploadType=resumable&fields=id,name,modifiedTime,size`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=UTF-8',
        'X-Upload-Content-Type': 'application/zip',
      },
      body: JSON.stringify({
        name: filename,
        parents: ['appDataFolder'],
      }),
    }
  );

  const uploadUrl = sessionResponse.headers.get('location') ?? sessionResponse.headers.get('Location');
  if (!uploadUrl) {
    throw new Error('Google Drive upload session did not return an upload URL.');
  }

  const uploadResult = await fs.uploadAsync(uploadUrl, fileUri, {
    httpMethod: 'PUT',
    uploadType: fs.FileSystemUploadType.BINARY_CONTENT,
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/zip',
    },
  });

  if (uploadResult.status >= 400) {
    try {
      const data = JSON.parse(uploadResult.body || '{}');
      throw new Error(data?.error?.message ?? `Google Drive upload failed (${uploadResult.status}).`);
    } catch (error) {
      if (error instanceof Error) throw error;
      throw new Error(`Google Drive upload failed (${uploadResult.status}).`);
    }
  }

  return JSON.parse(uploadResult.body || '{}') as GoogleDriveBackupFile;
}

async function getAndroidBackupDirectoryUri() {
  const fs = getFS();
  const saf = fs.StorageAccessFramework;
  const savedUri = await AsyncStorage.getItem(DOWNLOADS_DIR_URI_KEY);

  if (savedUri) {
    try {
      await saf.readDirectoryAsync(savedUri);
      return savedUri;
    } catch {
      await AsyncStorage.removeItem(DOWNLOADS_DIR_URI_KEY);
    }
  }

  const permission = await saf.requestDirectoryPermissionsAsync(getPreferredDownloadsDirUri());
  if (!permission.granted || !permission.directoryUri) {
    throw new Error('Downloads directory permission was not granted');
  }

  await AsyncStorage.setItem(DOWNLOADS_DIR_URI_KEY, permission.directoryUri);
  return permission.directoryUri;
}

/**
 * Export the database and image list as a JSON backup.
 * Returns the backup file URI.
 */
export async function createBackup(options?: { onProgress?: BackupProgressCallback }): Promise<{ uri: string; size: number }> {
  ensureNative('Backup creation');
  const fs = getFS();
  const onProgress = options?.onProgress;
  emitProgress(onProgress, 'preparing', 0, 'Starting backup');
  const { name: backupFilename, base64: backupBase64, size } = await createBackupArchive(onProgress);

  if (Platform.OS === 'android') {
    const backupDirUri = await getAndroidBackupDirectoryUri();
    emitProgress(onProgress, 'saving', 94, 'Saving backup to Downloads');
    const backupUri = await fs.StorageAccessFramework.createFileAsync(
      backupDirUri,
      backupFilename,
      'application/zip'
    );
    await fs.StorageAccessFramework.writeAsStringAsync(backupUri, backupBase64, {
      encoding: fs.EncodingType.Base64,
    });
    emitProgress(onProgress, 'done', 100, 'Backup complete');
    return { uri: backupUri, size };
  }

  await ensureBackupDir();
  const backupUri = `${getBackupDir()}${backupFilename}`;
  emitProgress(onProgress, 'saving', 94, 'Saving backup');
  await fs.writeAsStringAsync(backupUri, backupBase64, {
    encoding: fs.EncodingType.Base64,
  });
  emitProgress(onProgress, 'done', 100, 'Backup complete');
  return { uri: backupUri, size };
}

export async function getGoogleDriveStatus(): Promise<{ connected: boolean; email?: string | null }> {
  try {
    const session = await ensureGoogleDriveSession(false);
    return {
      connected: Boolean(session),
      email: session?.user.user.email ?? null,
    };
  } catch {
    return { connected: false, email: null };
  }
}

export async function connectGoogleDrive(): Promise<{ email: string }> {
  const session = await ensureGoogleDriveSession(true);
  if (!session) {
    throw new Error('Google sign-in was not completed.');
  }

  return { email: session.user.user.email };
}

export async function disconnectGoogleDrive(): Promise<void> {
  ensureGoogleDriveSupported();
  const { GoogleSignin } = getGoogleSignin();

  configureGoogleSignin();
  await GoogleSignin.signOut();
}

export async function createGoogleDriveBackup(): Promise<{ id: string; name: string; size: number }> {
  const session = await ensureGoogleDriveSession(true);
  if (!session) {
    throw new Error('Sign in to Google Drive first.');
  }

  const archive = await createBackupArchive();
  const tempUri = await writeTempBackupFile(archive.name, archive.base64);

  try {
    const uploadedFile = await uploadBackupToGoogleDrive(session.accessToken, tempUri, archive.name);
    return {
      id: uploadedFile.id,
      name: uploadedFile.name,
      size: archive.size,
    };
  } finally {
    await cleanupTempFile(tempUri);
  }
}

export async function listGoogleDriveBackups(): Promise<GoogleDriveBackupFile[]> {
  const session = await ensureGoogleDriveSession(false);
  if (!session) return [];

  const query = encodeURIComponent(`'appDataFolder' in parents and trashed = false and name contains 'wardrobapp-backup-'`);
  const response = await driveFetch(
    session.accessToken,
    `${GOOGLE_DRIVE_FILES_URL}?spaces=appDataFolder&fields=files(id,name,modifiedTime,size)&orderBy=modifiedTime desc&q=${query}`
  );
  const data = await response.json();
  return Array.isArray(data.files) ? data.files : [];
}

export async function restoreGoogleDriveBackup(fileId: string): Promise<void> {
  const session = await ensureGoogleDriveSession(true);
  if (!session) {
    throw new Error('Sign in to Google Drive first.');
  }

  const downloadUri = `${getTempBackupDir()}google-drive-restore-${fileId}.zip`;
  await ensureTempBackupDir();

  try {
    await getFS().downloadAsync(`${GOOGLE_DRIVE_FILES_URL}/${fileId}?alt=media`, downloadUri, {
      headers: {
        Authorization: `Bearer ${session.accessToken}`,
      },
    });
    await restoreBackup(downloadUri);
  } finally {
    await cleanupTempFile(downloadUri);
  }
}

/**
 * Restore from a backup JSON file.
 */
export async function restoreBackup(backupUri: string): Promise<void> {
  ensureNative('Backup restore');
  const fs = getFS();
  const { payload: backup, images } = await loadBackupData(backupUri);

  if (![1, 2].includes(backup.version)) {
    throw new Error('Unsupported backup version');
  }

  await withClosedDatabase(async () => {
    if (backup.database) {
      const dbDir = `${fs.documentDirectory}SQLite/`;
      const dirInfo = await fs.getInfoAsync(dbDir);
      if (!dirInfo.exists) {
        await fs.makeDirectoryAsync(dbDir, { intermediates: true });
      }
      await fs.writeAsStringAsync(getDbPath(), backup.database, {
        encoding: fs.EncodingType.Base64,
      });
    }

    if (images.length > 0) {
      await replaceImageDirectory(images);
    }
  });
}

/**
 * List available local backups.
 */
export async function listBackups(): Promise<{ name: string; uri: string }[]> {
  if (Platform.OS === 'web') return [];

  try {
    const fs = getFS();

    if (Platform.OS === 'android') {
      const backupDirUri = await AsyncStorage.getItem(DOWNLOADS_DIR_URI_KEY);
      if (!backupDirUri) return [];

      const files = await fs.StorageAccessFramework.readDirectoryAsync(backupDirUri);
      return files
        .map(uri => ({ name: getSafEntryName(uri), uri }))
        .filter(file => file.name.startsWith('wardrobapp-backup-'))
        .sort((a, b) => a.name.localeCompare(b.name))
        .reverse();
    }

    await ensureBackupDir();
    const files = await fs.readDirectoryAsync(getBackupDir());
    return files
      .filter(f => f.endsWith('.json') || f.endsWith('.zip'))
      .sort()
      .reverse()
      .map(f => ({ name: f, uri: `${getBackupDir()}${f}` }));
  } catch {
    return [];
  }
}

/**
 * Check if Google Drive integration is available.
 * Requires dev build with @react-native-google-signin.
 */
export function isGoogleDriveAvailable(): boolean {
  return Platform.OS === 'android';
}
