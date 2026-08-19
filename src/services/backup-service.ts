import AsyncStorage from '@react-native-async-storage/async-storage';
import { Directory, File, Paths } from 'expo-file-system';
import { closeDatabase, getDatabase } from '../db/client';

/**
 * Backup and restore.
 *
 * A backup is a single .zip containing `manifest.json`, the raw SQLite database
 * and an `images/` folder of the garment photos. Single-file on purpose: it can
 * be copied off the device, sent to another phone, or kept anywhere the user
 * likes without a folder structure to preserve.
 *
 * Everything is staged on disk and zipped natively, so memory stays flat no
 * matter how large the wardrobe is. The previous implementation built the whole
 * archive in JS — every photo as bytes, plus the finished archive as one
 * Uint8Array — which put peak usage at roughly twice the backup size and ran
 * the app out of heap on wardrobes of a few hundred megabytes.
 *
 * The trade is disk for heap: staging holds a second copy of the photos while
 * the archive is built, so a backup needs roughly twice the wardrobe's size
 * free in the cache directory. Both copies are deleted as soon as the backup
 * lands in its destination. Running out of disk fails loudly and recoverably;
 * running out of heap killed the app.
 *
 * Restore also accepts the legacy folder format and the legacy .json/.zip
 * archives, so older backups stay usable.
 *
 * Android-only: the destination is a user-granted SAF directory, and the
 * archive is zipped natively by react-native-zip-archive.
 */

let _fs: typeof import('expo-file-system/legacy') | null = null;
let _zip: typeof import('react-native-zip-archive') | null = null;

const DOWNLOADS_DIR_URI_KEY = 'backup_downloads_directory_uri';
const BACKUP_PREFIX = 'wardrobapp-backup-';
const MANIFEST_NAME = 'manifest.json';
const LEGACY_PAYLOAD_NAME = 'backup.json';
const DB_FILENAME = 'wardrobapp.db';
/** Name of the photo folder *inside* an archive. */
const IMAGES_DIRNAME = 'images';
/** Name of the live photo folder in the app's documents directory. */
const LIVE_IMAGES_DIRNAME = 'garment-images';

/**
 * Names used while a restore is in flight.
 *
 * The incoming copies are built beside the files they replace — same volume, so
 * the final swap is a rename rather than another copy — and the displaced
 * originals are kept under `.previous` until the swap has fully succeeded.
 */
const DB_STAGING_NAME = `${DB_FILENAME}.incoming`;
const DB_PREVIOUS_NAME = `${DB_FILENAME}.previous`;
const IMAGES_STAGING_DIRNAME = `${LIVE_IMAGES_DIRNAME}.incoming`;
const IMAGES_PREVIOUS_DIRNAME = `${LIVE_IMAGES_DIRNAME}.previous`;
const DB_SIDECAR_SUFFIXES = ['-wal', '-shm'];

/**
 * Layout version shared by the .zip archives and the older folder backups —
 * they hold the same three entries, so a .zip backup is literally a zipped
 * folder backup and both restore through the same code.
 */
const BACKUP_VERSION = 3;

function getFS() {
  if (!_fs) {
    _fs = require('expo-file-system/legacy');
  }
  return _fs!;
}

function getZip() {
  if (!_zip) {
    _zip = require('react-native-zip-archive');
  }
  return _zip!;
}

function getPreferredDownloadsDirUri() {
  return getFS().StorageAccessFramework.getUriForDirectoryInRoot('Download');
}

function getBackupFilename() {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  return `${BACKUP_PREFIX}${timestamp}.zip`;
}

async function withClosedDatabase<T>(operation: () => Promise<T>): Promise<T> {
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

export type BackupProgress = {
  phase: 'preparing' | 'archiving' | 'saving' | 'done';
  percent: number;
  message: string;
};

type BackupProgressCallback = (progress: BackupProgress) => void;

function toErrorText(error: unknown) {
  if (error instanceof Error) return error.message || (error as any).code || error.name;
  if (typeof error === 'string') return error;
  try {
    return JSON.stringify(error);
  } catch {
    return String(error);
  }
}

/** Wrap a step so any thrown error identifies which stage failed. */
async function step<T>(label: string, operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    throw new Error(`${label}: ${toErrorText(error)}`);
  }
}

