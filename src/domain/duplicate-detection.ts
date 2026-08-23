/**
 * Duplicate detection.
 *
 * Pure domain logic: the caller supplies what to compare against, so this
 * module has no database dependency and can be tested — and ported — alone.
 */
import { jaccardSimilarity } from '../utils/tag-similarity';
import { colorSimilarity } from '../utils/color-distance';
import type { Garment } from '../types';
import { getGarmentColorPalette } from '../utils/garment-fields';

export interface DuplicateMatch {
  garment: Garment;
  score: number;
  reason: string;
}

/** The subset of a garment duplicate detection actually compares. */
export interface DuplicateCandidate {
  category: string;
  tags: string[];
  color_primary: string;
  color_palette?: string[];
  size?: string | null;
}

/**
 * Score above which a garment is reported as a likely duplicate.
 *
 * Lower than the old 0.81 because the score is now a weighted *average* over
 * the signals that have data, rather than a sum whose maximum depended on how
 * much the user happened to fill in. At 0.81 an exact duplicate with no tags
 * peaked at 0.40 and could never be reported at all.
 */
export const DUPLICATE_THRESHOLD = 0.65;

/** One contribution to a duplicate score; a null score means "no data". */
type SignalTerm = { weight: number; score: number | null };

/**
 * Blend the signals that have something to say, ignoring the ones that do not.
 *
 * Weighting absent data as zero is what made the old score unreachable: with no
 * tags recorded, the tag term contributed nothing but still consumed 0.6 of the
 * available weight, capping an exact duplicate at 0.40 against a 0.81 threshold.
 * Renormalising over the active terms means an unanswered question lowers
 * confidence rather than arguing against a match.
 */
function weightedAverage(terms: SignalTerm[]): number | null {
  const active = terms.filter((t): t is { weight: number; score: number } => t.score !== null);
  const totalWeight = active.reduce((sum, t) => sum + t.weight, 0);
  if (totalWeight === 0) return null;

  return active.reduce((sum, t) => sum + t.weight * t.score, 0) / totalWeight;
}

/**
 * Score a candidate against garments already in the wardrobe.
 *
 * Pure: the caller supplies what to compare against. Returns matches scoring
 * strictly above the threshold, highest first.
 */
export function findDuplicatesAmong(
  newGarment: DuplicateCandidate,
  existing: Garment[],
  threshold = DUPLICATE_THRESHOLD
): DuplicateMatch[] {
  const matches: DuplicateMatch[] = [];

  for (const garment of existing) {
    // Compare primary against primary. Taking the best match across the whole
    // palette cross-product meant any shared entry pinned this to 1.0 -- and
    // '#000000' is the schema default, so a red garment and a blue one that both
    // happened to list black scored as identical in colour.
    const colorSim = colorSimilarity(
      newGarment.color_primary,
      getGarmentColorPalette(garment)[0] ?? garment.color_primary
    );

    // A blank size is not a size. Without the trim, '   ' counted as recorded
    // and scored a *mismatch* against a real size -- absence arguing against a
    // match, which is exactly what the abstention below exists to prevent.
    const bothSizesKnown = Boolean(newGarment.size?.trim() && garment.size?.trim());
    const sizeMatch = bothSizesKnown
      ? (newGarment.size!.trim().toLowerCase() === garment.size!.trim().toLowerCase() ? 1 : 0)
      : null;

    const tagSim = jaccardSimilarity(newGarment.tags, garment.tags);

    const score = weightedAverage([
      { weight: 0.6, score: tagSim },
      { weight: 0.3, score: colorSim },
      { weight: 0.1, score: sizeMatch },
    ]);

    if (score === null || score <= threshold) continue;

    const reasons: string[] = [];
    if (tagSim !== null && tagSim > 0.5) reasons.push('duplicateReasons.similarTags');
    if (colorSim > 0.7) reasons.push('duplicateReasons.similarColor');
    if (sizeMatch === 1) reasons.push('duplicateReasons.sameSize');

    matches.push({
      garment,
      score,
      reason: reasons.join(', ') || 'duplicateReasons.overallSimilarity',
    });
  }

  return matches.sort((a, b) => b.score - a.score);
}
