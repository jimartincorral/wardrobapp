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
  // Native SQLite cascades this via the foreign key, but the web adapter has no
  // FK support — delete explicitly so both platforms behave the same.
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

const PAIR_LEARNING_RATE = 0.3;

/** Maps a 1-5 star rating to -1.0 .. +1.0. */
function normalizeRating(rating: number): number {
  return (rating - 3) / 2;
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
 * Fold a rating into the learned pair scores.
 *
 * `previousRating` is the rating this one replaces, if any. In that case the
 * earlier rating's contribution is undone before the new one is applied — the
 * EMA step `new = old * (1 - lr) + r * lr` inverts exactly — so correcting a
 * rating moves the score to where it would have been, instead of training on
 * both values. wear_count is only incremented for a genuinely new rating.
 */
async function updatePairScores(
  garmentIds: string[],
  rating: number,
  previousRating: number | null
): Promise<void> {
  const db = await getDatabase();
  const normalizedRating = normalizeRating(rating);

  for (let i = 0; i < garmentIds.length; i++) {
    for (let j = i + 1; j < garmentIds.length; j++) {
      const [a, b] = [garmentIds[i], garmentIds[j]].sort();

      const existing = await db.getFirstAsync<{ score: number; wear_count: number }>(
        'SELECT score, wear_count FROM garment_pair_scores WHERE garment_id_a = ? AND garment_id_b = ?',
        a, b
      );

      if (existing) {
        const base = previousRating === null
          ? existing.score
          : (existing.score - normalizeRating(previousRating) * PAIR_LEARNING_RATE) / (1 - PAIR_LEARNING_RATE);
        const newScore = base * (1 - PAIR_LEARNING_RATE) + normalizedRating * PAIR_LEARNING_RATE;
        const wearCount = previousRating === null ? existing.wear_count + 1 : existing.wear_count;

        await db.runAsync(
          'UPDATE garment_pair_scores SET score = ?, wear_count = ? WHERE garment_id_a = ? AND garment_id_b = ?',
          newScore, wearCount, a, b
        );
      } else {
        await db.runAsync(
          'INSERT INTO garment_pair_scores (garment_id_a, garment_id_b, score, wear_count) VALUES (?, ?, ?, 1)',
          a, b, normalizedRating * PAIR_LEARNING_RATE
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
