import { beforeEach, describe, expect, it, vi } from 'vitest';
import { runDataMigrations } from './migrations';
import type { DatabaseAdapter } from './client';

function createFakeDb(garments: { id: string; tags: string }[]) {
  const prefs: { key: string; value: string }[] = [];
  const rows = [...garments];

  const db = {
    rows,
    prefs,
    execAsync: vi.fn(async () => {}),
    closeAsync: vi.fn(async () => {}),
    runAsync: vi.fn(async (sql: string, ...params: any[]) => {
      if (/^UPDATE garments SET tags/.test(sql)) {
        const [tags, id] = params;
        const row = rows.find(r => r.id === id);
        if (row) row.tags = tags;
        return;
      }
      if (/^INSERT INTO user_preferences/.test(sql)) {
        prefs.push({ key: params[0], value: params[1] });
        return;
      }
      throw new Error(`Unhandled runAsync: ${sql}`);
    }),
    getFirstAsync: vi.fn(async (sql: string, ...params: any[]) => {
      if (/FROM user_preferences/.test(sql)) {
        return prefs.find(p => p.key === params[0]) ?? null;
      }
      throw new Error(`Unhandled getFirstAsync: ${sql}`);
    }),
    getAllAsync: vi.fn(async (sql: string) => {
      if (/FROM garments/.test(sql)) return rows.map(r => ({ ...r }));
      throw new Error(`Unhandled getAllAsync: ${sql}`);
    }),
  };

  return db as typeof db & DatabaseAdapter;
}

describe('stripLegacyStructuredTags', () => {
  beforeEach(() => vi.clearAllMocks());

  it('removes weather and occasion values but keeps seasons and custom tags', async () => {
    const db = createFakeDb([
      { id: 'a', tags: JSON.stringify(['cotton', 'hot', 'casual', 'winter']) },
    ]);

    await runDataMigrations(db);

    expect(JSON.parse(db.rows[0].tags)).toEqual(['cotton', 'winter']);
  });

  it('only writes rows that actually change', async () => {
    const db = createFakeDb([
      { id: 'a', tags: JSON.stringify(['wool', 'summer']) },
      { id: 'b', tags: JSON.stringify(['rainy']) },
    ]);

    await runDataMigrations(db);

    const updates = db.runAsync.mock.calls.filter(c => /UPDATE garments/.test(c[0] as string));
    expect(updates).toHaveLength(1);
    expect(updates[0][2]).toBe('b');
  });

  it('runs once, then no-ops on later launches', async () => {
    const db = createFakeDb([{ id: 'a', tags: JSON.stringify(['cold']) }]);

    await runDataMigrations(db);
    expect(db.prefs).toHaveLength(1);

    db.runAsync.mockClear();
    await runDataMigrations(db);
    expect(db.runAsync).not.toHaveBeenCalled();
  });

  it('leaves unparseable tag values untouched', async () => {
    const db = createFakeDb([{ id: 'a', tags: 'not json' }]);

    await runDataMigrations(db);

    expect(db.rows[0].tags).toBe('not json');
  });

  it('does not flag itself as done if it fails, so it retries next launch', async () => {
    const db = createFakeDb([{ id: 'a', tags: JSON.stringify(['hot']) }]);
    db.runAsync.mockImplementationOnce(async () => { throw new Error('disk full'); });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await expect(runDataMigrations(db)).resolves.toBeUndefined();
    expect(db.prefs).toHaveLength(0);

    warn.mockRestore();
  });
});
