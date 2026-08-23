import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { DatabaseAdapter } from '../db/client';
import { freshDatabase } from '../db/testing/sqlite';

// Backed by real SQLite rather than a stub. The aggregation *is* the behaviour
// here — a GROUP BY on the wrong expression is exactly the kind of fault a fake
// that ignores SQL cannot show.

let adapter: DatabaseAdapter;

vi.mock('../db/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../db/client')>();
  return { ...actual, getDatabase: async () => adapter };
});

vi.mock('./image-service', () => ({
  getGarmentImageDirectory: () => '/photos/',
}));

const {
  getBrandDistribution,
  getCategoryDistribution,
  getColorDistribution,
  getSubcategoryDistribution,
  getGarmentLifespan,
} = await import('./analytics-service');

let db: Awaited<ReturnType<typeof freshDatabase>>['db'];

async function addGarment(fields: Record<string, unknown>) {
  const row = {
    id: 'g', image_uri: 'a.jpg', category: 'tops', subcategory: null,
    subcategories: '[]', tags: '[]', brand: null, color_primary: '#000000',
    color_palette: '[]', size: null, purchase_date: null, is_available: 1,
    unavailable_date: null, created_at: '2026-01-01', updated_at: '2026-01-01',
    ...fields,
  };
  const columns = Object.keys(row);
  await adapter.runAsync(
    `INSERT INTO garments (${columns.join(', ')}) VALUES (${columns.map(() => '?').join(', ')})`,
    ...columns.map(c => (row as any)[c])
  );
}

beforeEach(async () => {
  const created = await freshDatabase();
  db = created.db;
  adapter = created.adapter;
});

describe('getCategoryDistribution', () => {
  it('counts available garments per category, most first', async () => {
    await addGarment({ id: '1', category: 'tops' });
    await addGarment({ id: '2', category: 'tops' });
    await addGarment({ id: '3', category: 'shoes' });
    await addGarment({ id: '4', category: 'shoes', is_available: 0 });

    expect(await getCategoryDistribution()).toEqual([
      { category: 'tops', count: 2 },
      { category: 'shoes', count: 1 },
    ]);
  });
});

describe('getBrandDistribution', () => {
  it('treats a brand as one brand however it was typed', async () => {
    // The brand picker lists DISTINCT TRIM(brand), so the app already considers
    // these one brand. Grouping on the raw column reported it three times.
    await addGarment({ id: '1', brand: 'Uniqlo' });
    await addGarment({ id: '2', brand: ' Uniqlo' });
    await addGarment({ id: '3', brand: 'Uniqlo ' });
    await addGarment({ id: '4', brand: 'Nike' });

    expect(await getBrandDistribution()).toEqual([
      { category: 'Uniqlo', count: 3 },
      { category: 'Nike', count: 1 },
    ]);
  });

  it('ignores blank and absent brands', async () => {
    await addGarment({ id: '1', brand: 'Nike' });
    await addGarment({ id: '2', brand: '   ' });
    await addGarment({ id: '3', brand: null });

    expect(await getBrandDistribution()).toEqual([{ category: 'Nike', count: 1 }]);
  });
});

describe('getColorDistribution', () => {
  it('treats a colour as one colour whatever its case', async () => {
    // normalizeGarmentRow dedupes palettes case-insensitively, so the app
    // already considers these the same colour everywhere else.
    await addGarment({ id: '1', color_primary: '#ABCDEF' });
    await addGarment({ id: '2', color_primary: '#abcdef' });
    await addGarment({ id: '3', color_primary: '#000000' });

    expect(await getColorDistribution()).toEqual([
      { category: '#ABCDEF', count: 2 },
      { category: '#000000', count: 1 },
    ]);
  });
});

describe('getSubcategoryDistribution', () => {
  it('groups subcategories under their category', async () => {
    await addGarment({ id: '1', category: 'tops', subcategories: '["T-Shirt"]' });
    await addGarment({ id: '2', category: 'tops', subcategories: '["T-Shirt"]' });
    await addGarment({ id: '3', category: 'tops', subcategories: '["Hoodie"]' });
    await addGarment({ id: '4', category: 'shoes', subcategories: '["Boots"]' });

    expect(await getSubcategoryDistribution()).toEqual({
      tops: [{ category: 'T-Shirt', count: 2 }, { category: 'Hoodie', count: 1 }],
      shoes: [{ category: 'Boots', count: 1 }],
    });
  });

  it('counts a garment once per subcategory it carries', async () => {
    await addGarment({ id: '1', category: 'tops', subcategories: '["T-Shirt","Polo"]' });

    expect((await getSubcategoryDistribution()).tops).toEqual([
      { category: 'T-Shirt', count: 1 },
      { category: 'Polo', count: 1 },
    ]);
  });

  it('falls back to the singular column, then to a placeholder', async () => {
    await addGarment({ id: '1', category: 'tops', subcategory: 'Polo', subcategories: '[]' });
    await addGarment({ id: '2', category: 'tops', subcategories: '[]' });

    const result = await getSubcategoryDistribution();
    expect(result.tops).toContainEqual({ category: 'Polo', count: 1 });
    expect(result.tops).toContainEqual({ category: '__none__', count: 1 });
  });
});

describe('getGarmentLifespan', () => {
  it('reports days owned for retired garments, longest first', async () => {
    await addGarment({
      id: 'short', is_available: 0,
      purchase_date: '2026-01-01', unavailable_date: '2026-01-11',
    });
    await addGarment({
      id: 'long', is_available: 0,
      purchase_date: '2026-01-01', unavailable_date: '2026-03-02',
    });
    // Still owned, so it has no lifespan yet.
    await addGarment({ id: 'current', purchase_date: '2026-01-01' });
    // Retired but never given a purchase date.
    await addGarment({ id: 'unknown', is_available: 0, unavailable_date: '2026-02-01' });

    const lifespans = await getGarmentLifespan();

    expect(lifespans.map(l => l.garment.id)).toEqual(['long', 'short']);
    expect(lifespans[0].days).toBe(60);
    expect(lifespans[1].days).toBe(10);
  });
});