/** Yield to the RN event loop so progress can render between synchronous copies. */
function yieldToUi() {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function newSqliteDir() {
  return new Directory(Paths.document, 'SQLite');
}
function newImagesDir() {
  return new Directory(Paths.document, LIVE_IMAGES_DIRNAME);
}

function onlyFiles(entries: (Directory | File)[]) {
  return entries.filter((e): e is File => e instanceof File);
}

function onlyDirectories(entries: (Directory | File)[]) {
  return entries.filter((e): e is Directory => e instanceof Directory);
}

/** Android SAF folders are exposed as content:// URIs. */
function isContentUri(uri: string) {
  return uri.startsWith('content://');
}

/**
 * react-native-zip-archive works on plain filesystem paths, not `file://` URIs.
 * Percent-encoding has to come back out too: the URI form escapes spaces and
 * other characters that are perfectly legal in a path.
 */
export function toNativePath(uri: string): string {
  if (!uri.startsWith('file://')) return uri;
  return decodeURIComponent(uri.slice('file://'.length));
}

/** Replace a working directory with an empty one. */
function resetDirectory(dir: Directory): Directory {
  if (dir.exists) dir.delete();
  dir.create({ intermediates: true, idempotent: true });
  return dir;
}

function deleteQuietly(entry: Directory | File) {
  try {
    if (entry.exists) entry.delete();
  } catch {
    // Cleanup is best-effort: a leftover working copy costs disk, and failing
    // the caller over it would replace a working outcome with an error.
  }
}

/** How much of a file crosses into JS at a time when streaming into SAF. */
const SAF_CHUNK_BYTES = 4 * 1024 * 1024;

/**
 * Copy a local file to an Android SAF `content://` destination.
 *
 * Neither native copy can do this. `File.copy` reaches for `javaFile` and
 * throws outright ("This method cannot be used with content URIs"). The legacy
 * `copyAsync` branches on the *source* scheme: given a file:// source it calls
 * `toFile()` on the destination, which turns
 * `content://…/tree/primary:Download/Wardrobapp/document/…` into the literal
 * path `/tree/primary:Download/Wardrobapp/document/…` and fails with
 * "directory cannot be created".
 *
 * So stream it here instead: read the source through a file handle and append
 * each chunk to the destination, which SAF does support — it opens the document
 * in "wa" mode. Only one chunk is ever in memory, whatever the file size.
 */
async function streamFileInto(
  src: File,
  dest: File,
  onChunk?: (bytesWritten: number) => void
): Promise<void> {
  const handle = src.open();
  try {
    let written = 0;
    // The first write truncates, the rest append.
    let append = false;
    for (;;) {
      const chunk = handle.readBytes(SAF_CHUNK_BYTES);
      if (chunk.length === 0) break;
      dest.write(chunk, { append });
      append = true;
      written += chunk.length;
      onChunk?.(written);
      await yieldToUi();
    }
  } finally {
    handle.close();
  }
}

/**
 * Copy a file into a directory, picking whichever mechanism handles the URI
 * schemes involved: a native copy when both sides are ordinary paths, the
 * legacy `copyAsync` when reading *from* a content:// URI (that direction it
 * streams correctly through the content resolver), and a chunked stream when
 * writing *to* one.
 */
async function copyFileInto(src: File, destDir: Directory, name: string): Promise<void> {
  if (isContentUri(destDir.uri)) {
    await streamFileInto(src, destDir.createFile(name, null));
  } else if (isContentUri(src.uri)) {
    await getFS().copyAsync({ from: src.uri, to: new File(destDir, name).uri });
  } else {
    // Copy to an explicit File, not to the directory: given a Directory the
    // native copy derives the target name from the *source* and ignores `name`
    // entirely, which would land a staged database on top of the live one.
    src.copy(new File(destDir, name));
  }
}

/**
 * Resolve the base directory backups are written into: a user-granted SAF
 * folder, persisted across sessions.
 */
async function getBackupBaseDirectory(): Promise<Directory> {
  const saved = await AsyncStorage.getItem(DOWNLOADS_DIR_URI_KEY);
  if (saved) {
    try {
      const dir = new Directory(saved);
      if (dir.exists) {
        dir.list(); // Throws if the persisted permission is no longer valid.
        return dir;
      }
    } catch {
      // Fall through and re-request access below.
    }
    await AsyncStorage.removeItem(DOWNLOADS_DIR_URI_KEY);
  }

  let initialUri: string | undefined;
  try {
    initialUri = getPreferredDownloadsDirUri();
  } catch {
    initialUri = undefined;
  }

  const picked = await Directory.pickDirectoryAsync(initialUri);
  await AsyncStorage.setItem(DOWNLOADS_DIR_URI_KEY, picked.uri);
  return picked;
}

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

/**
 * Lay the backup out on disk: the database, every photo, and a manifest. Each
 * file is copied natively, so the only thing that ever reaches the JS heap is
 * the manifest.
 */
async function stageBackupContents(
  staging: Directory,
  onProgress?: BackupProgressCallback
): Promise<void> {
  emitProgress(onProgress, 'preparing', 5, 'Copying database');

  const dbFile = new File(newSqliteDir(), DB_FILENAME);
  if (dbFile.exists) {
    dbFile.copy(staging);
  }

  const stagedImages = new Directory(staging, IMAGES_DIRNAME);
  stagedImages.create({ intermediates: true, idempotent: true });

  let copied = 0;
  const sourceImages = newImagesDir();
  if (sourceImages.exists) {
    const files = onlyFiles(sourceImages.list());
    const total = files.length || 1;

    for (const file of files) {
      try {
        file.copy(stagedImages);
        copied++;
      } catch {
        // Skip files that can't be read.
      }
      emitProgress(
        onProgress,
        'preparing',
        10 + (copied / total) * 40,
        `Collecting images (${copied}/${files.length})`
      );
      if (copied % 20 === 0) await yieldToUi();
    }
  }

  const manifest = new File(staging, MANIFEST_NAME);
  manifest.create({ overwrite: true });
  manifest.write(
    JSON.stringify({
      version: BACKUP_VERSION,
      created_at: new Date().toISOString(),
      image_count: copied,
    })
  );
}

/** Zip a staged directory natively, reporting the module's own progress. */
async function zipDirectory(
  source: Directory,
  target: File,
  onProgress?: BackupProgressCallback
): Promise<void> {
  const { zip, subscribe, NO_COMPRESSION } = getZip();

  // Photos are already-compressed JPEG/PNG and dominate the archive, so
  // deflating would burn CPU for almost nothing. The database is the only
  // compressible entry and it is small — it holds image paths, not image data.
  const subscription = subscribe(({ progress }) => {
    emitProgress(
      onProgress,
      'archiving',
      55 + progress * 38,
      `Building ZIP archive (${Math.round(progress * 100)}%)`
    );
  });

  try {
    await zip(toNativePath(source.uri), toNativePath(target.uri), NO_COMPRESSION);
  } finally {
    subscription.remove();
  }
}

/** Put the finished archive in the user's chosen folder. */
async function saveArchiveTo(
  base: Directory,
  archive: File,
  name: string,
  onProgress?: BackupProgressCallback
): Promise<string> {
  if (isContentUri(base.uri)) {
    const dest = base.createFile(name, 'application/zip');
    const total = archive.size || 1;
    await streamFileInto(archive, dest, (written) => {
      emitProgress(
        onProgress,
        'saving',
        94 + (written / total) * 5,
        'Saving backup file'
      );
    });
    return dest.uri;
  }

  const dest = new File(base, name);
  if (dest.exists) dest.delete();
  archive.copy(dest);
  return dest.uri;
}

/**
 * Export the database and images as a single .zip backup file.
 *
 * Returns the backup file URI and its size in bytes.
 */
export async function createBackup(options?: { onProgress?: BackupProgressCallback }): Promise<{ uri: string; size: number }> {
  const onProgress = options?.onProgress;
  emitProgress(onProgress, 'preparing', 0, 'Starting backup');

  const base = await step('Requesting folder access', () => getBackupBaseDirectory());
  const name = getBackupFilename();

  const work = resetDirectory(new Directory(Paths.cache, 'backup-work'));
  try {
    const staging = new Directory(work, 'staging');
    staging.create({ intermediates: true, idempotent: true });

    await step('Collecting backup contents', () =>
      withClosedDatabase(() => stageBackupContents(staging, onProgress))
    );

    const archive = new File(work, name);
    await step('Building ZIP archive', () => zipDirectory(staging, archive, onProgress));

    // Read the size before the working directory goes away.
    const size = archive.size;

    emitProgress(onProgress, 'saving', 94, 'Saving backup file');
    const uri = await step('Saving backup file', () =>
      saveArchiveTo(base, archive, name, onProgress)
    );

    emitProgress(onProgress, 'done', 100, 'Backup complete');
    return { uri, size };
  } finally {
    deleteQuietly(work);
  }
}

function getSafEntryName(uri: string) {
  const decoded = decodeURIComponent(uri);
  return decoded.slice(decoded.lastIndexOf('/') + 1);
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

async function cleanupTempFile(uri: string) {
  try {
    await getFS().deleteAsync(uri, { idempotent: true });
  } catch {
    // Ignore cleanup errors.
  }
}

/**
 * Ensure the archive is a local file:// path so the native unzip can read it.
 * content:// archives are streamed to a temp file via the native copyAsync.
 */
async function materializeArchiveLocally(
  backupUri: string,
  isJson: boolean
): Promise<{ uri: string; temporary: boolean }> {
  if (!isContentUri(backupUri)) return { uri: backupUri, temporary: false };
  await ensureTempBackupDir();
  const localUri = `${getTempBackupDir()}restore-src${isJson ? '.json' : '.zip'}`;
  await getFS().copyAsync({ from: backupUri, to: localUri });
  return { uri: localUri, temporary: true };
}

export type ArchiveManifest = {
  version: number;
  created_at?: string;
  image_count?: number;
};

/**
 * Read a manifest and decide whether this build can restore it.
 *
 * Called before anything is overwritten, so every rejection here is free: the
 * wardrobe is still untouched. The messages name both versions, because
 * "Unsupported backup version" on its own leaves the user with no idea whether
 * to update the app or give up on the file.
 */
export function parseArchiveManifest(text: string): ArchiveManifest {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new Error(`Invalid backup: ${MANIFEST_NAME} is not readable JSON.`);
  }

  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error(`Invalid backup: ${MANIFEST_NAME} does not describe a backup.`);
  }

  const { version, created_at: createdAt, image_count: imageCount } = raw as Record<string, unknown>;

  if (typeof version !== 'number' || !Number.isInteger(version)) {
    throw new Error(`Invalid backup: ${MANIFEST_NAME} has no version number.`);
  }
  if (version > BACKUP_VERSION) {
    throw new Error(
      `This backup was made by a newer version of Wardrobapp (backup format ${version}; ` +
        `this app reads ${BACKUP_VERSION}). Update the app and try again.`
    );
  }
  if (version < BACKUP_VERSION) {
    throw new Error(
      `Unsupported backup format ${version}; this app reads ${BACKUP_VERSION}.`
    );
  }

  return {
    version,
    created_at: typeof createdAt === 'string' ? createdAt : undefined,
    image_count: typeof imageCount === 'number' ? imageCount : undefined,
  };
}

