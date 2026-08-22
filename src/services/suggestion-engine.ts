import { getDatabase } from '../db/client';
import { getAllGarments } from './garment-service';
import { getCurrentSeason } from '../utils/date-helpers';
import {
  buildSuggestions,
  pairKey,
  type GenerateSuggestionsOptions,
  type PairScoreLookup,
  type ScoredOutfit,
} from '../domain/outfit-suggestions';

/**
 * Wiring for the suggestion algorithm: loads what it needs, then runs it.
 *
 * The algorithm itself lives in `src/domain/outfit-suggestions` and knows
 * nothing about the database or the clock. This is the only part that does.
 */

export type { ScoredOutfit, GenerateSuggestionsOptions, SuggestionPreferences } from '../domain/outfit-suggestions';
export { buildSuggestions } from '../domain/outfit-suggestions';

/**
 * Read the whole pair-score table into memory once per suggestion run.
 *
 * Scoring touches the same handful of rows thousands of times — for every
 * candidate garment, against every already-selected one, for every slot, for
 * every attempt. Querying per lookup made generating suggestions issue
 * thousands of sequential round trips; the table is small (one row per garment
 * pair the user has actually rated), so loading it up front turns all of that
 * into map lookups.
 */
async function loadPairScores(): Promise<PairScoreLookup> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ garment_id_a: string; garment_id_b: string; score: number }>(
    'SELECT garment_id_a, garment_id_b, score FROM garment_pair_scores'
  );

  const scores = new Map<string, number>();
  for (const row of rows) {
    scores.set(pairKey(row.garment_id_a, row.garment_id_b), row.score);
  }

  return (idA, idB) => scores.get(pairKey(idA, idB)) ?? 0;
}

/**
 * Load what the engine needs, then run it.
 *
 * The only part of suggestion generation that touches the database or the clock.
 */
export async function generateSuggestions(
  options: GenerateSuggestionsOptions = {}
): Promise<ScoredOutfit[]> {
  const garments = await getAllGarments({ available_only: true });
  if (garments.length === 0) return [];

  const getPairScore = await loadPairScores();

  return buildSuggestions(
    {
      garments,
      getPairScore,
      currentSeason: getCurrentSeason(),
      random: Math.random,
    },
    options
  );
}
