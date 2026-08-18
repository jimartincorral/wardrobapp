import type { DatabaseAdapter } from './client';
import { isLegacyStructuredTag } from '../utils/style-tags';

const STRIP_LEGACY_TAGS_KEY = 'migration_strip_weather_occasion_tags';

function parseTags(value: unknown): string[] | null {
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
    const tags = parseTags(row.tags);
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

export async function runDataMigrations(db: DatabaseAdapter): Promise<void> {
  try {
    await stripLegacyStructuredTags(db);
  } catch (error) {
    // A failed data migration must not stop the app from opening -- the tags it
    // cleans are cosmetic, and splitStructuredTags filters them out on read
    // anyway. Left unflagged so it retries on the next launch.
    console.warn('Data migration failed, continuing:', error);
  }
}

export const __testing = { STRIP_LEGACY_TAGS_KEY };
