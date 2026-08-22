import { describe, expect, it } from 'vitest';
import { DUPLICATE_THRESHOLD, findDuplicatesAmong } from './duplicate-detection';
import type { Garment } from '../types';

// findDuplicatesAmong is pure, so this exercises the real scoring: the tag
// Jaccard, the colour distance and the size match, with nothing stubbed.

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
  created_at: '2026-04-11T00:00:00.000Z',
  updated_at: '2026-04-11T00:00:00.000Z',
  ...overrides,
});

const candidate = {
  category: 'tops',
  tags: ['cotton', 'basic'],
  color_primary: '#000000',
  color_palette: ['#000000'],
  size: 'M',
};

describe('findDuplicatesAmong', () => {
  it('flags a garment identical in tags, colour and size', () => {
    const existing = [garment({ id: 'twin', tags: ['cotton', 'basic'] })];

    const matches = findDuplicatesAmong(candidate, existing);

    expect(matches).toHaveLength(1);
    expect(matches[0].garment.id).toBe('twin');
    // Identical on all three terms is the only way to reach 1.0.
    expect(matches[0].score).toBeCloseTo(1, 5);
  });

  it('ignores a garment that shares nothing but its category', () => {
    const existing = [
      garment({ id: 'other', tags: ['wool', 'formal'], color_primary: '#FFFFFF', color_palette: ['#FFFFFF'], size: 'XL' }),
    ];

    expect(findDuplicatesAmong(candidate, existing)).toEqual([]);
  });

  it('returns matches highest-scoring first', () => {
    const existing = [
      garment({ id: 'partial', tags: ['cotton'] }),
      garment({ id: 'exact', tags: ['cotton', 'basic'] }),
    ];

    // A low threshold so both clear it and the ordering is what is under test.
    const matches = findDuplicatesAmong(candidate, existing, 0.1);

    expect(matches.map(m => m.garment.id)).toEqual(['exact', 'partial']);
    expect(matches[0].score).toBeGreaterThan(matches[1].score);
  });

  it('treats the threshold as exclusive', () => {
    const existing = [garment({ id: 'twin', tags: ['cotton', 'basic'] })];

    // The twin scores exactly 1, so a threshold of 1 must exclude it.
    expect(findDuplicatesAmong(candidate, existing, 1)).toEqual([]);
    expect(findDuplicatesAmong(candidate, existing, 0.99)).toHaveLength(1);
  });

  it('scores a size mismatch below an otherwise identical match', () => {
    const sameSize = findDuplicatesAmong(candidate, [garment({ tags: ['cotton', 'basic'], size: 'M' })], 0.1);
    const otherSize = findDuplicatesAmong(candidate, [garment({ tags: ['cotton', 'basic'], size: 'L' })], 0.1);

    expect(otherSize[0].score).toBeLessThan(sameSize[0].score);
  });

  it('names which signals fired', () => {
    const existing = [garment({ id: 'twin', tags: ['cotton', 'basic'] })];

    const [match] = findDuplicatesAmong(candidate, existing);

    expect(match.reason).toContain('duplicateReasons.similarTags');
    expect(match.reason).toContain('duplicateReasons.similarColor');
    expect(match.reason).toContain('duplicateReasons.sameSize');
  });

  it('falls back to a generic reason when no single signal is strong', () => {
    // Partial tag overlap (Jaccard 0.333) and a mid-grey against black
    // (similarity 0.464) both sit under their individual bars, and there is no
    // size to match -- so the total clears a low threshold with no one reason.
    const existing = [
      garment({ id: 'vague', tags: ['a', 'c'], color_primary: '#808080', color_palette: ['#808080'], size: null }),
    ];

    const matches = findDuplicatesAmong(
      { ...candidate, tags: ['a', 'b'], size: null },
      existing,
      0.1
    );

    expect(matches).toHaveLength(1);
    expect(matches[0].reason).toBe('duplicateReasons.overallSimilarity');
  });

  it('handles an empty wardrobe', () => {
    expect(findDuplicatesAmong(candidate, [])).toEqual([]);
  });

  it('exposes the default threshold it ships with', () => {
    expect(DUPLICATE_THRESHOLD).toBe(0.81);
  });
});
