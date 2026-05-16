import * as Crypto from 'expo-crypto';
import { getDatabase } from '../db/client';
import type { Garment } from '../types';
import { normalizeGarmentRow } from '../utils/garment-fields';

function rowToGarment(row: any): Garment {
  return normalizeGarmentRow(row);
}

export async function createGarment(
  data: Omit<Garment, 'id' | 'created_at' | 'updated_at' | 'is_available' | 'unavailable_date'>
): Promise<Garment> {
  const db = await getDatabase();
  const now = new Date().toISOString();
  const id = Crypto.randomUUID();
  const subcategories = data.subcategories.length > 0
    ? data.subcategories
    : (data.subcategory ? [data.subcategory] : []);
  const primarySubcategory = subcategories[0] ?? null;

  await db.runAsync(
    `INSERT INTO garments (id, image_uri, image_uri_nobg, image_uris, image_uris_nobg, category, subcategory, subcategories, tags, brand, color_primary, color_secondary, color_palette, size, price, purchase_date, is_available, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
    id,
    data.image_uri,
    data.image_uri_nobg ?? null,
    JSON.stringify(data.image_uris),
    JSON.stringify(data.image_uris_nobg),
    data.category,
    primarySubcategory,
    JSON.stringify(subcategories),
    JSON.stringify(data.tags),
    data.brand ?? null,
    data.color_primary,
    data.color_secondary ?? null,
    JSON.stringify(data.color_palette),
    data.size ?? null,
    data.price ?? null,
    data.purchase_date ?? null,
    now,
    now
  );

  return {
    ...data,
    subcategory: primarySubcategory,
    subcategories,
    id,
    is_available: true,
    unavailable_date: null,
    created_at: now,
    updated_at: now,
  };
}

export async function getGarment(id: string): Promise<Garment | null> {
  const db = await getDatabase();
  const row = await db.getFirstAsync('SELECT * FROM garments WHERE id = ?', id);
  return row ? rowToGarment(row) : null;
}

export async function getAllGarments(filters?: {
  category?: string;
  available_only?: boolean;
  search?: string;
}): Promise<Garment[]> {
  const db = await getDatabase();
  let query = 'SELECT * FROM garments WHERE 1=1';
  const params: any[] = [];

  if (filters?.category) {
    query += ' AND category = ?';
    params.push(filters.category);
  }
  if (filters?.available_only !== false) {
    query += ' AND is_available = 1';
  }
  if (filters?.search) {
    query += ' AND (brand LIKE ? OR tags LIKE ? OR subcategory LIKE ? OR subcategories LIKE ?)';
    const term = `%${filters.search}%`;
    params.push(term, term, term, term);
  }

  query += ' ORDER BY created_at DESC';
  const rows = await db.getAllAsync(query, ...params);
  return rows.map(rowToGarment);
}

export async function updateGarment(id: string, data: Partial<Garment>): Promise<void> {
  const db = await getDatabase();
  const now = new Date().toISOString();
  const fields: string[] = [];
  const values: any[] = [];

  const normalizeSqlValue = (value: any) => {
    if (value === undefined) return null;
    if (typeof value === 'number' && !Number.isFinite(value)) return null;
    return value;
  };

  const updatable = [
    'image_uri', 'image_uri_nobg', 'category', 'subcategory',
    'brand', 'color_primary', 'color_secondary', 'size', 'price', 'purchase_date',
  ] as const;

  for (const field of updatable) {
    if (data[field] !== undefined) {
      fields.push(`${field} = ?`);
      values.push(normalizeSqlValue(data[field]));
    }
  }

  if (data.tags !== undefined) {
    fields.push('tags = ?');
    values.push(JSON.stringify(data.tags));
  }
  if (data.image_uris !== undefined) {
    fields.push('image_uris = ?');
    values.push(JSON.stringify(data.image_uris));
  }
  if (data.image_uris_nobg !== undefined) {
    fields.push('image_uris_nobg = ?');
    values.push(JSON.stringify(data.image_uris_nobg));
  }
  if (data.color_palette !== undefined) {
    fields.push('color_palette = ?');
    values.push(JSON.stringify(data.color_palette));
  }
  if (data.subcategories !== undefined) {
    fields.push('subcategories = ?');
    values.push(JSON.stringify(data.subcategories));

    if (data.subcategory === undefined) {
      fields.push('subcategory = ?');
      values.push(data.subcategories[0] ?? null);
    }
  } else if (data.subcategory !== undefined) {
    fields.push('subcategories = ?');
    values.push(JSON.stringify(data.subcategory ? [data.subcategory] : []));
  }

  if (fields.length === 0) return;

  fields.push('updated_at = ?');
  values.push(now);
  values.push(id);

  await db.runAsync(
    `UPDATE garments SET ${fields.join(', ')} WHERE id = ?`,
    ...values
  );
}

export async function markUnavailable(id: string): Promise<void> {
  const db = await getDatabase();
  const now = new Date().toISOString();
  await db.runAsync(
    'UPDATE garments SET is_available = 0, unavailable_date = ?, updated_at = ? WHERE id = ?',
    now, now, id
  );
}

export async function markAvailable(id: string): Promise<void> {
  const db = await getDatabase();
  const now = new Date().toISOString();
  await db.runAsync(
    'UPDATE garments SET is_available = 1, unavailable_date = NULL, updated_at = ? WHERE id = ?',
    now, id
  );
}

export async function deleteGarment(id: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM garments WHERE id = ?', id);
}

export async function getGarmentCount(): Promise<number> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ count: number }>(
    'SELECT COUNT(*) as count FROM garments WHERE is_available = 1'
  );
  return result?.count ?? 0;
}

export async function getUnavailableGarmentCount(): Promise<number> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ count: number }>(
    'SELECT COUNT(*) as count FROM garments WHERE is_available = 0'
  );
  return result?.count ?? 0;
}

export async function getExistingBrands(): Promise<string[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ brand: string }>(
    `SELECT DISTINCT TRIM(brand) as brand
     FROM garments
     WHERE brand IS NOT NULL AND TRIM(brand) != ''
     ORDER BY brand COLLATE NOCASE ASC`
  );

  return rows.map(row => row.brand).filter(Boolean);
}

export async function getExistingTags(): Promise<string[]> {
  const garments = await getAllGarments({ available_only: false });
  const seen = new Set<string>();
  const tags: string[] = [];

  for (const garment of garments) {
    for (const rawTag of garment.tags) {
      const trimmed = rawTag.trim();
      const normalized = trimmed.toLowerCase();
      if (!trimmed || seen.has(normalized)) continue;
      seen.add(normalized);
      tags.push(trimmed);
    }
  }

  return tags.sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
}
