import { runDataMigrations } from './migrations';
import { ALTER_STATEMENTS, CREATE_TABLES_SQL, INDEX_STATEMENTS } from './schema';

/**
 * SQLite access, via expo-sqlite.
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
    const opened = await openNativeDatabase();

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
  await database.execAsync(CREATE_TABLES_SQL);

  // Columns first, then the indexes over them. The other order throws: an
  // install old enough to predate `is_available` -- exactly what these ALTERs
  // exist to bring forward -- hits `CREATE INDEX ... ON garments(is_available)`
  // before the column is added, and index creation is deliberately not
  // swallowed, so initialization rejects and the app cannot open its database
  // at all.
  //
  // "Already exists" is the normal outcome on every install that is already
  // current, so each ALTER's failure is ignored.
  for (const statement of ALTER_STATEMENTS) {
    try {
      await database.execAsync(statement);
    } catch {
      // Ignore if the column already exists.
    }
  }

  // One statement at a time so a failure names the index.
  for (const idx of INDEX_STATEMENTS) {
    await database.execAsync(idx);
  }

  await runDataMigrations(database);
}

/** Exposed for tests that exercise schema setup against real SQLite. */
export const __testing = { initializeDatabase };

export async function closeDatabase() {
  if (db) {
    await db.closeAsync();
    db = null;
  }
}
