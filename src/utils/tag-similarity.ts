/**
 * Compute Jaccard similarity between two tag sets.
 * Returns a value between 0 (no overlap) and 1 (identical).
 */
export function jaccardSimilarity(tagsA: string[], tagsB: string[]): number {
  if (tagsA.length === 0 && tagsB.length === 0) return 0;

  const setA = new Set(tagsA.map(t => t.toLowerCase().trim()));
  const setB = new Set(tagsB.map(t => t.toLowerCase().trim()));

  let intersection = 0;
  for (const tag of setA) {
    if (setB.has(tag)) intersection++;
  }

  const union = setA.size + setB.size - intersection;
  return union === 0 ? 0 : intersection / union;
}
