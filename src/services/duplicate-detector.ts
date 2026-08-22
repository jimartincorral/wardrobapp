import { getAllGarments } from './garment-service';
import {
  findDuplicatesAmong,
  DUPLICATE_THRESHOLD,
  type DuplicateCandidate,
  type DuplicateMatch,
} from '../domain/duplicate-detection';

/**
 * Wiring for duplicate detection. The scoring itself lives in
 * `src/domain/duplicate-detection`; this is the only part that reads the database.
 */

export type { DuplicateMatch, DuplicateCandidate } from '../domain/duplicate-detection';
export { findDuplicatesAmong, DUPLICATE_THRESHOLD } from '../domain/duplicate-detection';

/**
 * Look up same-category garments, then score the candidate against them.
 *
 * The only part of duplicate detection that touches the database.
 */
export async function findDuplicates(
  newGarment: DuplicateCandidate,
  threshold = DUPLICATE_THRESHOLD
): Promise<DuplicateMatch[]> {
  const existing = await getAllGarments({
    category: newGarment.category,
    available_only: true,
  });

  return findDuplicatesAmong(newGarment, existing, threshold);
}