/**
 * Reject an archive that is missing pieces, before the live data is touched.
 *
 * The database check is the important one. Deleting the photo directory used to
 * be unconditional while restoring the database was not, so an archive that had
 * lost its database wiped every photo and reported success — leaving rows that
 * all pointed at files that no longer existed.
 */
export function checkArchiveCompleteness(archive: {
  manifest: ArchiveManifest;
  hasDatabase: boolean;
  imageCount: number;
}): void {
  if (!archive.hasDatabase) {
    throw new Error(
      `Invalid backup: ${DB_FILENAME} is missing from the archive. Nothing was changed.`
    );
  }

  const expected = archive.manifest.image_count;
  if (typeof expected === 'number' && archive.imageCount < expected) {
    throw new Error(
      `Incomplete backup: the manifest lists ${expected} photo(s) but only ` +
        `${archive.imageCount} are present, so the archive is truncated. Nothing was changed.`
    );
  }
}

/** Remove the WAL sidecars belonging to one database file, if present. */
function deleteDbSidecars(dbName: string) {
  const sqliteDir = newSqliteDir();
  for (const suffix of DB_SIDECAR_SUFFIXES) {
    deleteQuietly(new File(sqliteDir, `${dbName}${suffix}`));
  }
}

/** Throw away a half-built restore. Safe to call at any point. */
function discardStagedRestore() {
  deleteQuietly(new File(newSqliteDir(), DB_STAGING_NAME));
  deleteDbSidecars(DB_STAGING_NAME);
  deleteQuietly(new Directory(Paths.document, IMAGES_STAGING_DIRNAME));
}

