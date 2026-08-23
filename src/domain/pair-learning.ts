/**
 * How a rating becomes a learned affinity between two garments.
 *
 * Pure domain logic, deliberately separated from the storage that applies it:
 * this is the part with an actual argument in it. Everything else about rating an
 * outfit is bookkeeping.
 */

/** Weight given to the newest rating in the running average. */
export const PAIR_LEARNING_RATE = 0.3;

/** Map a 1-5 star rating onto -1.0 .. +1.0, so 3 stars is neutral. */
export function normalizeRating(rating: number): number {
  return (rating - 3) / 2;
}

/** A pair's learned state. */
export interface PairScore {
  score: number;
  wear_count: number;
}

/**
 * Fold a rating into a pair's score.
 *
 * `previous` is the rating this one replaces, if the user is correcting
 * themselves rather than rating something new. In that case the earlier rating's
 * contribution is undone before the new one is applied — the
 * exponential-moving-average step
 *
 *     next = old * (1 - lr) + r * lr
 *
 * inverts exactly, so correcting a rating moves the score to where it would have
 * been had the user rated correctly the first time, instead of training on both
 * values. `wear_count` counts how often a pair has actually been worn together,
 * so a correction must not increment it.
 */
export function foldRatingIntoPair(
  existing: PairScore | null,
  rating: number,
  previous: number | null = null
): PairScore {
  const normalized = normalizeRating(rating);

  if (!existing) {
    // A first rating starts from an implicit score of zero, so the EMA step
    // reduces to the rating's own share.
    return { score: normalized * PAIR_LEARNING_RATE, wear_count: 1 };
  }

  const base = previous === null
    ? existing.score
    // The inverse of the EMA step, undoing the rating being replaced.
    : (existing.score - normalizeRating(previous) * PAIR_LEARNING_RATE) / (1 - PAIR_LEARNING_RATE);

  return {
    score: base * (1 - PAIR_LEARNING_RATE) + normalized * PAIR_LEARNING_RATE,
    wear_count: previous === null ? existing.wear_count + 1 : existing.wear_count,
  };
}

/**
 * Every unordered pair in an outfit, each with its ids in a stable order.
 *
 * Ordering the two ids means a pair is stored once however the outfit lists it,
 * which is what makes the key and the lookup agree.
 */
export function garmentPairs(garmentIds: string[]): [string, string][] {
  const pairs: [string, string][] = [];
  for (let i = 0; i < garmentIds.length; i++) {
    for (let j = i + 1; j < garmentIds.length; j++) {
      const a = garmentIds[i];
      const b = garmentIds[j];
      pairs.push(a <= b ? [a, b] : [b, a]);
    }
  }
  return pairs;
}
