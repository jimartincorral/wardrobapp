/**
 * A real SQLite database for tests, via `node:sqlite`.
 *
 * Test-only: nothing in the app imports this, so it never reaches a bundle. It
 * exists because the service tests mock `getDatabase` with a fake that accepts
 * any SQL and returns canned rows — the right shape for testing what a service
 * does with results, and useless for testing whether the SQL is correct. A
 * GROUP BY that groups by the wrong expression is invisible to a stub.
 */
import { DatabaseSync } from 'node:sqlite';
import type { DatabaseAdapter } from '../client';

export function adapterOver(db: DatabaseSync): DatabaseAdapter {
  return {
    execAsync: async (sql: string) => { db.exec(sql); },
    runAsync: async (sql: string, ...params: any[]) => { db.prepare(sql).run(...params); },
    getFirstAsync: async <T,>(sql: string, ...params: any[]) =>
      (db.prepare(sql).get(...params) ?? null) as T | null,
    getAllAsync: async <T,>(sql: string, ...params: any[]) => db.prepare(sql).all(...params) as T[],
    closeAsync: async () => { db.close(); },
  };
}

/** An in-memory database with the app's real schema applied. */
export async function freshDatabase(): Promise<{ db: DatabaseSync; adapter: DatabaseAdapter }> {
  const { __testing } = await import('../client');
  const db = new DatabaseSync(':memory:');
  const adapter = adapterOver(db);
  await __testing.initializeDatabase(adapter);
  return { db, adapter };
}