/** Throw away the displaced originals once the new data is safely in place. */
function discardReplacedData() {
  deleteQuietly(new File(newSqliteDir(), DB_PREVIOUS_NAME));
  deleteQuietly(new Directory(Paths.document, IMAGES_PREVIOUS_DIRNAME));
}

/**
 * Confirm SQLite can actually read the staged database before it replaces the
 * live one. A truncated or garbage file inside an otherwise valid archive would
 * otherwise be installed happily and only fail on the next query, by which
 * point the original is gone.
 */
async function verifyStagedDatabase(): Promise<void> {
  const SQLite = await import('expo-sqlite');
  let handle: Awaited<ReturnType<typeof SQLite.openDatabaseAsync>> | null = null;

  try {
    handle = await SQLite.openDatabaseAsync(DB_STAGING_NAME, { useNewConnection: true });

    const integrity = await handle.getFirstAsync<{ integrity_check: string }>(
      'PRAGMA integrity_check;'
    );
    if (integrity?.integrity_check !== 'ok') {
      throw new Error(
        `it failed SQLite's integrity check (${integrity?.integrity_check ?? 'no result'})`
      );
    }

    // A readable database that has no garments table is some other app's file.
    await handle.getFirstAsync('SELECT count(*) AS count FROM garments;');
  } catch (error) {
    throw new Error(`Invalid backup: ${toErrorText(error)}. Nothing was changed.`);
  } finally {
    if (handle) {
      try {
        await handle.closeAsync();
      } catch {
        // The verification handle is disposable either way.
      }
    }
    // Opening the file may have created sidecars; they must not travel with it.
    deleteDbSidecars(DB_STAGING_NAME);
  }
}

