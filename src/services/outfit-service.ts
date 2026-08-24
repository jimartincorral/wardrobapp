import * as Crypto from 'expo-crypto';
import { getDatabase } from '../db/client';
import type { Outfit, OutfitRating } from '../types';
import { foldRatingIntoPair, garmentPairs } from '../domain/pair-learning';

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
  // The foreign key cascades this, but only while `PRAGMA foreign_keys = ON`
  // holds; deleting explicitly keeps it correct either way.
  await db.runAsync('DELETE FROM outfit_ratings WHERE outfit_id = ?', id);
  await db.runAsync('DELETE FROM outfits WHERE id = ?', id);
}

/**
 * Drop a garment from every outfit that references it, so deleting a garment
 * can't leave outfits pointing at rows that no longer exist. Outfits left with
 * no garments at all are deleted; ones that still have garments are kept (their
 * name may now be slightly stale, but the outfit itself is still usable).
 */
export async function removeGarmentFromOutfits(garmentId: string): Promise<void> {
  const db = await getDatabase();
  const outfits = await getAllOutfits();

  for (const outfit of outfits) {
    if (!outfit.garment_ids.includes(garmentId)) continue;

    const remaining = outfit.garment_ids.filter(id => id !== garmentId);
    if (remaining.length === 0) {
      await deleteOutfit(outfit.id);
    } else {
      await db.runAsync(
        'UPDATE outfits SET garment_ids = ? WHERE id = ?',
        JSON.stringify(remaining), outfit.id
      );
    }
  }
}

/**
 * Rate an outfit. An outfit carries exactly one rating: re-rating is the user
 * correcting themselves, not a second opinion, so the previous rating is
 * replaced rather than appended. (The delete also collapses any duplicate rows
 * left behind by earlier versions, which appended on every star tap.)
 */
export async function rateOutfit(outfitId: string, rating: number, feedback?: string): Promise<OutfitRating> {
  const db = await getDatabase();
  const id = Crypto.randomUUID();
  const now = new Date().toISOString();

  const previous = await db.getFirstAsync<{ rating: number }>(
    'SELECT rating FROM outfit_ratings WHERE outfit_id = ? ORDER BY rated_at DESC',
    outfitId
  );

  await db.runAsync('DELETE FROM outfit_ratings WHERE outfit_id = ?', outfitId);
  await db.runAsync(
    'INSERT INTO outfit_ratings (id, outfit_id, rating, feedback, rated_at) VALUES (?, ?, ?, ?, ?)',
    id, outfitId, rating, feedback ?? null, now
  );

  // Update pair scores for learning
  const outfit = await getOutfit(outfitId);
  if (outfit) {
    await updatePairScores(outfit.garment_ids, rating, previous?.rating ?? null);
  }

  return { id, outfit_id: outfitId, rating, feedback: feedback ?? null, rated_at: now };
}

/**
 * Persist the learned scores for every pair in a rated outfit.
 *
 * The arithmetic lives in `src/domain/pair-learning` — this is the storage
 * around it.
 */
async function updatePairScores(
  garmentIds: string[],
  rating: number,
  previousRating: number | null
): Promise<void> {
  const db = await getDatabase();

  for (const [a, b] of garmentPairs(garmentIds)) {
    const existing = await db.getFirstAsync<{ score: number; wear_count: number }>(
      'SELECT score, wear_count FROM garment_pair_scores WHERE garment_id_a = ? AND garment_id_b = ?',
      a, b
    );

    const next = foldRatingIntoPair(existing, rating, previousRating);

    if (existing) {
      await db.runAsync(
        'UPDATE garment_pair_scores SET score = ?, wear_count = ? WHERE garment_id_a = ? AND garment_id_b = ?',
        next.score, next.wear_count, a, b
      );
    } else {
      await db.runAsync(
        'INSERT INTO garment_pair_scores (garment_id_a, garment_id_b, score, wear_count) VALUES (?, ?, ?, ?)',
        a, b, next.score, next.wear_count
      );
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

