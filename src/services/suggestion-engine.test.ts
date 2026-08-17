import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Garment } from '../types';

const getAllGarmentsMock = vi.fn();
const getDatabaseMock = vi.fn();

vi.mock('./garment-service', () => ({
  getAllGarments: getAllGarmentsMock,
}));

vi.mock('../db/client', () => ({
  getDatabase: getDatabaseMock,
}));

vi.mock('../utils/color-distance', () => ({
  colorHarmonyScore: vi.fn(() => 0.5),
}));

vi.mock('../utils/date-helpers', () => ({
  getCurrentSeason: vi.fn(() => 'spring'),
}));

const baseGarment = (overrides: Partial<Garment>): Garment => {
  const garment: Garment = {
    id: 'garment',
    image_uri: 'file://image.jpg',
    image_uri_nobg: null,
    image_uris: ['file://image.jpg'],
    image_uris_nobg: [],
    category: 'tops',
    subcategory: 'T-Shirt',
    subcategories: ['T-Shirt'],
    tags: [],
    brand: null,
    color_primary: '#000000',
    color_secondary: null,
    color_palette: ['#000000'],
    size: null,
    purchase_date: null,
    is_available: true,
    unavailable_date: null,
    created_at: '2026-04-11T00:00:00.000Z',
    updated_at: '2026-04-11T00:00:00.000Z',
    ...overrides,
  };

  if (!overrides.subcategories) {
    garment.subcategories = garment.subcategory ? [garment.subcategory] : [];
  }

  return garment;
};

describe('generateSuggestions', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    getDatabaseMock.mockResolvedValue({
      getAllAsync: vi.fn().mockResolvedValue([]),
    });
  });

  it('builds outfit suggestions from activewear top and bottom pieces', async () => {
    getAllGarmentsMock.mockResolvedValue([
      baseGarment({
        id: 'a-top',
        category: 'activewear',
        subcategory: 'Workout Top',
      }),
      baseGarment({
        id: 'a-bottom',
        category: 'activewear',
        subcategory: 'Workout Shorts',
      }),
    ]);

    const { generateSuggestions } = await import('./suggestion-engine');
    const suggestions = await generateSuggestions({ count: 1 });

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].garments.map(g => g.id).sort()).toEqual(['a-bottom', 'a-top']);
  });

  it('builds outfit suggestions from a one-piece activewear set', async () => {
    getAllGarmentsMock.mockResolvedValue([
      baseGarment({
        id: 'track-suit',
        category: 'activewear',
        subcategory: 'Track Suit',
      }),
    ]);

    const { generateSuggestions } = await import('./suggestion-engine');
    const suggestions = await generateSuggestions({ count: 1 });

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].garments.map(g => g.id)).toEqual(['track-suit']);
  });

  it('builds outfit suggestions from a one-piece loungewear set', async () => {
    getAllGarmentsMock.mockResolvedValue([
      baseGarment({
        id: 'pajama-set',
        category: 'loungewear',
        subcategory: 'Pajama Set',
      }),
    ]);

    const { generateSuggestions } = await import('./suggestion-engine');
    const suggestions = await generateSuggestions({ count: 1 });

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].garments.map(g => g.id)).toEqual(['pajama-set']);
  });

  it('supports matching any of multiple selected seasons', async () => {
    getAllGarmentsMock.mockResolvedValue([
      baseGarment({
        id: 'summer-top',
        category: 'tops',
        subcategory: 'Tank Top',
        tags: ['summer'],
      }),
      baseGarment({
        id: 'fall-bottom',
        category: 'bottoms',
        subcategory: 'Jeans',
        tags: ['fall'],
      }),
      baseGarment({
        id: 'winter-bottom',
        category: 'bottoms',
        subcategory: 'Wool Pants',
        tags: ['winter'],
      }),
    ]);

    const { generateSuggestions } = await import('./suggestion-engine');
    const suggestions = await generateSuggestions({
      count: 1,
      preferences: { seasons: ['summer', 'fall'] },
    });

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].garments.map(g => g.id)).toContain('summer-top');
  });

  it('returns a normalized display score between 0 and 1', async () => {
    getDatabaseMock.mockResolvedValue({
      getAllAsync: vi.fn().mockResolvedValue([
        { garment_id_a: 'coat', garment_id_b: 'dress', score: 12 },
      ]),
    });
    getAllGarmentsMock.mockResolvedValue([
      baseGarment({
        id: 'dress',
        category: 'dresses',
        subcategory: 'Slip Dress',
        tags: ['summer'],
      }),
      baseGarment({
        id: 'coat',
        category: 'outerwear',
        subcategory: 'Jacket',
        tags: ['winter', 'heavy'],
      }),
    ]);

    const { generateSuggestions } = await import('./suggestion-engine');
    const suggestions = await generateSuggestions({ count: 1 });

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].score).toBeGreaterThanOrEqual(0);
    expect(suggestions[0].score).toBeLessThanOrEqual(1);
  });

  it('reads the pair-score table once, however large the wardrobe', async () => {
    const getAllAsync = vi.fn().mockResolvedValue([]);
    getDatabaseMock.mockResolvedValue({ getAllAsync });

    // Enough garments that a per-lookup query would run hundreds of times.
    getAllGarmentsMock.mockResolvedValue(
      Array.from({ length: 40 }, (_, i) =>
        baseGarment({
          id: `garment-${i}`,
          category: i % 2 === 0 ? 'tops' : 'bottoms',
          subcategory: i % 2 === 0 ? 'T-Shirt' : 'Jeans',
        })
      )
    );

    const { generateSuggestions } = await import('./suggestion-engine');
    await generateSuggestions({ count: 3 });

    expect(getAllAsync).toHaveBeenCalledTimes(1);
  });

  it('finds a learned pair score regardless of the stored key order', async () => {
    getDatabaseMock.mockResolvedValue({
      getAllAsync: vi.fn().mockResolvedValue([
        // Stored b-before-a; lookups happen in selection order, not sorted order.
        { garment_id_a: 'z-bottom', garment_id_b: 'a-top', score: 1 },
      ]),
    });
    getAllGarmentsMock.mockResolvedValue([
      baseGarment({ id: 'a-top', category: 'tops', subcategory: 'T-Shirt' }),
      baseGarment({ id: 'z-bottom', category: 'bottoms', subcategory: 'Jeans' }),
    ]);

    const { generateSuggestions } = await import('./suggestion-engine');
    const suggestions = await generateSuggestions({ count: 1 });

    // A positive learned score pushes the normalized display score above the
    // 0.5 midpoint it would sit at with no signal at all.
    expect(suggestions).toHaveLength(1);
    expect(suggestions[0].score).toBeGreaterThan(0.5);
  });
});
