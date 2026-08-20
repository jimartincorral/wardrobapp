import { beforeEach, describe, expect, it, vi } from 'vitest';
import { runDataMigrations, __testing } from './migrations';
import type { DatabaseAdapter } from './client';

type GarmentRow = {
  id: string;
  tags?: string;
  image_uri?: string | null;
  image_uri_nobg?: string | null;
  image_uris?: string | null;
  image_uris_nobg?: string | null;
};

function createFakeDb(garments: GarmentRow[]) {
  const prefs: { key: string; value: string }[] = [];
  const rows: GarmentRow[] = garments.map(g => ({ ...g }));

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
      if (/^UPDATE garments SET image_uri/.test(sql)) {
        const [imageUri, imageUriNobg, imageUris, imageUrisNobg, id] = params;
        const row = rows.find(r => r.id === id);
        if (row) {
          row.image_uri = imageUri;
          row.image_uri_nobg = imageUriNobg;
          row.image_uris = imageUris;
          row.image_uris_nobg = imageUrisNobg;
        }
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

const flagged = (db: { prefs: { key: string }[] }) => db.prefs.map(p => p.key);
const tagUpdates = (db: { runAsync: { mock: { calls: unknown[][] } } }) =>
  db.runAsync.mock.calls.filter(c => /UPDATE garments SET tags/.test(c[0] as string));
const imageUpdates = (db: { runAsync: { mock: { calls: unknown[][] } } }) =>
  db.runAsync.mock.calls.filter(c => /UPDATE garments SET image_uri/.test(c[0] as string));

describe('stripLegacyStructuredTags', () => {
  beforeEach(() => vi.clearAllMocks());

  it('removes weather and occasion values but keeps seasons and custom tags', async () => {
    const db = createFakeDb([
      { id: 'a', tags: JSON.stringify(['cotton', 'hot', 'casual', 'winter']) },
    ]);

    await runDataMigrations(db);

    expect(JSON.parse(db.rows[0].tags!)).toEqual(['cotton', 'winter']);
  });

  it('only writes rows that actually change', async () => {
    const db = createFakeDb([
      { id: 'a', tags: JSON.stringify(['wool', 'summer']) },
      { id: 'b', tags: JSON.stringify(['rainy']) },
    ]);

    await runDataMigrations(db);

    const updates = tagUpdates(db);
    expect(updates).toHaveLength(1);
    expect(updates[0][2]).toBe('b');
  });

  it('runs once, then no-ops on later launches', async () => {
    const db = createFakeDb([{ id: 'a', tags: JSON.stringify(['cold']) }]);

    await runDataMigrations(db);
    expect(flagged(db)).toContain(__testing.STRIP_LEGACY_TAGS_KEY);

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
    expect(flagged(db)).not.toContain(__testing.STRIP_LEGACY_TAGS_KEY);

    warn.mockRestore();
  });

  it('failing does not prevent the other migrations from running', async () => {
    // The two are independent, so one bad launch must not skip the rest.
    const db = createFakeDb([
      { id: 'a', tags: JSON.stringify(['hot']), image_uri: 'file:///old/garment-images/a.jpg' },
    ]);
    db.runAsync.mockImplementationOnce(async () => { throw new Error('disk full'); });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await runDataMigrations(db);

    expect(flagged(db)).not.toContain(__testing.STRIP_LEGACY_TAGS_KEY);
    expect(flagged(db)).toContain(__testing.BARE_IMAGE_REFS_KEY);
    expect(db.rows[0].image_uri).toBe('a.jpg');

    warn.mockRestore();
  });
});

describe('storeBareImageRefs', () => {
  beforeEach(() => vi.clearAllMocks());

  it('strips the directory from absolute photo paths', async () => {
    // iOS rewrites the container UUID on reinstall, so the old absolute path
    // points nowhere even though the file was restored correctly.
    const db = createFakeDb([
      {
        id: 'a',
        image_uri: 'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/front.jpg',
        image_uri_nobg: 'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/front_nobg.png',
        image_uris: JSON.stringify([
          'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/front.jpg',
          'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/back.jpg',
        ]),
        image_uris_nobg: JSON.stringify([
          'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/front_nobg.png',
        ]),
      },
    ]);

    await runDataMigrations(db);

    expect(db.rows[0].image_uri).toBe('front.jpg');
    expect(db.rows[0].image_uri_nobg).toBe('front_nobg.png');
    expect(JSON.parse(db.rows[0].image_uris!)).toEqual(['front.jpg', 'back.jpg']);
    expect(JSON.parse(db.rows[0].image_uris_nobg!)).toEqual(['front_nobg.png']);
  });

  it('leaves web data URIs alone', async () => {
    // On web the column holds the image itself, not a path to one.
    const dataUri = 'data:image/jpeg;base64,AAAA';
    const db = createFakeDb([
      { id: 'a', image_uri: dataUri, image_uris: JSON.stringify([dataUri]) },
    ]);

    await runDataMigrations(db);

    expect(imageUpdates(db)).toHaveLength(0);
    expect(db.rows[0].image_uri).toBe(dataUri);
  });

  it('does not rewrite rows that already hold bare filenames', async () => {
    const db = createFakeDb([
      { id: 'a', image_uri: 'front.jpg', image_uris: JSON.stringify(['front.jpg']) },
    ]);

    await runDataMigrations(db);

    expect(imageUpdates(db)).toHaveLength(0);
  });

  it('preserves a null nobg reference rather than inventing one', async () => {
    const db = createFakeDb([
      {
        id: 'a',
        image_uri: 'file:///old/garment-images/front.jpg',
        image_uri_nobg: null,
        image_uris_nobg: JSON.stringify([]),
      },
    ]);

    await runDataMigrations(db);

    expect(db.rows[0].image_uri_nobg).toBeNull();
    expect(JSON.parse(db.rows[0].image_uris_nobg!)).toEqual([]);
  });

  it('runs once, then no-ops on later launches', async () => {
    const db = createFakeDb([{ id: 'a', image_uri: 'file:///old/garment-images/front.jpg' }]);

    await runDataMigrations(db);
    expect(flagged(db)).toContain(__testing.BARE_IMAGE_REFS_KEY);

    db.runAsync.mockClear();
    await runDataMigrations(db);
    expect(imageUpdates(db)).toHaveLength(0);
  });
});