/**
 * How a particular backup format produces its replacement data. Both are
 * required: a restore that cannot supply a database is not a restore.
 */
type RestoreSources = {
  writeDatabase: (destinationDir: Directory, name: string) => Promise<void>;
  writeImages: (destination: Directory) => Promise<void>;
};

/**
 * Install a restore as close to atomically as the filesystem allows.
 *
 * Build the replacement beside the live data, verify it, then swap both halves
 * by rename and only then drop the originals. Any failure before the swap
 * leaves the wardrobe untouched; any failure during it is rolled back.
 *
 * The cost is disk: staging holds a second copy of the photos, so a restore
 * needs roughly the archive's size free in the documents directory. That is the
 * same trade the backup path makes, and it buys the property that matters —
 * a failed restore leaves the user exactly where they started rather than with
 * neither their old wardrobe nor a complete new one.
 */
async function commitRestore(sources: RestoreSources): Promise<void> {
  const sqliteDir = newSqliteDir();
  sqliteDir.create({ intermediates: true, idempotent: true });

  // An earlier restore may have died midway; its leftovers are not ours to keep.
  discardStagedRestore();

  try {
    await sources.writeDatabase(sqliteDir, DB_STAGING_NAME);

    const stagedDb = new File(sqliteDir, DB_STAGING_NAME);
    if (!stagedDb.exists || stagedDb.size === 0) {
      throw new Error(`Invalid backup: ${DB_FILENAME} is empty. Nothing was changed.`);
    }

    await sources.writeImages(resetDirectory(new Directory(Paths.document, IMAGES_STAGING_DIRNAME)));
    await verifyStagedDatabase();
  } catch (error) {
    discardStagedRestore();
    throw error;
  }

  await swapInStagedRestore();
}

/**
 * Move the staged data into place, putting everything back if any step fails.
 *
 * Ordering matters: both originals are moved aside before either replacement
 * moves in, so at every intermediate point the user's data still exists under a
 * known name and the rollback can find it.
 */
