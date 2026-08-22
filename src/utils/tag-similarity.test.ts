import { describe, expect, it } from 'vitest';
import { jaccardSimilarity } from './tag-similarity';

describe('jaccardSimilarity', () => {
  it('abstains when neither side has tags', () => {
    // Not 0: "nothing to compare" has to be distinguishable from "they
    // disagree", or two untagged garments read as maximally dissimilar on the
    // strength of no evidence.
    expect(jaccardSimilarity([], [])).toBeNull();
  });

  it('abstains when both sides hold only blanks', () => {
    // `[''] vs ['']` used to score a perfect 1.
    expect(jaccardSimilarity([''], ['  '])).toBeNull();
  });

  it('scores 1 for identical sets', () => {
    expect(jaccardSimilarity(['a', 'b'], ['b', 'a'])).toBe(1);
  });

  it('normalizes case and ignores duplicate tags', () => {
    const score = jaccardSimilarity(['Casual', ' casual ', 'Denim'], ['DENIM', 'formal']);
    expect(score).toBeCloseTo(1 / 3, 5);
  });

  it('returns 0 when both sides have tags but none in common', () => {
    // A real disagreement, unlike the abstention above.
    expect(jaccardSimilarity(['sport'], ['formal'])).toBe(0);
  });

  it('returns 0 when only one side has tags', () => {
    expect(jaccardSimilarity(['sport'], [])).toBe(0);
    expect(jaccardSimilarity([], ['sport'])).toBe(0);
  });

  it('drops blank entries rather than counting them as tags', () => {
    expect(jaccardSimilarity(['a', '', '  '], ['a'])).toBe(1);
  });

  it('is symmetric', () => {
    expect(jaccardSimilarity(['a', 'b'], ['b', 'c'])).toBe(jaccardSimilarity(['b', 'c'], ['a', 'b']));
  });
});
