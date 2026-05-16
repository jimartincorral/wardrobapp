import { describe, expect, it } from 'vitest';
import { jaccardSimilarity } from './tag-similarity';

describe('jaccardSimilarity', () => {
  it('returns 0 when both sets are empty', () => {
    expect(jaccardSimilarity([], [])).toBe(0);
  });

  it('normalizes case and ignores duplicate tags', () => {
    const score = jaccardSimilarity(['Casual', ' casual ', 'Denim'], ['DENIM', 'formal']);
    expect(score).toBeCloseTo(1 / 3, 5);
  });

  it('returns 0 when there is no overlap', () => {
    expect(jaccardSimilarity(['sport'], ['formal'])).toBe(0);
  });
});
