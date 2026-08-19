import { Platform } from 'react-native';
import { runDataMigrations } from './migrations';

/**
 * Unified database interface that works on both native (expo-sqlite) and web.
 * On web, uses a lightweight in-memory SQL adapter (no WASM) with localStorage persistence.
 * On native, uses expo-sqlite (full SQLite).
 */

export interface DatabaseAdapter {
  execAsync(sql: string): Promise<void>;
  runAsync(sql: string, ...params: any[]): Promise<void>;
  getFirstAsync<T = any>(sql: string, ...params: any[]): Promise<T | null>;
  getAllAsync<T = any>(sql: string, ...params: any[]): Promise<T[]>;
  closeAsync(): Promise<void>;
}

let db: DatabaseAdapter | null = null;
let dbInitPromise: Promise<DatabaseAdapter> | null = null;

/**
 * Held while the database *file* is being replaced or copied wholesale — a
 * backup or a restore. `getDatabase` waits on it rather than opening, because
 * both operations run with the connection deliberately closed and a caller that
 * reopened underneath them would be catastrophic:
 *
 *  - During a backup, reopening sets `journal_mode = WAL` and starts writing
 *    while the file is being copied, so the backup captures a torn database.
 *  - During a restore, reopening recreates the very WAL sidecars the restore
 *    deleted, and SQLite then replays that stale WAL onto the *restored* file,
 *    grafting fragments of the old wardrobe onto it.
 *
 * Nothing else stops that: screens refetch on focus, so navigating a tab during
 * a multi-minute backup was enough to trigger it.
 */
let maintenanceLock: Promise<void> | null = null;

export async function getDatabase(): Promise<DatabaseAdapter> {
  // Wait for a backup/restore to finish before touching the file it owns.
  while (maintenanceLock) {
    await maintenanceLock;
  }

  if (db) return db;
  if (dbInitPromise) return dbInitPromise;

  dbInitPromise = (async () => {
    const opened =
      Platform.OS === 'web'
        ? (await import('./web-memory-db')).createMemoryAdapter()
        : await openNativeDatabase();

    // Only publish the connection once the schema is in place. Assigning `db`
    // first made it visible to concurrent callers mid-initialization, so a
    // second screen mounting on a fresh install could query before
    // CREATE TABLE finished ("no such table: garments") or, on an upgrade, hit a
    // column a pending ALTER had not added yet.
    await initializeDatabase(opened);
    db = opened;
    return opened;
  })();

  try {
    return await dbInitPromise;
  } catch (error) {
    db = null;
    throw error;
  } finally {
    dbInitPromise = null;
  }
}

/**
 * Run an operation that owns the database file, with the connection closed and
 * every other caller held off until it finishes.
 */
export async function withDatabaseClosed<T>(operation: () => Promise<T>): Promise<T> {
  while (maintenanceLock) {
    await maintenanceLock;
  }

  let release!: () => void;
  maintenanceLock = new Promise<void>((resolve) => {
    release = resolve;
  });

  try {
    await closeDatabase();
    return await operation();
  } finally {
    // Drop the lock before reopening: getDatabase() is what reopens, and it
    // waits on this very promise.
    maintenanceLock = null;
    release();
    try {
      await getDatabase();
    } catch (error) {
      // Reopening is best-effort. Surfacing this would replace the caller's own
      // error -- the one describing what actually went wrong -- with a
      // secondary one, and the next getDatabase() will retry anyway.
      console.warn('Failed to reopen the database after maintenance:', error);
    }
  }
}

// ── Native: expo-sqlite ──────────────────────────────────────
async function openNativeDatabase(): Promise<DatabaseAdapter> {
  const SQLite = await import('expo-sqlite');
  let nativeDb = await SQLite.openDatabaseAsync('wardrobapp.db');

  const configureNativeDb = async () => {
    await nativeDb.execAsync('PRAGMA journal_mode = WAL;');
    await nativeDb.execAsync('PRAGMA foreign_keys = ON;');
  };

  await configureNativeDb();

  const sanitizeParams = (params: any[]) =>
    params.map((param) => {
      if (param === undefined) return null;
      if (typeof param === 'number' && !Number.isFinite(param)) return null;
      return param;
    });

  const shouldReconnect = (error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    return message.includes('NullPointerException') || message.includes('NativeDatabase');
  };

  const reconnect = async () => {
    try {
      await nativeDb.closeAsync();
    } catch {
      // Ignore close errors while recovering connection.
    }
    nativeDb = await SQLite.openDatabaseAsync('wardrobapp.db');
    await configureNativeDb();
  };

  const withReconnect = async <T>(operation: () => Promise<T>): Promise<T> => {
    try {
      return await operation();
    } catch (error) {
      if (!shouldReconnect(error)) throw error;
      await reconnect();
      return operation();
    }
  };

  return {
    execAsync: (sql) => withReconnect(() => nativeDb.execAsync(sql)),
    runAsync: (sql, ...params) => withReconnect(() => nativeDb.runAsync(sql, ...sanitizeParams(params)).then(() => {})),
    getFirstAsync: <T,>(sql: string, ...params: any[]) =>
      withReconnect(() => nativeDb.getFirstAsync<T>(sql, ...sanitizeParams(params))),
    getAllAsync: <T,>(sql: string, ...params: any[]) =>
      withReconnect(() => nativeDb.getAllAsync<T>(sql, ...sanitizeParams(params))),
    closeAsync: () => nativeDb.closeAsync(),
  };
}

