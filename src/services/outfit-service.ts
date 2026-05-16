import * as Crypto from 'expo-crypto';
import { getDatabase } from '../db/client';
import type { Outfit, OutfitRating } from '../types';

function rowToOutfit(row: any): Outfit {
  return {
    ...row,
    garment_ids: JSON.parse(row.garment_ids || '[]'),
    is_suggested: Boolean(row.is_suggested),
    is_pinned: Boolean(row.is_pinned),
  };
}

export async function createOutfit(data: {
  name: string;
  garment_ids: string[];
  occasion?: string;
  season?: string;
  is_suggested?: boolean;
  is_pinned?: boolean;
}): Promise<Outfit> {
  const db = await getDatabase();
  const id = Crypto.randomUUID();
  const now = new Date().toISOString();

  await db.runAsync(
    `INSERT INTO outfits (id, name, garment_ids, occasion, season, created_at, is_suggested, is_pinned)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    id,
    data.name,
    JSON.stringify(data.garment_ids),
    data.occasion ?? null,
    data.season ?? null,
    now,
    data.is_suggested ? 1 : 0,
    data.is_pinned ? 1 : 0
  );

  return {
    id,
    name: data.name,
    garment_ids: data.garment_ids,
    occasion: data.occasion ?? null,
    season: data.season ?? null,
    created_at: now,
    is_suggested: data.is_suggested ?? false,
    is_pinned: data.is_pinned ?? false,
  };
}

export async function getOutfit(id: string): Promise<Outfit | null> {
  const db = await getDatabase();
  const row = await db.getFirstAsync('SELECT * FROM outfits WHERE id = ?', id);
  return row ? rowToOutfit(row) : null;
}

export async function getAllOutfits(): Promise<Outfit[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync('SELECT * FROM outfits ORDER BY is_pinned DESC, created_at DESC');
  return rows.map(rowToOutfit);
}

export async function setOutfitPinned(id: string, isPinned: boolean): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('UPDATE outfits SET is_pinned = ? WHERE id = ?', isPinned ? 1 : 0, id);
}

export async function deleteOutfit(id: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM outfits WHERE id = ?', id);
}

export async function rateOutfit(outfitId: string, rating: number, feedback?: string): Promise<OutfitRating> {
  const db = await getDatabase();
  const id = Crypto.randomUUID();
  const now = new Date().toISOString();

  await db.runAsync(
    'INSERT INTO outfit_ratings (id, outfit_id, rating, feedback, rated_at) VALUES (?, ?, ?, ?, ?)',
    id, outfitId, rating, feedback ?? null, now
  );

  // Update pair scores for learning
  const outfit = await getOutfit(outfitId);
  if (outfit) {
    await updatePairScores(outfit.garment_ids, rating);
  }

  return { id, outfit_id: outfitId, rating, feedback: feedback ?? null, rated_at: now };
}

async function updatePairScores(garmentIds: string[], rating: number): Promise<void> {
  const db = await getDatabase();
  const normalizedRating = (rating - 3) / 2; // Maps 1-5 to -1.0 to +1.0
  const learningRate = 0.3;

  for (let i = 0; i < garmentIds.length; i++) {
    for (let j = i + 1; j < garmentIds.length; j++) {
      const [a, b] = [garmentIds[i], garmentIds[j]].sort();

      const existing = await db.getFirstAsync<{ score: number; wear_count: number }>(
        'SELECT score, wear_count FROM garment_pair_scores WHERE garment_id_a = ? AND garment_id_b = ?',
        a, b
      );

      if (existing) {
        const newScore = existing.score * (1 - learningRate) + normalizedRating * learningRate;
        await db.runAsync(
          'UPDATE garment_pair_scores SET score = ?, wear_count = ? WHERE garment_id_a = ? AND garment_id_b = ?',
          newScore, existing.wear_count + 1, a, b
        );
      } else {
        await db.runAsync(
          'INSERT INTO garment_pair_scores (garment_id_a, garment_id_b, score, wear_count) VALUES (?, ?, ?, 1)',
          a, b, normalizedRating * learningRate
        );
      }
    }
  }
}

export async function getOutfitRatings(outfitId: string): Promise<OutfitRating[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync(
    'SELECT * FROM outfit_ratings WHERE outfit_id = ? ORDER BY rated_at DESC',
    outfitId
  );
  return rows as OutfitRating[];
}

export async function getAverageRating(outfitId: string): Promise<number | null> {
  const db = await getDatabase();
  const result = await db.getFirstAsync<{ avg_rating: number | null }>(
    'SELECT AVG(rating) as avg_rating FROM outfit_ratings WHERE outfit_id = ?',
    outfitId
  );
  return result?.avg_rating ?? null;
}
