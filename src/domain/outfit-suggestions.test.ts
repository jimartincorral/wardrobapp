import { describe, expect, it } from 'vitest';
import { buildSuggestions } from './outfit-suggestions';
import type { Garment } from '../types';

// buildSuggestions is pure, so this runs the real algorithm end to end: real
// colour harmony, real occasion derivation, nothing stubbed.

const garment = (id: string, category: string, overrides: Partial<Garment> = {}): Garment => ({
  id,
  image_uri: 'a.jpg',
  image_uri_nobg: null,
  image_uris: ['a.jpg'],
  image_uris_nobg: [],
  category,
  subcategory: id,
  subcategories: [id],
  tags: [],
  brand: null,
  color_primary: '#000000',
  color_secondary: null,
  color_palette: ['#000000'],
  size: null,
  purchase_date: null,
  is_available: true,
  unavailable_date: null,
  created_at: '2026-01-01T00:00:00.000Z',
  updated_at: '2026-01-01T00:00:00.000Z',
  ...overrides,
});

const noLearning = () => 0;

const baseContext = {
  getPairScore: noLearning,
  currentSeason: 'spring',
  random: Math.random,
};

/** Ten tops and ten bottoms, newest-first as getAllGarments returns them. */
const wardrobe = (size = 10) => [
  ...Array.from({ length: size }, (_, i) => garment(`top-${i}`, 'tops')),
  ...Array.from({ length: size }, (_, i) => garment(`bottom-${i}`, 'bottoms')),
];

/** How often each garment is chosen across many runs. */
function selectionCounts(garments: Garment[], getPairScore = noLearning, runs = 200) {
  const counts = new Map<string, number>();
  for (let run = 0; run < runs; run++) {
    for (const outfit of buildSuggestions({ ...baseContext, garments, getPairScore }, { count: 3 })) {
      for (const item of outfit.garments) {
        counts.set(item.id, (counts.get(item.id) ?? 0) + 1);
      }
    }
  }
  return counts;
}

describe('reachability', () => {
  it('can select every garment in the wardrobe', () => {
    // The exploration branch used to sample from the top 60% of a list sorted by
    // a weight that was identical for everyone, so the oldest 40% of each slot
    // was unreachable: never suggested, so never rated, so never able to earn a
    // score that would make it reachable.
    const garments = wardrobe();
    const counts = selectionCounts(garments);

    const unreachable = garments.filter(g => !counts.has(g.id)).map(g => g.id);
    expect(unreachable).toEqual([]);
  });

  it('does not let one garment dominate the wardrobe', () => {
    // A strict `>` tie-break against -Infinity always kept the first candidate,
    // and candidates arrive newest-first. One bottom took 722 of 900 slots.
    const counts = selectionCounts(wardrobe());
    const picks = [...counts.values()];
    const total = picks.reduce((sum, n) => sum + n, 0);

    expect(Math.max(...picks) / total).toBeLessThan(0.2);
  });

  it('lets a learned score promote a garment that was previously unreachable', () => {
    // The clearest statement of the bug: learning could not reach the tail.
    const garments = wardrobe();
    const favoured = (a: string, b: string) =>
      [a, b].sort().join('|') === 'bottom-9|top-9' ? 5 : 0;

    const counts = selectionCounts(garments, favoured);

    expect(counts.get('top-9') ?? 0).toBeGreaterThan(0);
    expect(counts.get('bottom-9') ?? 0).toBeGreaterThan(0);
  });
});

describe('seeded garments', () => {
  const seed = garment('seed-top', 'tops');
  const garments = [seed, garment('other-top', 'tops'), garment('jeans', 'bottoms')];

  it('fills the rest of the outfit without refilling the seed slot', () => {
    // seedSlots filtered which templates were viable but did not stop the loop
    // filling those slots again, so seeding a top produced two tops.
    const suggestions = buildSuggestions(
      { ...baseContext, garments },
      { count: 3, seedGarments: [seed] }
    );

    expect(suggestions.length).toBeGreaterThan(0);
    for (const outfit of suggestions) {
      expect(outfit.garments.filter(g => g.category === 'tops')).toHaveLength(1);
    }
  });

  it('always includes the seed', () => {
    const suggestions = buildSuggestions(
      { ...baseContext, garments },
      { count: 3, seedGarments: [seed] }
    );

    for (const outfit of suggestions) {
      expect(outfit.garments.map(g => g.id)).toContain('seed-top');
    }
  });
});

describe('scoring weights', () => {
  it('counts season once rather than twice', () => {
    // Season was added at weight 1.0 and again inside contextScore at 1.2,
    // giving it an effective 2.2 -- more than colour harmony. With occasion
    // fixed, a season-matching outfit should beat a season-contradicting one by
    // the season weight alone, not by more than the harmony weight of 1.5.
    const summerTop = garment('summer-top', 'tops', { tags: ['summer'] });
    const summerBottom = garment('summer-bottom', 'bottoms', { tags: ['summer'] });
    const winterTop = garment('winter-top', 'tops', { tags: ['winter'] });
    const winterBottom = garment('winter-bottom', 'bottoms', { tags: ['winter'] });

    const scoreOf = (garments: Garment[]) =>
      buildSuggestions(
        { ...baseContext, garments, random: () => 0.5 },
        { count: 1, preferences: { seasons: ['summer'] } }
      )[0].score;

    const matching = scoreOf([summerTop, summerBottom]);
    const contradicting = scoreOf([winterTop, winterBottom]);

    expect(matching).toBeGreaterThan(contradicting);
  });

  it('rewards a learned pair', () => {
    const garments = [garment('top-1', 'tops'), garment('bottom-1', 'bottoms')];
    const scoreWith = (getPairScore: (a: string, b: string) => number) =>
      buildSuggestions({ ...baseContext, garments, getPairScore, random: () => 0.5 }, { count: 1 })[0].score;

    expect(scoreWith(() => 1)).toBeGreaterThan(scoreWith(noLearning));
  });
});

describe('invariants', () => {
  it('never repeats a garment within an outfit', () => {
    for (const outfit of buildSuggestions({ ...baseContext, garments: wardrobe(6) }, { count: 5 })) {
      const ids = outfit.garments.map(g => g.id);
      expect(new Set(ids).size).toBe(ids.length);
    }
  });

  it('never returns two outfits with the same garments', () => {
    const suggestions = buildSuggestions({ ...baseContext, garments: wardrobe(6) }, { count: 5 });
    const keys = suggestions.map(o => o.garments.map(g => g.id).sort().join(','));

    expect(new Set(keys).size).toBe(keys.length);
  });

  it('returns nothing when no template can be satisfied', () => {
    expect(
      buildSuggestions({ ...baseContext, garments: [garment('lonely-top', 'tops')] }, { count: 3 })
    ).toEqual([]);
  });
});