async function swapInStagedRestore(): Promise<void> {
  const sqliteDir = newSqliteDir();
  let dbMovedAside = false;
  let imagesMovedAside = false;
  let dbSwappedIn = false;

  try {
    // A stale WAL would be replayed onto the *restored* database, grafting
    // fragments of the old wardrobe onto it. Most likely to exist precisely
    // when someone is restoring, because something already went wrong.
    deleteDbSidecars(DB_FILENAME);

    const liveDb = new File(sqliteDir, DB_FILENAME);
    if (liveDb.exists) {
      liveDb.rename(DB_PREVIOUS_NAME);
      dbMovedAside = true;
    }

    const liveImages = newImagesDir();
    if (liveImages.exists) {
      liveImages.rename(IMAGES_PREVIOUS_DIRNAME);
      imagesMovedAside = true;
    }

    new File(sqliteDir, DB_STAGING_NAME).rename(DB_FILENAME);
    dbSwappedIn = true;

    new Directory(Paths.document, IMAGES_STAGING_DIRNAME).rename(LIVE_IMAGES_DIRNAME);
  } catch (error) {
    try {
      if (dbSwappedIn) new File(sqliteDir, DB_FILENAME).rename(DB_STAGING_NAME);
      if (dbMovedAside) new File(sqliteDir, DB_PREVIOUS_NAME).rename(DB_FILENAME);
      if (imagesMovedAside) {
        deleteQuietly(newImagesDir());
        new Directory(Paths.document, IMAGES_PREVIOUS_DIRNAME).rename(LIVE_IMAGES_DIRNAME);
      }
    } catch (rollbackError) {
      // Say exactly where the data is rather than pretending it is lost.
      throw new Error(
        `Restore failed (${toErrorText(error)}) and the wardrobe could not be put back ` +
          `(${toErrorText(rollbackError)}). Your original data is still on the device as ` +
          `${DB_PREVIOUS_NAME} and ${IMAGES_PREVIOUS_DIRNAME}.`
      );
    }

    discardStagedRestore();
    throw new Error(`Restore failed: ${toErrorText(error)}. Your wardrobe was left unchanged.`);
  }

  // Only now is the displaced copy expendable.
  discardReplacedData();
}

/** Copy archive photos into a staging directory. */
async function copyImagesInto(images: File[], destination: Directory): Promise<void> {
  let handled = 0;
  for (const image of images) {
    await copyFileInto(image, destination, image.name);
    if (++handled % 10 === 0) await yieldToUi();
  }
}

/** Write base64 photos from a legacy payload into a staging directory. */
async function writeBase64ImagesInto(
  images: BackupImage[],
  destination: Directory
): Promise<void> {
  const fs = getFS();
  let handled = 0;
  for (const image of images) {
    await fs.writeAsStringAsync(new File(destination, image.name).uri, image.data, {
      encoding: fs.EncodingType.Base64,
    });
    if (++handled % 10 === 0) await yieldToUi();
  }
}

/** Write a base64 database from a legacy payload into a staging directory. */
async function writeBase64DatabaseInto(
  destinationDir: Directory,
  name: string,
  base64: string
): Promise<void> {
  const fs = getFS();
  await fs.writeAsStringAsync(new File(destinationDir, name).uri, base64, {
    encoding: fs.EncodingType.Base64,
  });
}

/**
 * Restore from a backup. Handles the current single-file .zip archives as well
 * as the legacy folder-based format and legacy .json archives.
 */
export async function restoreBackup(backupUri: string): Promise<void> {
  let folder: Directory | null = null;
  try {
    const dir = new Directory(backupUri);
    if (dir.exists) folder = dir;
  } catch {
    folder = null;
  }

  if (folder) {
    await restoreFolderBackup(folder);
    return;
  }

  await restoreArchiveBackup(backupUri);
}

/**
 * Delete a local backup. Handles both the single-file (.zip/.json) archives and
 * the legacy folder-based backups. Deleting an already-missing backup is a
 * no-op so the caller can safely refresh its list afterwards.
 */
export async function deleteBackup(backupUri: string): Promise<void> {
  try {
    const dir = new Directory(backupUri);
    if (dir.exists) {
      dir.delete();
      return;
    }
  } catch {
    // Not a directory (or no longer accessible) — fall through to file delete.
  }

  const file = new File(backupUri);
  if (file.exists) file.delete();
}

