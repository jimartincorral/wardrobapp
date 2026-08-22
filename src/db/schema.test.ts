import { describe, expect, it } from 'vitest';
import { DatabaseSync } from 'node:sqlite';
import { __testing } from './client';
import type { DatabaseAdapter } from './client';

// Schema setup, run against real SQLite rather than a stub. The stub in
// client.test.ts accepts any SQL, which is the right shape for testing the
// maintenance lock and the wrong shape for testing whether the schema actually
// applies -- an ordering mistake is invisible to something that never executes
// the statements.

function adapterOver(db: DatabaseSync): DatabaseAdapter {
  return {
    execAsync: async (sql: string) => { db.exec(sql); },
    runAsync: async (sql: string, ...params: any[]) => { db.prepare(sql).run(...params); },
    getFirstAsync: async <T,>(sql: string, ...params: any[]) =>
      (db.prepare(sql).get(...params) ?? null) as T | null,
    getAllAsync: async <T,>(sql: string, ...params: any[]) =>
      db.prepare(sql).all(...params) as T[],
    closeAsync: async () => { db.close(); },
  };
}

const columnsOf = (db: DatabaseSync, table: string) =>
  (db.prepare(`PRAGMA table_info(${table})`).all() as { name: string }[]).map(c => c.name);

describe('initializeDatabase', () => {
  it('sets up a fresh database', async () => {
    const db = new DatabaseSync(':memory:');

    await __testing.initializeDatabase(adapterOver(db));

    const tables = (db.prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
    ).all() as { name: string }[]).map(t => t.name);

    expect(tables).toContain('garments');
    expect(tables).toContain('outfits');
    expect(tables).toContain('outfit_ratings');
    expect(tables).toContain('garment_pair_scores');
    expect(tables).toContain('user_preferences');
    expect(columnsOf(db, 'garments')).toContain('is_available');
  });

  it('is idempotent', async () => {
    const db = new DatabaseSync(':memory:');
    const adapter = adapterOver(db);

    // It runs on every start, so running twice must be indistinguishable from
    // running once.
    await __testing.initializeDatabase(adapter);
    const first = columnsOf(db, 'garments');
    await __testing.initializeDatabase(adapter);

    expect(columnsOf(db, 'garments')).toEqual(first);
  });

  it('upgrades an install that predates most of the columns', async () => {
    // The case the 17 ALTER statements exist for. Creating the indexes before
    // adding the columns threw `no such column: is_available` -- and index
    // creation is not swallowed, so initialization rejected and the app could
    // not open its database at all.
    const db = new DatabaseSync(':memory:');
    db.exec(`CREATE TABLE garments (
      id TEXT PRIMARY KEY,
      image_uri TEXT NOT NULL,
      category TEXT NOT NULL
    )`);

    await expect(__testing.initializeDatabase(adapterOver(db))).resolves.not.toThrow();

    const columns = columnsOf(db, 'garments');
    for (const column of [
      'is_available', 'tags', 'color_palette', 'image_uris', 'image_uris_nobg',
      'subcategories', 'brand', 'size', 'purchase_date', 'unavailable_date',
      'created_at', 'updated_at',
    ]) {
      expect(columns, `expected ${column} to be added`).toContain(column);
    }

    const indexes = (db.prepare(
      "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'"
    ).all() as { name: string }[]).map(i => i.name);
    expect(indexes).toContain('idx_garments_available');
  });

  it('keeps the data in an upgraded install', async () => {
    const db = new DatabaseSync(':memory:');
    db.exec(`CREATE TABLE garments (
      id TEXT PRIMARY KEY,
      image_uri TEXT NOT NULL,
      category TEXT NOT NULL
    )`);
    db.exec("INSERT INTO garments (id, image_uri, category) VALUES ('old', 'a.jpg', 'tops')");

    await __testing.initializeDatabase(adapterOver(db));

    // An upgrade that loses the wardrobe is not an upgrade.
    const row = db.prepare('SELECT id, image_uri, category FROM garments').get();
    expect(row).toEqual({ id: 'old', image_uri: 'a.jpg', category: 'tops' });
  });
});
