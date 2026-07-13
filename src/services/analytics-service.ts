import { getDatabase } from '../db/client';
import type { Garment } from '../types';
import { normalizeGarmentRow } from '../utils/garment-fields';

interface CategoryStat {
  category: string;
  count: number;
}

function rowToGarment(row: any): Garment {
  return normalizeGarmentRow(row);
}

export async function getCategoryDistribution(): Promise<CategoryStat[]> {
  const db = await getDatabase();
  return db.getAllAsync<CategoryStat>(
    `SELECT category, COUNT(*) as count
     FROM garments
     WHERE is_available = 1
     GROUP BY category
     ORDER BY count DESC`
  );
}

export async function getColorDistribution(): Promise<CategoryStat[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ color_primary: string; count: number }>(
    `SELECT color_primary, COUNT(*) as count
     FROM garments
     WHERE is_available = 1 AND color_primary IS NOT NULL AND color_primary != ''
     GROUP BY color_primary
     ORDER BY count DESC`
  );
  return rows.map(r => ({ category: r.color_primary, count: r.count }));
}

export async function getSubcategoryDistribution(): Promise<Record<string, CategoryStat[]>> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<any>(
    `SELECT category, subcategory, subcategories FROM garments WHERE is_available = 1`
  );

  const byCategory: Record<string, Map<string, number>> = {};
  for (const row of rows) {
    const category = row.category;
    if (!category) continue;

    let subs: string[] = [];
    if (row.subcategories) {
      try {
        const parsed = typeof row.subcategories === 'string'
          ? JSON.parse(row.subcategories)
          : row.subcategories;
        if (Array.isArray(parsed)) subs = parsed.filter(Boolean);
      } catch {
        // ignore malformed JSON
      }
    }
    if (subs.length === 0 && row.subcategory) subs = [row.subcategory];
    if (subs.length === 0) subs = ['__none__'];

    if (!byCategory[category]) byCategory[category] = new Map();
    const map = byCategory[category];
    for (const sub of subs) {
      map.set(sub, (map.get(sub) ?? 0) + 1);
    }
  }

  const result: Record<string, CategoryStat[]> = {};
  for (const [category, map] of Object.entries(byCategory)) {
    result[category] = [...map.entries()]
      .map(([sub, count]) => ({ category: sub, count }))
      .sort((a, b) => b.count - a.count);
  }
  return result;
}

export async function getBrandDistribution(): Promise<CategoryStat[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ brand: string; count: number }>(
    `SELECT brand, COUNT(*) as count
     FROM garments
     WHERE is_available = 1 AND brand IS NOT NULL AND TRIM(brand) != ''
     GROUP BY brand
     ORDER BY count DESC`
  );
  return rows.map(r => ({ category: r.brand, count: r.count }));
}

export async function getGarmentLifespan(): Promise<{ garment: Garment; days: number }[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<any>(
    `SELECT g.*,
            CAST(julianday(g.unavailable_date) - julianday(g.purchase_date) AS INTEGER) as lifespan_days
     FROM garments g
     WHERE g.is_available = 0
       AND g.unavailable_date IS NOT NULL
       AND g.purchase_date IS NOT NULL
     ORDER BY lifespan_days DESC`
  );
  return rows.map(r => ({ garment: rowToGarment(r), days: r.lifespan_days }));
}