/** True for the "user dismissed the picker" error thrown by File.pickFileAsync. */
function isPickerCancellation(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /cancel/i.test(message);
}

/**
 * Let the user pick a backup archive from anywhere on the device — e.g. one
 * sent from another phone or copied from another device — and restore it.
 * Returns false if the user dismissed the file picker.
 */
export async function restoreBackupFromFile(): Promise<boolean> {
  let picked: File | File[];
  try {
    picked = await File.pickFileAsync();
  } catch (error) {
    if (isPickerCancellation(error)) return false;
    throw error;
  }

  const file = Array.isArray(picked) ? picked[0] : picked;
  if (!file) return false;

  // Stream the picked file into a temp file with a known extension, then reuse
  // the shared archive-restore path. Going through a file:// temp avoids
  // depending on the provider-specific shape of the picker's content:// URI
  // (whose last path segment often isn't the real filename, so format detection
  // can't use it). copyAsync streams natively — reading the whole archive into
  // JS first would OutOfMemory on large backups.
  await ensureTempBackupDir();
  const isJson = (file.name ?? '').toLowerCase().endsWith('.json');
  const destUri = `${getTempBackupDir()}${isJson ? 'imported-backup.json' : 'imported-backup.zip'}`;
  await getFS().copyAsync({ from: file.uri, to: destUri });

  try {
    await restoreBackup(destUri);
  } finally {
    await cleanupTempFile(destUri);
  }

  return true;
}

/** Restore a folder-based backup by copying files back natively. */
async function restoreFolderBackup(dir: Directory): Promise<void> {
  const entries = dir.list();
  const manifestFile = onlyFiles(entries).find((f) => f.name === MANIFEST_NAME);
  if (!manifestFile) {
    throw new Error('Invalid backup: manifest.json not found');
  }

  const manifest = parseArchiveManifest(manifestFile.textSync());

  const dbEntry = onlyFiles(entries).find((f) => f.name === DB_FILENAME);
  const archiveImages = onlyDirectories(entries).find((e) => e.name === IMAGES_DIRNAME) ?? null;
  const imageFiles = archiveImages ? onlyFiles(archiveImages.list()) : [];

  checkArchiveCompleteness({
    manifest,
    hasDatabase: Boolean(dbEntry),
    imageCount: imageFiles.length,
  });

  await withClosedDatabase(() =>
    commitRestore({
      writeDatabase: (dir, name) => copyFileInto(dbEntry!, dir, name),
      writeImages: (destination) => copyImagesInto(imageFiles, destination),
    })
  );
}

export type ArchiveLayout = 'folder' | 'legacy-archive' | 'nested' | 'unknown';

/**
 * Work out what an extracted archive contains from the names at one level.
 *
 * `folder` is the current layout (and the older folder backups). `legacy-archive`
 * is the v1/v2 zip whose database lived base64-encoded inside backup.json.
 * `nested` covers zip implementations that wrap everything in a single top-level
 * directory — the caller descends and classifies again rather than failing.
 */
export function classifyArchiveEntries(
  fileNames: string[],
  directoryNames: string[]
): ArchiveLayout {
  if (fileNames.includes(MANIFEST_NAME)) return 'folder';
  if (fileNames.includes(LEGACY_PAYLOAD_NAME)) return 'legacy-archive';
  if (directoryNames.length === 1) return 'nested';
  return 'unknown';
}

function classifyDirectory(dir: Directory): ArchiveLayout {
  const entries = dir.list();
  return classifyArchiveEntries(
    onlyFiles(entries).map((f) => f.name),
    onlyDirectories(entries).map((d) => d.name)
  );
}