// ── Schema initialization ────────────────────────────────────
async function initializeDatabase(database: DatabaseAdapter) {
  await database.execAsync(`
    CREATE TABLE IF NOT EXISTS garments (
      id TEXT PRIMARY KEY,
      image_uri TEXT NOT NULL,
      image_uri_nobg TEXT,
      image_uris TEXT NOT NULL DEFAULT '[]',
      image_uris_nobg TEXT NOT NULL DEFAULT '[]',
      category TEXT NOT NULL,
      subcategory TEXT,
      subcategories TEXT NOT NULL DEFAULT '[]',
      tags TEXT NOT NULL DEFAULT '[]',
      brand TEXT,
      color_primary TEXT NOT NULL DEFAULT '#000000',
      color_secondary TEXT,
      color_palette TEXT NOT NULL DEFAULT '[]',
      size TEXT,
      purchase_date TEXT,
      is_available INTEGER NOT NULL DEFAULT 1,
      unavailable_date TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS outfits (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      garment_ids TEXT NOT NULL DEFAULT '[]',
      occasion TEXT,
      season TEXT,
      created_at TEXT NOT NULL,
      is_suggested INTEGER NOT NULL DEFAULT 0,
      is_pinned INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS outfit_ratings (
      id TEXT PRIMARY KEY,
      outfit_id TEXT NOT NULL,
      rating INTEGER NOT NULL,
      feedback TEXT,
      rated_at TEXT NOT NULL,
      FOREIGN KEY (outfit_id) REFERENCES outfits(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS garment_pair_scores (
      garment_id_a TEXT NOT NULL,
      garment_id_b TEXT NOT NULL,
      score REAL NOT NULL DEFAULT 0,
      wear_count INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (garment_id_a, garment_id_b)
    );

    CREATE TABLE IF NOT EXISTS user_preferences (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
  `);

  // Indexes are created as separate statements: the web adapter parses one
  // statement at a time and cannot handle them batched with the CREATE TABLEs.
  const indexes = [
    'CREATE INDEX IF NOT EXISTS idx_garments_category ON garments(category)',
    'CREATE INDEX IF NOT EXISTS idx_garments_available ON garments(is_available)',
    'CREATE INDEX IF NOT EXISTS idx_outfit_ratings_outfit ON outfit_ratings(outfit_id)',
    'CREATE INDEX IF NOT EXISTS idx_pair_scores_score ON garment_pair_scores(score DESC)',
  ];
  for (const idx of indexes) {
    await database.execAsync(idx);
  }

  const safeAlter = async (sql: string) => {
    try {
      await database.execAsync(sql);
    } catch {
      // Ignore if column already exists
    }
  };

  // Migrations for upgraded installs
  await safeAlter('ALTER TABLE outfits ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0');

  // Ensure garments table has all newer columns on older app installs
  await safeAlter('ALTER TABLE garments ADD COLUMN image_uri_nobg TEXT');
  await safeAlter("ALTER TABLE garments ADD COLUMN image_uris TEXT NOT NULL DEFAULT '[]'");
  await safeAlter("ALTER TABLE garments ADD COLUMN image_uris_nobg TEXT NOT NULL DEFAULT '[]'");
  await safeAlter('ALTER TABLE garments ADD COLUMN subcategory TEXT');
  await safeAlter("ALTER TABLE garments ADD COLUMN subcategories TEXT NOT NULL DEFAULT '[]'");
  await safeAlter("ALTER TABLE garments ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'");
  await safeAlter('ALTER TABLE garments ADD COLUMN brand TEXT');
  await safeAlter("ALTER TABLE garments ADD COLUMN color_primary TEXT NOT NULL DEFAULT '#000000'");
  await safeAlter('ALTER TABLE garments ADD COLUMN color_secondary TEXT');
  await safeAlter("ALTER TABLE garments ADD COLUMN color_palette TEXT NOT NULL DEFAULT '[]'");
  await safeAlter('ALTER TABLE garments ADD COLUMN size TEXT');
  await safeAlter('ALTER TABLE garments ADD COLUMN purchase_date TEXT');
  await safeAlter('ALTER TABLE garments ADD COLUMN is_available INTEGER NOT NULL DEFAULT 1');
  await safeAlter('ALTER TABLE garments ADD COLUMN unavailable_date TEXT');
  await safeAlter('ALTER TABLE garments ADD COLUMN created_at TEXT');
  await safeAlter('ALTER TABLE garments ADD COLUMN updated_at TEXT');

  await runDataMigrations(database);
}

export async function closeDatabase() {
  if (db) {
    await db.closeAsync();
    db = null;
  }
}
