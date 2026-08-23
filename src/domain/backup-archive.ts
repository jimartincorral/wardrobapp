/**
 * Deciding whether a backup archive can be restored.
 *
 * Pure, and separated from the extraction and swapping on purpose: these checks
 * run *before* anything is overwritten, so every rejection here is free — the
 * wardrobe is still untouched. The parts that can do damage live in
 * `services/backup-service`, which is where the filesystem and zip handling
 * belong.
 *
 * Extracted because importing the service pulls in AsyncStorage and
 * expo-file-system at module scope, so its rules could not be exercised — or
 * ported — without a React Native runtime.
 */

/**
 * The format this build writes and reads.
 *
 * A layout version shared by the .zip archives and the older folder backups —
 * they hold the same three entries, so a .zip backup is literally a zipped
 * folder backup and both restore through the same code.
 */
export const BACKUP_VERSION = 3;

/** Formats this build can still read, beyond the current one. */
export const LEGACY_BACKUP_VERSIONS = [1, 2] as const;

export const MANIFEST_NAME = 'manifest.json';
export const LEGACY_PAYLOAD_NAME = 'backup.json';
/** The database entry inside an archive; the same name the live one has. */
export const ARCHIVE_DB_FILENAME = 'wardrobapp.db';
/** The photo folder *inside* an archive, not the live one. */
export const ARCHIVE_IMAGES_DIRNAME = 'images';

export type ArchiveManifest = {
  version: number;
  created_at?: string;
  image_count?: number;
};

/**
 * Read a manifest and decide whether this build can restore it.
 *
 * The messages name both versions, because "Unsupported backup version" on its
 * own leaves someone with no idea whether to update the app or give up on the
 * file.
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
      `Invalid backup: ${ARCHIVE_DB_FILENAME} is missing from the archive. Nothing was changed.`
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

/**
 * Check a legacy v1/v2 payload before it is applied. Same contract as
 * `parseArchiveManifest`: reject while the wardrobe is still untouched.
 */
export function checkLegacyPayload(payload: { version: number; database?: string }): void {
  if (!LEGACY_BACKUP_VERSIONS.includes(payload.version as 1 | 2)) {
    throw new Error(
      `Unsupported backup format ${payload.version}; this app reads ` +
        `${LEGACY_BACKUP_VERSIONS.join(', ')} and ${BACKUP_VERSION}.`
    );
  }
  if (!payload.database) {
    throw new Error('Invalid backup: it contains no database. Nothing was changed.');
  }
}
