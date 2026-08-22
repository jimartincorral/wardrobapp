/**
 * Generate parity fixtures for the Kotlin domain port.
 *
 * The Kotlin module under native/domain is a port, not a rewrite, so the
 * interesting question is not "does it pass tests someone wrote for it" but
 * "does it agree with the implementation it was ported from". This dumps the
 * TypeScript answers for a fixed corpus of inputs; the Kotlin side reads the
 * same files and asserts it produces the same outputs.
 *
 * Run with: npm run parity:dump
 *
 * Only imports from src/domain, src/utils and src/constants -- the layers that
 * are free of React Native by construction, which is what makes running them
 * under plain node possible at all.
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

import { GARMENT_COLORS } from '../src/constants/colors';
import {
  colorDistance,
  colorHarmonyScore,
  colorRelationship,
  colorSimilarity,
} from '../src/utils/color-distance';
import { jaccardSimilarity } from '../src/utils/tag-similarity';
import { findDuplicatesAmong } from '../src/domain/duplicate-detection';
import { buildSuggestions, pairKey } from '../src/domain/outfit-suggestions';
import type { SeasonOption, OccasionOption } from '../src/constants/style-filters';
import { getOccasionsFor } from '../src/utils/garment-occasions';
import { CATEGORIES } from '../src/constants/categories';
import type { Garment } from '../src/types';

const OUT_DIR = join(__dirname, '..', 'native', 'domain', 'src', 'test', 'resources', 'parity');

/** Every colour the picker offers, plus the shapes that arrive from elsewhere. */
const EDGE_CASE_COLORS = [
  '#fff',            // 3-digit: used to parse as [255, 15, NaN]
  '#ABC',
  '  #000000  ',     // padded, as a restored backup can carry
  '#cc0000',         // lowercase form of a palette entry
  '#rainbow',        // sentinel in the wrong case
  '',
  'not-a-color',
  '#12345',          // wrong length
  '#GGGGGG',         // right length, not hex
];

const COLORS = [...GARMENT_COLORS.map(c => c.hex), ...EDGE_CASE_COLORS];

function dumpColors() {
  const lines: string[] = [];
  for (const a of COLORS) {
    for (const b of COLORS) {
      lines.push(JSON.stringify({
        a,
        b,
        distance: colorDistance(a, b),
        similarity: colorSimilarity(a, b),
        relationship: colorRelationship(a, b),
        harmony: colorHarmonyScore(a, b),
      }));
    }
  }
  return lines;
}

const TAG_SETS: string[][] = [
  [],
  [''],
  ['  '],
  ['cotton'],
  ['cotton', 'basic'],
  ['Cotton', 'BASIC'],
  ['cotton', 'basic', 'summer'],
  ['cotton', ''],
  ['  cotton  '],
  ['wool', 'formal'],
  ['a', 'b'],
  ['a', 'c'],
  ['cotton', 'cotton'],
];

function dumpTags() {
  const lines: string[] = [];
  for (const a of TAG_SETS) {
    for (const b of TAG_SETS) {
      lines.push(JSON.stringify({ a, b, similarity: jaccardSimilarity(a, b) }));
    }
  }
  return lines;
}

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

/**
 * Duplicate-detection scenarios. Deliberately spans the cases the score has to
 * get right -- absent tags, absent sizes, shared palette entries, partial tag
 * overlap -- rather than only the obvious hit and obvious miss.
 */
const DUPLICATE_SCENARIOS = (() => {
  const candidateTagSets = [[], ['cotton'], ['cotton', 'basic'], ['a', 'b'], ['wool', 'formal']];
  const candidateSizes: (string | null)[] = ['M', 'L', null];
  const candidateColors = ['#000000', '#CC0000', '#F5F5DC', '#RAINBOW'];

  const existingVariants = [
    garment({ id: 'twin', tags: ['cotton', 'basic'] }),
    garment({ id: 'untagged', tags: [] }),
    garment({ id: 'onetag', tags: ['cotton'] }),
    garment({ id: 'nosize', tags: ['cotton', 'basic'], size: null }),
    garment({ id: 'partial', tags: ['a', 'c'], color_primary: '#808080', color_palette: ['#808080'] }),
    garment({
      id: 'sharedblack',
      tags: ['a'],
      color_primary: '#0066CC',
      color_palette: ['#0066CC', '#000000'],
    }),
    garment({ id: 'blank-size', tags: ['cotton', 'basic'], size: '   ' }),
  ];

  const scenarios: { candidate: any; existing: Garment[] }[] = [];
  for (const tags of candidateTagSets) {
    for (const size of candidateSizes) {
      for (const color of candidateColors) {
        scenarios.push({
          candidate: {
            category: 'tops',
            tags,
            color_primary: color,
            color_palette: [color],
            size,
          },
          existing: existingVariants,
        });
      }
    }
  }
  return scenarios;
})();