/** Restore an extracted v1/v2 archive: backup.json plus an images/ folder. */
async function restoreExtractedLegacyArchive(root: Directory): Promise<void> {
  const payloadFile = onlyFiles(root.list()).find((f) => f.name === LEGACY_PAYLOAD_NAME)!;

  // The v1/v2 payload embeds the database as base64. Reading it as a string is
  // safe: the database stores image *paths*, not the images themselves, so it
  // is a few megabytes at most. The photos are separate entries, already on
  // disk from the extraction, and get copied natively below.
  const payload = JSON.parse(payloadFile.textSync()) as BackupPayload;
  checkLegacyPayload(payload);

  const archiveImages = onlyDirectories(root.list()).find((d) => d.name === IMAGES_DIRNAME) ?? null;
  const imageFiles = archiveImages ? onlyFiles(archiveImages.list()) : [];

  await withClosedDatabase(() =>
    commitRestore({
      writeDatabase: (dir, name) => writeBase64DatabaseInto(dir, name, payload.database),
      writeImages: (destination) => copyImagesInto(imageFiles, destination),
    })
  );
}

/**
 * Check a legacy v1/v2 payload before it is applied. Same contract as
 * `parseArchiveManifest`: reject while the wardrobe is still untouched.
 */
export function checkLegacyPayload(payload: Pick<BackupPayload, 'version' | 'database'>): void {
  if (![1, 2].includes(payload.version)) {
    throw new Error(
      `Unsupported backup format ${payload.version}; this app reads 1, 2 and ${BACKUP_VERSION}.`
    );
  }
  if (!payload.database) {
    throw new Error('Invalid backup: it contains no database. Nothing was changed.');
  }
}

/** Restore the oldest format: one .json document with everything base64 inside. */
async function restoreLegacyJsonBackup(localUri: string): Promise<void> {
  const fs = getFS();
  const payload = JSON.parse(await fs.readAsStringAsync(localUri)) as BackupPayload;
  checkLegacyPayload(payload);

  const images = Array.isArray(payload.images) ? payload.images : [];
  await withClosedDatabase(() =>
    commitRestore({
      writeDatabase: (dir, name) => writeBase64DatabaseInto(dir, name, payload.database),
      writeImages: (destination) => writeBase64ImagesInto(images, destination),
    })
  );
}

/** Restore a single-file (.zip/.json) backup archive. */
async function restoreArchiveBackup(backupUri: string): Promise<void> {
  const filename = (await readBackupFileName(backupUri)).toLowerCase();
  const isJson = filename.endsWith('.json');
  const local = await materializeArchiveLocally(backupUri, isJson);

  try {
    if (isJson) {
      await restoreLegacyJsonBackup(local.uri);
      return;
    }

    // Extract natively: the archive never passes through the JS heap, so a
    // wardrobe of any size unpacks in the same memory as a tiny one.
    const work = resetDirectory(new Directory(Paths.cache, 'restore-work'));
    try {
      await step('Extracting backup archive', () =>
        getZip().unzip(toNativePath(local.uri), toNativePath(work.uri))
      );

      let root = work;
      let layout = classifyDirectory(root);
      if (layout === 'nested') {
        root = onlyDirectories(root.list())[0];
        layout = classifyDirectory(root);
      }

      if (layout === 'folder') {
        await restoreFolderBackup(root);
        return;
      }
      if (layout === 'legacy-archive') {
        await restoreExtractedLegacyArchive(root);
        return;
      }

      throw new Error('Invalid backup archive: no manifest.json found');
    } finally {
      deleteQuietly(work);
    }
  } finally {
    if (local.temporary) await cleanupTempFile(local.uri);
  }
}

/**
 * List available local backups.
 */
export async function listBackups(): Promise<{ name: string; uri: string }[]> {
  try {
    const savedUri = await AsyncStorage.getItem(DOWNLOADS_DIR_URI_KEY);
    if (!savedUri) return [];

    const base = new Directory(savedUri);
    if (!base.exists) return [];

    const entries = base.list();
    const folders = onlyDirectories(entries)
      .filter((d) => d.name.startsWith(BACKUP_PREFIX))
      .map((d) => ({ name: d.name, uri: d.uri }));
    // Keep listing any legacy single-file archives so old backups remain restorable.
    const archives = onlyFiles(entries)
      .filter((f) => f.name.startsWith(BACKUP_PREFIX) && (f.name.endsWith('.zip') || f.name.endsWith('.json')))
      .map((f) => ({ name: f.name, uri: f.uri }));

    return [...folders, ...archives].sort((a, b) => b.name.localeCompare(a.name));
  } catch {
    return [];
  }
}
