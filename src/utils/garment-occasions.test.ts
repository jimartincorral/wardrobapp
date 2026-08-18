import { describe, expect, it } from 'vitest';
import { getOccasionsFor, getGarmentOccasions } from './garment-occasions';
import type { Garment } from '../types';

const garment = (overrides: Partial<Garment>): Garment => ({
  id: 'g', image_uri: 'f', image_uri_nobg: null, image_uris: ['f'], image_uris_nobg: [],
  category: 'tops', subcategory: null, subcategories: [], tags: [], brand: null,
  color_primary: '#000000', color_secondary: null, color_palette: ['#000000'], size: null,
  purchase_date: null, is_available: true, unavailable_date: null,
  created_at: '2026-01-01', updated_at: '2026-01-01', ...overrides,
});

describe('getOccasionsFor', () => {
  it('derives occasions from the subcategory', () => {
    expect(getOccasionsFor('midlayer', ['Blazer'])).toEqual(['work', 'formal']);
    expect(getOccasionsFor('shoes', ['Athletic'])).toEqual(['sport']);
    expect(getOccasionsFor('loungewear', ['Robe'])).toEqual(['lounge']);
  });

  it('unions the occasions of every subcategory, in canonical order', () => {
    expect(getOccasionsFor('shoes', ['Heels', 'Sneakers'])).toEqual(['casual', 'work', 'formal', 'sport']);
  });

  it('falls back to the category when the subcategory is missing or unknown', () => {
    expect(getOccasionsFor('activewear', [])).toEqual(['sport']);
    expect(getOccasionsFor('loungewear', ['Something New'])).toEqual(['lounge']);
    expect(getOccasionsFor('tops', [])).toEqual(['casual']);
  });

  it('maps underwear to no occasion at all', () => {
    expect(getOccasionsFor('underwear', ['Socks'])).toEqual([]);
  });
});

describe('getGarmentOccasions', () => {
  it('prefers subcategories but accepts the legacy single subcategory field', () => {
    expect(getGarmentOccasions(garment({ category: 'shoes', subcategories: ['Heels'] })))
      .toEqual(['work', 'formal']);
    expect(getGarmentOccasions(garment({ category: 'shoes', subcategories: [], subcategory: 'Heels' })))
      .toEqual(['work', 'formal']);
  });

  it('does not read occasion from tags any more', () => {
    // A garment tagged "sport" in the old scheme is a blazer: work/formal wins.
    const g = garment({ category: 'midlayer', subcategories: ['Blazer'], tags: ['sport'] });
    expect(getGarmentOccasions(g)).toEqual(['work', 'formal']);
  });
});
