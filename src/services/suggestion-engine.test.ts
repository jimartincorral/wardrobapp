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
      getFirstAsync: vi.fn().mockResolvedValue(null),
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
      getFirstAsync: vi.fn((query: string) => {
        if (query.includes('FROM garment_pair_scores')) {
          return Promise.resolve({ score: 12 });
        }
        return Promise.resolve(null);
      }),
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
});
