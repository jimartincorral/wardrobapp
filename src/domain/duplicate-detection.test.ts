import { describe, expect, it } from 'vitest';
import { DUPLICATE_THRESHOLD, findDuplicatesAmong } from './duplicate-detection';
import type { Garment } from '../types';

// findDuplicatesAmong is pure, so this exercises the real scoring: the tag
// Jaccard, the Lab colour distance and the size match, with nothing stubbed.

const garment = (overrides: Partial<Garment>): Garment => ({
  id: 'g',
  image_uri: 'front.jpg',
  image_uri_nobg: null,
  image_uris: ['front.jpg'],
  image_uris_nobg: [],
  category: 'tops',
  subcategory: 'T-Shirt',
  subcategories: ['T-Shirt'],
  tags: [],
  brand: null,
  color_primary: '#000000',
  color_secondary: null,
  color_palette: ['#000000'],
  size: 'M',
  purchase_date: null,
  is_available: true,
  unavailable_date: null,
  created_at: '2026-01-01T00:00:00.000Z',
  updated_at: '2026-01-01T00:00:00.000Z',
  ...overrides,
});

const candidate = {
  category: 'tops',
  tags: ['cotton', 'basic'],
  color_primary: '#000000',
  color_palette: ['#000000'],
  size: 'M',
};

const flags = (cand: typeof candidate | Record<string, unknown>, existing: Garment[]) =>
  findDuplicatesAmong(cand as typeof candidate, existing).length > 0;

const scoreOf = (cand: typeof candidate | Record<string, unknown>, existing: Garment) =>
  findDuplicatesAmong(cand as typeof candidate, [existing], -1)[0].score;

describe('what gets flagged', () => {
  it('flags a garment identical in tags, colour and size', () => {
    const matches = findDuplicatesAmong(candidate, [garment({ id: 'twin', tags: ['cotton', 'basic'] })]);

    expect(matches).toHaveLength(1);
    expect(matches[0].garment.id).toBe('twin');
    expect(matches[0].score).toBeCloseTo(1, 5);
  });

  it('flags an exact duplicate that carries no tags at all', () => {
    // Weighting the absent tag term as zero capped this at 0.40 against a 0.81
    // threshold, so an untagged duplicate could never be reported.
    const untagged = { ...candidate, tags: [] };
    const existing = garment({ id: 'twin', tags: [] });

    expect(scoreOf(untagged, existing)).toBeCloseTo(1, 5);
    expect(flags(untagged, [existing])).toBe(true);
  });

  it('flags the same garment when one side gained an extra tag', () => {
    // The scenario from the review: an existing black tee tagged ['cotton'],
    // and the same tee being added with a season chip as well. One extra tag
    // used to drop the score to 0.70 and defeat detection entirely.
    const withSeason = { ...candidate, tags: ['cotton', 'summer'] };

    expect(flags(withSeason, [garment({ id: 'twin', tags: ['cotton'] })])).toBe(true);
  });

  it('flags a duplicate when neither side records a size', () => {
    const noSize = { ...candidate, size: null };

    expect(flags(noSize, [garment({ id: 'twin', tags: ['cotton', 'basic'], size: null })])).toBe(true);
  });

  it('does not credit an unrecorded size as a size match', () => {
    // An unanswered question lowers confidence; it must not argue for the match.
    // Same imperfect tag overlap and identical colour either way, so the only
    // difference is whether the size is known.
    const partial = { ...candidate, tags: ['a', 'b'] };
    const recorded = scoreOf(partial, garment({ tags: ['a', 'c'], size: 'M' }));
    const unknown = scoreOf({ ...partial, size: null }, garment({ tags: ['a', 'c'], size: null }));

    expect(unknown).toBeLessThan(recorded);
  });

  it('ignores a garment that shares nothing but its category', () => {
    const other = garment({
      id: 'other',
      tags: ['wool', 'formal'],
      color_primary: '#FFFFFF',
      color_palette: ['#FFFFFF'],
      size: 'XL',
    });

    expect(findDuplicatesAmong(candidate, [other])).toEqual([]);
  });

  it('ignores a garment that matches only on colour and size', () => {
    // Same black M top, entirely different tags: not a duplicate.
    const unrelated = garment({ id: 'unrelated', tags: ['wool', 'formal'] });

    expect(scoreOf(candidate, unrelated)).toBeLessThan(DUPLICATE_THRESHOLD);
  });
});

