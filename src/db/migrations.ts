import type { DatabaseAdapter } from './client';
import { isLegacyStructuredTag } from '../utils/style-tags';
import { toStoredImageRef } from '../utils/image-paths';

const STRIP_LEGACY_TAGS_KEY = 'migration_strip_weather_occasion_tags';
const BARE_IMAGE_REFS_KEY = 'migration_bare_image_refs';

/** Parse a JSON string-array column, or null when it is absent/unreadable. */
function parseStringArrayColumn(value: unknown): string[] | null {
  if (typeof value !== 'string' || !value.trim()) return null;
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map(String) : null;
  } catch {
    return null;
  }
}

/**
 * Remove weather and occasion values from stored garment tags.
 *
 * Both used to be written into the tags array as structured values. Weather is
 * gone and occasion is now derived from the garment's type, so leaving them in
 * place would make them reappear as ordinary user-typed tags on the garment
 * screen and in tag autocomplete.
 *
 * Guarded by a flag in user_preferences so it only runs once, and it only
 * writes rows that actually change.
 */
async function stripLegacyStructuredTags(db: DatabaseAdapter): Promise<void> {
  const done = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM user_preferences WHERE key = ?',
    STRIP_LEGACY_TAGS_KEY
  );
  if (done) return;

  const rows = await db.getAllAsync<{ id: string; tags: string }>(
    'SELECT id, tags FROM garments'
  );

  for (const row of rows) {
    const tags = parseStringArrayColumn(row.tags);
    if (!tags) continue;

    const cleaned = tags.filter(tag => !isLegacyStructuredTag(tag));
    if (cleaned.length === tags.length) continue;

    await db.runAsync('UPDATE garments SET tags = ? WHERE id = ?', JSON.stringify(cleaned), row.id);
  }

  await db.runAsync(
    'INSERT INTO user_preferences (key, value) VALUES (?, ?)',
    STRIP_LEGACY_TAGS_KEY,
    new Date().toISOString()
  );
}

/**
 * Rewrite absolute garment photo paths to bare filenames.
 *
 * Older builds stored the full path, including a documents directory whose
 * location is not guaranteed stable across installs, so those paths can point
 * nowhere after a restore onto a reinstalled app. Reads already re-base them
 * (see utils/image-paths), so this is a tidy-up that makes the stored data
 * portable rather than a correctness fix — which is exactly why it is safe to
 * retry and safe to fail.
 */
async function storeBareImageRefs(db: DatabaseAdapter): Promise<void> {
  const done = await db.getFirstAsync<{ value: string }>(
    'SELECT value FROM user_preferences WHERE key = ?',
    BARE_IMAGE_REFS_KEY
  );
  if (done) return;

  type ImageRow = {
    id: string;
    image_uri: string | null;
    image_uri_nobg: string | null;
    image_uris: string | null;
    image_uris_nobg: string | null;
  };

  const rows = await db.getAllAsync<ImageRow>(
    'SELECT id, image_uri, image_uri_nobg, image_uris, image_uris_nobg FROM garments'
  );

  const bareList = (value: string | null) => {
    const parsed = parseStringArrayColumn(value);
    return parsed ? JSON.stringify(parsed.map(toStoredImageRef)) : value;
  };

  for (const row of rows) {
    const nextUri = row.image_uri ? toStoredImageRef(row.image_uri) : row.image_uri;
    const nextNoBg = row.image_uri_nobg ? toStoredImageRef(row.image_uri_nobg) : row.image_uri_nobg;
    const nextUris = bareList(row.image_uris);
    const nextNoBgUris = bareList(row.image_uris_nobg);

    if (
      nextUri === row.image_uri &&
      nextNoBg === row.image_uri_nobg &&
      nextUris === row.image_uris &&
      nextNoBgUris === row.image_uris_nobg
    ) {
      continue;
    }

    await db.runAsync(
      'UPDATE garments SET image_uri = ?, image_uri_nobg = ?, image_uris = ?, image_uris_nobg = ? WHERE id = ?',
      nextUri,
      nextNoBg,
      nextUris,
      nextNoBgUris,
      row.id
    );
  }

  await db.runAsync(
    'INSERT INTO user_preferences (key, value) VALUES (?, ?)',
    BARE_IMAGE_REFS_KEY,
    new Date().toISOString()
  );
}

export async function runDataMigrations(db: DatabaseAdapter): Promise<void> {
  // Run independently: neither migration is a prerequisite for the other, so one
  // failing must not skip the rest. A failure must also not stop the app from
  // opening -- both are recoverable on read (tags are filtered by
  // splitStructuredTags, image paths are re-based by resolveImageRef) -- so each
  // is left unflagged to retry on the next launch.
  for (const migration of [stripLegacyStructuredTags, storeBareImageRefs]) {
    try {
      await migration(db);
    } catch (error) {
      console.warn(`Data migration ${migration.name} failed, continuing:`, error);
    }
  }
}

export const __testing = { STRIP_LEGACY_TAGS_KEY, BARE_IMAGE_REFS_KEY };
