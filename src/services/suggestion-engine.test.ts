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
    const garments = [
      baseGarment({ id: 'a-top', category: 'tops', subcategory: 'T-Shirt' }),
      baseGarment({ id: 'z-bottom', category: 'bottoms', subcategory: 'Jeans' }),
    ];

    const scoreWith = async (rows: unknown[]) => {
      vi.clearAllMocks();
      getDatabaseMock.mockResolvedValue({ getAllAsync: vi.fn().mockResolvedValue(rows) });
      getAllGarmentsMock.mockResolvedValue(garments);
      const { generateSuggestions } = await import('./suggestion-engine');
      const suggestions = await generateSuggestions({ count: 1 });
      expect(suggestions).toHaveLength(1);
      return suggestions[0].score;
    };

    // Stored b-before-a; lookups happen in selection order, not sorted order.
    const learned = await scoreWith([
      { garment_id_a: 'z-bottom', garment_id_b: 'a-top', score: 1 },
    ]);
    const unlearned = await scoreWith([]);

    // Assert the *delta*, not a fixed midpoint. An earlier version of this test
    // checked `score > 0.5`, which passes even with the learning code deleted --
    // season and harmony alone already push an unlearned outfit above 0.7.
    expect(learned).toBeGreaterThan(unlearned);
  });
});

/** Deterministic stand-in for Math.random: replays a sequence, then repeats it. */
function scriptedRandom(sequence: number[]): () => number {
  let index = 0;
  return () => sequence[index++ % sequence.length];
}

const pairScoresFrom = (scores: Record<string, number> = {}) =>
  (idA: string, idB: string) =>
    scores[[idA, idB].sort().join('|')] ?? 0;

describe('buildSuggestions', () => {
  // Note: colorHarmonyScore is stubbed to a constant above, so harmony is held
  // fixed here and the assertions isolate the other signals.
  const top = baseGarment({ id: 'top-1', category: 'tops', subcategory: 'T-Shirt' });
  const bottom = baseGarment({ id: 'bottom-1', category: 'bottoms', subcategory: 'Jeans' });

  const context = (overrides: Record<string, unknown> = {}) => ({
    garments: [top, bottom],
    getPairScore: pairScoresFrom(),
    currentSeason: 'spring',
    random: scriptedRandom([0.5]),
    ...overrides,
  });

  // A wardrobe big enough that selection genuinely varies. With only one top and
  // one bottom the outcome is forced, so a determinism test over it would pass
  // even if the engine ignored its random source entirely.
  const wideWardrobe = [
    ...Array.from({ length: 6 }, (_, i) =>
      baseGarment({ id: `top-${i}`, category: 'tops', subcategory: `Top${i}` })
    ),
    ...Array.from({ length: 6 }, (_, i) =>
      baseGarment({ id: `bottom-${i}`, category: 'bottoms', subcategory: `Bottom${i}` })
    ),
  ];

  it('produces identical output for the same context and random source', async () => {
    // The property that makes a run reproducible -- and the algorithm portable.
    const { buildSuggestions } = await import('./suggestion-engine');
    const script = [0.9, 0.1, 0.42, 0.85, 0.07, 0.63, 0.28, 0.71];

    const first = buildSuggestions(
      context({ garments: wideWardrobe, random: scriptedRandom(script) }),
      { count: 4 }
    );
    const second = buildSuggestions(
      context({ garments: wideWardrobe, random: scriptedRandom(script) }),
      { count: 4 }
    );

    expect(first.length).toBeGreaterThan(1);
    expect(first).toEqual(second);
  });

  it('lets the injected random change which outfits come out', async () => {
    // Proves the random source is actually driving selection, so the
    // determinism above is a real property rather than a forced outcome.
    const { buildSuggestions } = await import('./suggestion-engine');

    const names = (script: number[]) =>
      buildSuggestions(
        context({ garments: wideWardrobe, random: scriptedRandom(script) }),
        { count: 4 }
      ).map(outfit => outfit.name);

    expect(names([0.9, 0.05, 0.5])).not.toEqual(names([0.9, 0.95, 0.5]));
  });

  it('rates an outfit higher once its pair has a learned affinity', async () => {
    const { buildSuggestions } = await import('./suggestion-engine');

    const withLearning = buildSuggestions(
      context({ getPairScore: pairScoresFrom({ 'bottom-1|top-1': 1 }) }),
      { count: 1 }
    );
    const without = buildSuggestions(context(), { count: 1 });

    expect(withLearning[0].score).toBeGreaterThan(without[0].score);
  });

  it('uses the supplied season instead of the calendar', async () => {
    const { buildSuggestions } = await import('./suggestion-engine');
    const winterTop = baseGarment({ id: 'top-1', category: 'tops', tags: ['winter'] });
    const garments = [winterTop, bottom];

    const inWinter = buildSuggestions(context({ garments, currentSeason: 'winter' }), { count: 1 });
    const inSummer = buildSuggestions(context({ garments, currentSeason: 'summer' }), { count: 1 });

    expect(inWinter[0].score).toBeGreaterThan(inSummer[0].score);
  });

  it('never repeats a garment inside one outfit', async () => {
    const { buildSuggestions } = await import('./suggestion-engine');

    const suggestions = buildSuggestions(
      context({ random: scriptedRandom([0.1, 0.3, 0.7, 0.9, 0.5]) }),
      { count: 5 }
    );

    for (const outfit of suggestions) {
      const ids = outfit.garments.map(g => g.id);
      expect(new Set(ids).size).toBe(ids.length);
    }
  });

  it('returns nothing when no template can be satisfied', async () => {
    const { buildSuggestions } = await import('./suggestion-engine');

    // A lone top satisfies no template: every one needs a bottom, a dress or a set.
    expect(buildSuggestions(context({ garments: [top] }), { count: 3 })).toEqual([]);
  });

  it('returns nothing for an empty wardrobe without touching the random source', async () => {
    const { buildSuggestions } = await import('./suggestion-engine');
    const random = vi.fn(() => 0.5);

    expect(buildSuggestions(context({ garments: [], random }), { count: 3 })).toEqual([]);
    expect(random).not.toHaveBeenCalled();
  });
});