function dumpDuplicates() {
  return DUPLICATE_SCENARIOS.map(({ candidate, existing }) => {
    // Threshold -1 so every pair is reported: the fixture then pins the score
    // for every comparison, not just the ones that clear the default bar.
    const matches = findDuplicatesAmong(candidate, existing, -1);
    return JSON.stringify({
      candidate,
      existing: existing.map(g => ({
        id: g.id,
        category: g.category,
        tags: g.tags,
        color_primary: g.color_primary,
        color_palette: g.color_palette,
        size: g.size,
      })),
      matches: matches.map(m => ({ id: m.garment.id, score: m.score, reason: m.reason })),
    });
  });
}

function dumpOccasions() {
  const lines: string[] = [];
  const categoryKeys = Object.keys(CATEGORIES);
  const subcategories = new Set<string>();
  for (const key of categoryKeys) {
    for (const sub of CATEGORIES[key as keyof typeof CATEGORIES].subcategories) {
      subcategories.add(sub);
    }
  }

  for (const category of categoryKeys) {
    // Every subcategory against every category, not just its own: the fallback
    // path only shows up when the pairing is unrecognised.
    for (const sub of [null, ...subcategories]) {
      lines.push(JSON.stringify({
        category,
        subcategories: sub === null ? [] : [sub],
        occasions: getOccasionsFor(category, sub === null ? [] : [sub]),
      }));
    }
  }
  return lines;
}


/**
 * A linear congruential generator, specified precisely enough to reimplement.
 *
 * The engine takes its randomness as a parameter, which is what makes a run
 * reproducible -- and what lets the port be compared draw for draw. Math.random
 * cannot do that, so both sides run this instead: state and output are exact in
 * IEEE-754 doubles (the widest intermediate is under 2^53), so the Kotlin and
 * TypeScript sequences are identical rather than merely similar.
 */
function lcg(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296;
    return state / 4294967296;
  };
}

/** A wardrobe spanning the category, colour and tag space the engine branches on. */
function buildWardrobe(): Garment[] {
  // subcategory is nullable on purpose: a garment with none takes the
  // category-fallback path in both slot mapping and occasion derivation.
  const specs: [string, string | null, string, string[]][] = [
    ['tops', 'T-Shirt', '#000000', ['cotton', 'summer']],
    ['tops', 'Blouse', '#FFFFFF', ['work']],
    ['tops', 'Sweater', '#000080', ['winter', 'wool']],
    ['tops', 'Hoodie', '#808080', ['all-season']],
    ['tops', 'Polo', '#CC0000', []],
    ['bottoms', 'Jeans', '#000080', ['all-season']],
    ['bottoms', 'Chinos', '#C3B091', ['work']],
    ['bottoms', 'Shorts', '#F5F5DC', ['summer']],
    ['bottoms', 'Sweatpants', '#808080', ['winter']],
    ['dresses', 'Midi', '#800020', ['work']],
    ['dresses', 'Sundress', '#FF69B4', ['summer']],
    ['outerwear', 'Parka', '#228B22', ['winter']],
    ['outerwear', 'Windbreaker', '#FF8C00', ['lightweight']],
    ['outerwear', 'Cardigan', '#D2B48C', []],
    ['outerwear', 'Coat', '#000000', ['winter', 'wool']],
    ['midlayer', 'Blazer', '#000080', ['work']],
    ['shoes', 'Sneakers', '#FFFFFF', []],
    ['shoes', 'Heels', '#000000', ['formal']],
    ['shoes', 'Boots', '#8B4513', ['winter']],
    ['accessories', 'Belt', '#8B4513', []],
    ['accessories', 'Scarf', '#RAINBOW', ['winter']],
    ['accessories', 'Watch', '#C0C0C0', []],
    ['activewear', 'Track Suit', '#000000', ['sport']],
    ['activewear', 'Yoga Pants', '#800080', ['sport']],
    ['activewear', 'Workout Top', '#008080', ['sport', 'summer']],
    ['loungewear', 'Lounge Set', '#E6E6FA', ['lounge']],
    ['loungewear', 'Robe', '#FFFDD0', []],
    ['underwear', 'Thermal', '#FFFFFF', ['winter']],
    ['tops', null, '#DAA520', ['spring']],
    ['bottoms', 'Skirt', '#fff', ['spring']],
  ];

  return specs.map(([category, subcategory, hex, tags], i) => garment({
    // Zero-padded so the lexicographic sort the dedup key relies on is stable.
    id: `g${String(i).padStart(2, '0')}`,
    category,
    subcategory,
    subcategories: subcategory ? [subcategory] : [],
    tags,
    color_primary: hex,
    color_palette: [hex],
    size: i % 3 === 0 ? 'M' : (i % 3 === 1 ? 'L' : null),
  }));
}

