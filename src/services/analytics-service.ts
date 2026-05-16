import { getDatabase } from '../db/client';
import type { Garment } from '../types';
import { normalizeGarmentRow } from '../utils/garment-fields';

interface CategoryStat {
  category: string;
  count: number;
}

interface CostPerWear {
  garment_id: string;
  price: number;
  wears: number;
  cost_per_wear: number;
  garment: Garment;
}

function rowToGarment(row: any): Garment {
  return normalizeGarmentRow(row);
}

export async function getMostWorn(limit = 10): Promise<{ garment: Garment; count: number }[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<any>(
    `SELECT g.*, COUNT(w.id) as wear_count
     FROM garments g
     JOIN wear_history w ON g.id = w.garment_id
     WHERE g.is_available = 1
     GROUP BY g.id
     ORDER BY wear_count DESC
     LIMIT ?`,
    limit
  );
  return rows.map(r => ({ garment: rowToGarment(r), count: r.wear_count }));
}

export async function getLeastWorn(limit = 10): Promise<{ garment: Garment; count: number }[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<any>(
    `SELECT g.*, COALESCE(wc.cnt, 0) as wear_count
     FROM garments g
     LEFT JOIN (SELECT garment_id, COUNT(*) as cnt FROM wear_history GROUP BY garment_id) wc
       ON g.id = wc.garment_id
     WHERE g.is_available = 1
     ORDER BY wear_count ASC, g.created_at ASC
     LIMIT ?`,
    limit
  );
  return rows.map(r => ({ garment: rowToGarment(r), count: r.wear_count }));
}

export async function getUnusedGarments(days = 90): Promise<Garment[]> {
  const db = await getDatabase();
  const cutoffTs = Date.now() - days * 24 * 60 * 60 * 1000;

  const [garments, wearRows] = await Promise.all([
    db.getAllAsync<any>('SELECT * FROM garments WHERE is_available = 1'),
    db.getAllAsync<{ garment_id: string; worn_at: string }>(
      'SELECT garment_id, worn_at FROM wear_history'
    ),
  ]);

  const lastWornByGarment = new Map<string, string>();
  for (const row of wearRows) {
    const ts = Date.parse(row.worn_at);
    if (Number.isNaN(ts)) continue;
    const previous = lastWornByGarment.get(row.garment_id);
    if (!previous || ts > Date.parse(previous)) {
      lastWornByGarment.set(row.garment_id, row.worn_at);
    }
  }

  const unused = garments.filter((g: any) => {
    const lastWorn = lastWornByGarment.get(g.id);
    if (!lastWorn) return true;
    const lastWornTs = Date.parse(lastWorn);
    return Number.isNaN(lastWornTs) || lastWornTs < cutoffTs;
  });

  unused.sort((a: any, b: any) => {
    const aLast = lastWornByGarment.get(a.id);
    const bLast = lastWornByGarment.get(b.id);
    if (!aLast && !bLast) return String(a.created_at || '').localeCompare(String(b.created_at || ''));
    if (!aLast) return -1;
    if (!bLast) return 1;
    return aLast.localeCompare(bLast);
  });

  return unused.map(rowToGarment);
}

export async function getCostPerWear(limit = 10): Promise<CostPerWear[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<any>(
    `SELECT g.*, g.id as garment_id, g.price, COUNT(w.id) as wears,
            CASE WHEN COUNT(w.id) > 0 THEN g.price / COUNT(w.id) ELSE g.price END as cost_per_wear
     FROM garments g
     LEFT JOIN wear_history w ON g.id = w.garment_id
     WHERE g.is_available = 1 AND g.price IS NOT NULL AND g.price > 0
     GROUP BY g.id
     ORDER BY cost_per_wear DESC
     LIMIT ?`,
    limit
  );
  return rows.map(row => ({
    garment_id: row.garment_id,
    price: row.price,
    wears: row.wears,
    cost_per_wear: row.cost_per_wear,
    garment: rowToGarment(row),
  }));
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

export async function getWardrobeValue(): Promise<number> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ total: number | null }>(
    'SELECT SUM(price) as total FROM garments WHERE is_available = 1 AND price IS NOT NULL'
  );
  return result?.total ?? 0;
}

export async function getMonthlyWearTrend(months = 6): Promise<{ month: string; count: number }[]> {
  const db = await getDatabase();
  return db.getAllAsync<{ month: string; count: number }>(
    `SELECT strftime('%Y-%m', worn_at) as month, COUNT(*) as count
     FROM wear_history
     WHERE worn_at >= date('now', '-${months} months')
     GROUP BY month
     ORDER BY month ASC`
  );
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
