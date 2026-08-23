import { describe, expect, it } from 'vitest';
import { filterGarments, sortGarments } from './garment-filtering';
import type { Garment } from '../types';

const garment = (overrides: Partial<Garment>): Garment => ({
  id: 'g',
  image_uri: 'a.jpg',
  image_uri_nobg: null,
  image_uris: ['a.jpg'],
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

describe('filterGarments', () => {
  it('keeps everything when nothing is asked for', () => {
    const all = [garment({ id: 'a' }), garment({ id: 'b' })];
    expect(filterGarments(all).map(g => g.id)).toEqual(['a', 'b']);
  });

  it('matches a subcategory in the list or the singular column', () => {
    const inList = garment({ id: 'list', subcategories: ['Hoodie'], subcategory: null });
    const singular = garment({ id: 'single', subcategories: [], subcategory: 'Hoodie' });
    const other = garment({ id: 'other', subcategories: ['Polo'], subcategory: 'Polo' });

    expect(filterGarments([inList, singular, other], { subcategory: 'Hoodie' }).map(g => g.id))
      .toEqual(['list', 'single']);
  });

  it('matches a season tag case-insensitively', () => {
    const tagged = garment({ id: 'tagged', tags: ['Summer'] });
    const untagged = garment({ id: 'untagged', tags: [] });

    expect(filterGarments([tagged, untagged], { season: 'summer' }).map(g => g.id))
      .toEqual(['tagged']);
  });

  it('derives occasion from the garment type rather than its tags', () => {
    // Nothing is tagged 'work'; a Blouse is work-appropriate by type.
    const blouse = garment({ id: 'blouse', subcategories: ['Blouse'] });
    const tee = garment({ id: 'tee', subcategories: ['T-Shirt'] });

    expect(filterGarments([blouse, tee], { occasion: 'work' }).map(g => g.id)).toEqual(['blouse']);
  });

  it('matches brand and size as case-insensitive substrings', () => {
    const uniqlo = garment({ id: 'uniqlo', brand: 'Uniqlo', size: 'M' });
    const nike = garment({ id: 'nike', brand: 'Nike', size: 'XL' });

    expect(filterGarments([uniqlo, nike], { brand: '  uniQ ' }).map(g => g.id)).toEqual(['uniqlo']);
    expect(filterGarments([uniqlo, nike], { size: 'x' }).map(g => g.id)).toEqual(['nike']);
  });

  it('matches a colour anywhere in the palette, not just the primary', () => {
    const secondary = garment({
      id: 'secondary',
      color_primary: '#000000',
      color_palette: ['#000000', '#CC0000'],
    });
    const neither = garment({ id: 'neither', color_palette: ['#FFFFFF'] });

    expect(filterGarments([secondary, neither], { color: '#CC0000' }).map(g => g.id))
      .toEqual(['secondary']);
  });

  it('applies every filter given, not just the first', () => {
    const match = garment({ id: 'match', brand: 'Uniqlo', tags: ['summer'] });
    const wrongBrand = garment({ id: 'wrong', brand: 'Nike', tags: ['summer'] });
    const wrongSeason = garment({ id: 'season', brand: 'Uniqlo', tags: ['winter'] });

    expect(
      filterGarments([match, wrongBrand, wrongSeason], { brand: 'Uniqlo', season: 'summer' })
        .map(g => g.id)
    ).toEqual(['match']);
  });
});

describe('sortGarments', () => {
  it('puts the newest first by default', () => {
    const items = [
      garment({ id: 'old', created_at: '2026-01-01' }),
      garment({ id: 'new', created_at: '2026-06-01' }),
      garment({ id: 'mid', created_at: '2026-03-01' }),
    ];

    expect(sortGarments(items).map(g => g.id)).toEqual(['new', 'mid', 'old']);
    expect(sortGarments(items, 'oldest').map(g => g.id)).toEqual(['old', 'mid', 'new']);
  });

  it('does not reorder its input', () => {
    const items = [garment({ id: 'a', created_at: '2026-01-01' }), garment({ id: 'b', created_at: '2026-06-01' })];
    sortGarments(items);
    expect(items.map(g => g.id)).toEqual(['a', 'b']);
  });

  it('survives a garment with no timestamp', () => {
    // created_at is declared non-null but really can be null on an install
    // upgraded through the ALTER path. Dereferencing it threw for any wardrobe
    // big enough for the null to reach the right-hand side of a comparison, and
    // the list hook swallowed that -- so the screen showed an empty wardrobe.
    const items = Array.from({ length: 12 }, (_, i) => garment({
      id: `g${i}`,
      created_at: (i === 5 ? null : `2026-01-${String(i + 1).padStart(2, '0')}`) as string,
    }));

    const sorted = sortGarments(items);

    expect(sorted).toHaveLength(12);
    // An absent timestamp sorts as the earliest possible, so it goes last here
    // rather than vanishing.
    expect(sorted[sorted.length - 1].id).toBe('g5');
    expect(sortGarments(items, 'oldest')[0].id).toBe('g5');
  });
});
