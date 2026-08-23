/**
 * Jaccard similarity between two tag sets.
 *
 * Returns null when neither side has any tags. That is not the same as 0: 0
 * asserts the tags disagree, null says there is nothing to compare. Callers that
 * blend signals need to tell those apart, otherwise two untagged garments look
 * maximally *dis*similar on the strength of no evidence at all.
 */
export function jaccardSimilarity(tagsA: string[], tagsB: string[]): number | null {
  // Blank entries are not tags; `[''] vs ['']` used to score a perfect 1.
  const setA = new Set(tagsA.map(t => t.toLowerCase().trim()).filter(Boolean));
  const setB = new Set(tagsB.map(t => t.toLowerCase().trim()).filter(Boolean));

  if (setA.size === 0 && setB.size === 0) return null;

  let intersection = 0;
  for (const tag of setA) {
    if (setB.has(tag)) intersection++;
  }

  const union = setA.size + setB.size - intersection;
  return union === 0 ? null : intersection / union;
}