const WARDROBE = buildWardrobe();

/**
 * Learned pair scores for a slice of the wardrobe, so the branch that weights
 * them is exercised rather than left at a uniform zero.
 */
function buildPairScores(): Record<string, number> {
  const scores: Record<string, number> = {};
  for (let i = 0; i < WARDROBE.length; i += 3) {
    for (let j = i + 1; j < Math.min(i + 4, WARDROBE.length); j++) {
      // Deterministic, spread across positive and negative.
      scores[pairKey(WARDROBE[i].id, WARDROBE[j].id)] = ((i * 7 + j * 13) % 9) / 4 - 1;
    }
  }
  return scores;
}

const PAIR_SCORES = buildPairScores();

function dumpSuggestions() {
  const lines: string[] = [];

  const preferenceSets: { seasons?: SeasonOption[]; occasion?: OccasionOption }[] = [
    {},
    { seasons: ['summer'] },
    { seasons: ['winter'] },
    { seasons: ['all-season'] },
    { seasons: ['spring', 'fall'] },
    { occasion: 'work' },
    { occasion: 'sport' },
    { seasons: ['summer'], occasion: 'casual' },
  ];

  const seedSets: string[][] = [[], ['g00'], ['g05'], ['g00', 'g16']];
  const wardrobeSizes = [WARDROBE.length, 8, 2];

  let scenario = 0;
  for (const size of wardrobeSizes) {
    const garments = WARDROBE.slice(0, size);
    const ids = new Set(garments.map(g => g.id));

    for (const preferences of preferenceSets) {
      for (const seedIds of seedSets) {
        // Skip seeds the trimmed wardrobe does not contain.
        if (!seedIds.every(id => ids.has(id))) continue;

        for (const withLearning of [false, true]) {
          for (const seed of [1, 20260822, 4294967295]) {
            const getPairScore = withLearning
              ? (a: string, b: string) => PAIR_SCORES[pairKey(a, b)] ?? 0
              : (_a: string, _b: string) => 0;

            const outfits = buildSuggestions(
              { garments, getPairScore, currentSeason: 'fall', random: lcg(seed) },
              {
                count: 3,
                preferences: Object.keys(preferences).length > 0 ? preferences : undefined,
                seedGarments: garments.filter(g => seedIds.includes(g.id)),
              }
            );

            lines.push(JSON.stringify({
              scenario: scenario++,
              seed,
              wardrobeSize: size,
              currentSeason: 'fall',
              preferences,
              seedIds,
              withLearning,
              outfits: outfits.map(o => ({
                ids: o.garments.map(g => g.id),
                score: o.score,
                name: o.name,
              })),
            }));
          }
        }
      }
    }
  }

  return lines;
}

mkdirSync(OUT_DIR, { recursive: true });

const files: Record<string, string[]> = {
  'colors.jsonl': dumpColors(),
  'tags.jsonl': dumpTags(),
  'duplicates.jsonl': dumpDuplicates(),
  'occasions.jsonl': dumpOccasions(),
  'suggestions.jsonl': dumpSuggestions(),
};

// The wardrobe and pair scores live in the fixture rather than being rebuilt on
// the Kotlin side: two generators that were meant to agree would be one more
// thing that can silently disagree.
writeFileSync(
  join(OUT_DIR, 'wardrobe.json'),
  JSON.stringify({ garments: WARDROBE, pairScores: PAIR_SCORES }, null, 2) + '\n'
);

for (const [name, lines] of Object.entries(files)) {
  writeFileSync(join(OUT_DIR, name), lines.join('\n') + '\n');
  console.log(`${name}: ${lines.length} cases`);
}