describe('colour comparison', () => {
  it('does not treat a shared palette entry as the same colour', () => {
    // '#000000' is the schema default, so taking the best match across the whole
    // palette cross-product made a red garment and a blue one score 1.0.
    const red = { ...candidate, tags: ['a'], color_primary: '#CC0000', color_palette: ['#CC0000', '#000000'] };
    const blue = garment({
      id: 'blue',
      tags: ['a'],
      color_primary: '#0066CC',
      color_palette: ['#0066CC', '#000000'],
    });

    expect(scoreOf(red, blue)).toBeLessThan(1);
  });

  it('scores a colour mismatch below an otherwise identical match', () => {
    const same = scoreOf(candidate, garment({ tags: ['cotton', 'basic'] }));
    const different = scoreOf(
      candidate,
      garment({ tags: ['cotton', 'basic'], color_primary: '#FFFFFF', color_palette: ['#FFFFFF'] })
    );

    expect(different).toBeLessThan(same);
  });
});

describe('ranking and thresholds', () => {
  it('returns matches highest-scoring first', () => {
    const matches = findDuplicatesAmong(candidate, [
      garment({ id: 'partial', tags: ['cotton'] }),
      garment({ id: 'exact', tags: ['cotton', 'basic'] }),
    ], -1);

    expect(matches.map(m => m.garment.id)).toEqual(['exact', 'partial']);
    expect(matches[0].score).toBeGreaterThan(matches[1].score);
  });

  it('treats the threshold as exclusive', () => {
    const twin = [garment({ id: 'twin', tags: ['cotton', 'basic'] })];

    expect(findDuplicatesAmong(candidate, twin, 1)).toEqual([]);
    expect(findDuplicatesAmong(candidate, twin, 0.99)).toHaveLength(1);
  });

  it('scores a size mismatch below an otherwise identical match', () => {
    const same = scoreOf(candidate, garment({ tags: ['cotton', 'basic'], size: 'M' }));
    const other = scoreOf(candidate, garment({ tags: ['cotton', 'basic'], size: 'L' }));

    expect(other).toBeLessThan(same);
  });

  it('handles an empty wardrobe', () => {
    expect(findDuplicatesAmong(candidate, [])).toEqual([]);
  });
});

describe('reasons', () => {
  it('names which signals fired', () => {
    const [match] = findDuplicatesAmong(candidate, [garment({ id: 'twin', tags: ['cotton', 'basic'] })]);

    expect(match.reason).toContain('duplicateReasons.similarTags');
    expect(match.reason).toContain('duplicateReasons.similarColor');
    expect(match.reason).toContain('duplicateReasons.sameSize');
  });

  it('does not claim similar tags when there were none to compare', () => {
    const untagged = { ...candidate, tags: [] };
    const [match] = findDuplicatesAmong(untagged, [garment({ id: 'twin', tags: [] })]);

    expect(match.reason).not.toContain('duplicateReasons.similarTags');
    expect(match.reason).toContain('duplicateReasons.similarColor');
  });

  it('falls back to a generic reason when no single signal is strong', () => {
    // Partial tag overlap and a mid-grey against black both sit under their
    // individual bars, and there is no size on either side.
    const matches = findDuplicatesAmong(
      { ...candidate, tags: ['a', 'b'], size: null },
      [garment({ id: 'vague', tags: ['a', 'c'], color_primary: '#808080', color_palette: ['#808080'], size: null })],
      -1
    );

    expect(matches[0].reason).toBe('duplicateReasons.overallSimilarity');
  });
});
