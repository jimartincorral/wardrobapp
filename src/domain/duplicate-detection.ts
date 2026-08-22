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

export const DUPLICATE_THRESHOLD = 0.81;

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
    const tagSim = jaccardSimilarity(newGarment.tags, garment.tags);
    const incomingPalette = newGarment.color_palette?.length ? newGarment.color_palette : [newGarment.color_primary];
    const existingPalette = getGarmentColorPalette(garment);
    const colorSim = Math.max(
      ...incomingPalette.flatMap(source => existingPalette.map(target => colorSimilarity(source, target)))
    );
    const sizeMatch = newGarment.size && garment.size
      ? (newGarment.size.toLowerCase() === garment.size.toLowerCase() ? 1 : 0)
      : 0;

    const score = 0.6 * tagSim + 0.3 * colorSim + 0.1 * sizeMatch;

    if (score > threshold) {
      const reasons: string[] = [];
      if (tagSim > 0.5) reasons.push('duplicateReasons.similarTags');
      if (colorSim > 0.7) reasons.push('duplicateReasons.similarColor');
      if (sizeMatch) reasons.push('duplicateReasons.sameSize');

      matches.push({
        garment,
        score,
        reason: reasons.join(', ') || 'duplicateReasons.overallSimilarity',
      });
    }
  }

  return matches.sort((a, b) => b.score - a.score);
}
