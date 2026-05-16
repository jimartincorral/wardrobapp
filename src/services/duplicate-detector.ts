import { getAllGarments } from './garment-service';
import { jaccardSimilarity } from '../utils/tag-similarity';
import { colorSimilarity } from '../utils/color-distance';
import type { Garment } from '../types';
import { getGarmentColorPalette } from '../utils/garment-fields';

export interface DuplicateMatch {
  garment: Garment;
  score: number;
  reason: string;
}

/**
 * Check for potential duplicate garments based on category, tags, color, and size.
 * Returns matches with score strictly above the threshold, sorted by score descending.
 */
export async function findDuplicates(
  newGarment: {
    category: string;
    tags: string[];
    color_primary: string;
    color_palette?: string[];
    size?: string | null;
  },
  threshold = 0.81
): Promise<DuplicateMatch[]> {
  const existing = await getAllGarments({ category: newGarment.category, available_only: true });

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
