import * as Crypto from 'expo-crypto';
import { getDatabase } from '../db/client';
import type { WearRecord } from '../types';

export async function logWear(garmentId: string, outfitId?: string): Promise<WearRecord> {
  const db = await getDatabase();
  const id = Crypto.randomUUID();
  const now = new Date().toISOString();

  await db.runAsync(
    'INSERT INTO wear_history (id, garment_id, outfit_id, worn_at) VALUES (?, ?, ?, ?)',
    id, garmentId, outfitId ?? null, now
  );

  return { id, garment_id: garmentId, outfit_id: outfitId ?? null, worn_at: now };
}

export async function logOutfitWear(garmentIds: string[], outfitId: string): Promise<void> {
  const db = await getDatabase();
  const now = new Date().toISOString();

  for (const garmentId of garmentIds) {
    const id = Crypto.randomUUID();
    await db.runAsync(
      'INSERT INTO wear_history (id, garment_id, outfit_id, worn_at) VALUES (?, ?, ?, ?)',
      id, garmentId, outfitId, now
    );
  }
}

export async function getWearHistory(garmentId: string, limit = 50): Promise<WearRecord[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync(
    'SELECT * FROM wear_history WHERE garment_id = ? ORDER BY worn_at DESC LIMIT ?',
    garmentId, limit
  );
  return rows as WearRecord[];
}

export async function getWearCount(garmentId: string): Promise<number> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ count: number }>(
    'SELECT COUNT(*) as count FROM wear_history WHERE garment_id = ?',
    garmentId
  );
  return result?.count ?? 0;
}

export async function getLastWorn(garmentId: string): Promise<string | null> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ worn_at: string }>(
    'SELECT worn_at FROM wear_history WHERE garment_id = ? ORDER BY worn_at DESC LIMIT 1',
    garmentId
  );
  return result?.worn_at ?? null;
}

export async function getTotalWears(): Promise<number> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ count: number }>(
    'SELECT COUNT(*) as count FROM wear_history'
  );
  return result?.count ?? 0;
}
