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
import { foldRatingIntoPair, garmentPairs } from '../src/domain/pair-learning';
import { filterGarments, sortGarments } from '../src/domain/garment-filtering';
import {
  EMPTY_FORM,
  brandSuggestions,
  displayedPreviewUri,
  galleryItems,
  normalizeForm,
  selectedHasOriginal,
  withBackgroundRemoved,
  withDetectedColor,
  withImage,
  withImagesReordered,
  withImportedPreview,
  withSubcategories,
  withoutImageAt,
  type GarmentFormState,
} from '../src/domain/garment-form';
import type { GarmentFilter, GarmentSortOption } from '../src/domain/garment-filtering';
import { OCCASION_OPTIONS, SEASON_OPTIONS } from '../src/constants/style-filters';
import type { SeasonOption, OccasionOption } from '../src/constants/style-filters';
import { getOccasionsFor } from '../src/utils/garment-occasions';
import {
  isLegacyAbsoluteImageRef,
  resolveImageRef,
  toStoredImageRef,
} from '../src/utils/image-paths';
import { normalizeGarmentRow } from '../src/utils/garment-fields';
import { ALTER_STATEMENTS, CREATE_TABLES_SQL, INDEX_STATEMENTS } from '../src/db/schema';
import {
  checkArchiveCompleteness,
  checkLegacyPayload,
  parseArchiveManifest,
} from '../src/domain/backup-archive';
import { analyticsView } from '../src/domain/analytics-view';
import { garmentDetail } from '../src/domain/garment-detail';
import {
  NO_FILTERS,
  isUnfiltered,
  occasionChips,
  seasonChips,
  withOccasionSelected,
  withSeasonToggled,
  type OutfitFilters,
} from '../src/domain/outfit-filters';
import { CATEGORIES } from '../src/constants/categories';
import type { Garment } from '../src/types';

const OUT_DIR = join(__dirname, '..', 'native', 'domain', 'src', 'test', 'resources', 'parity');

/** The mapping layer lives in its own module, so its fixtures do too. */
const DATA_OUT_DIR = join(__dirname, '..', 'native', 'data', 'src', 'test', 'resources', 'parity');

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


/** Photo references in every shape the app has ever written or received. */
const IMAGE_REFS = [
  '',
  'front.jpg',
  'garment-images/front.jpg',
  'file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/front.jpg',
  'file:///var/mobile/Containers/Data/Application/OLD-UUID/Documents/garment-images/x.jpg',
  '/absolute/no/scheme/front.jpg',
  'content://com.android.providers.media.documents/document/image%3A1000',
  'CONTENT://Uppercase/Scheme',
  'https://example.com/shirt.jpg',
  'HTTP://example.com/shirt.jpg',
  'data:image/png;base64,iVBORw0KGgo=',
  'blob:abcdef',
  'trailing/slash/',
  'no-extension',
  'spaces in name.jpg',
  'weird?query=1',
];

const IMAGE_DIRECTORIES = [
  '',
  'file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/',
  '/tmp/garment-images/',
];

function dumpImagePaths() {
  const lines: string[] = [];
  for (const ref of IMAGE_REFS) {
    for (const directory of IMAGE_DIRECTORIES) {
      lines.push(JSON.stringify({
        ref,
        directory,
        stored: toStoredImageRef(ref),
        resolved: resolveImageRef(ref, directory),
        legacy: isLegacyAbsoluteImageRef(ref),
      }));
    }
  }
  return lines;
}

/**
 * Rows in the shapes the table actually holds.
 *
 * List columns arrive as a JSON array from current builds, a bare
 * comma-separated string from much older ones, and sometimes as nothing at all.
 * Colour and photo columns each exist in a single-value and a list form, either
 * of which may be the populated one. is_available comes back as a SQLite
 * integer, but a restore can leave a string there -- and "0" is truthy in JS.
 */
const ROW_VARIANTS: Record<string, unknown>[] = [
  // A current, well-formed row.
  {
    id: 'g1', image_uri: 'front.jpg', image_uri_nobg: 'front-nobg.png',
    image_uris: '["front.jpg","back.jpg"]', image_uris_nobg: '["front-nobg.png",""]',
    category: 'tops', subcategory: 'T-Shirt', subcategories: '["T-Shirt"]',
    tags: '["Cotton","BASIC"]', brand: 'Uniqlo',
    color_primary: '#000000', color_secondary: '#FFFFFF',
    color_palette: '["#000000","#FFFFFF"]', size: 'M',
    purchase_date: '2026-01-01', is_available: 1, unavailable_date: null,
    created_at: '2026-01-01T00:00:00.000Z', updated_at: '2026-01-02T00:00:00.000Z',
  },
  // The upgraded-install shape: nullable timestamps, empty list columns.
  {
    id: 'g2', image_uri: 'only.jpg', image_uri_nobg: null,
    image_uris: '[]', image_uris_nobg: '[]',
    category: 'bottoms', subcategory: null, subcategories: '[]',
    tags: '[]', brand: null,
    color_primary: '#000080', color_secondary: null, color_palette: '[]', size: null,
    purchase_date: null, is_available: 1, unavailable_date: null,
    created_at: null, updated_at: null,
  },
  // The oldest shape: comma-separated lists, no JSON anywhere.
  {
    id: 'g3', image_uri: 'a.jpg', image_uri_nobg: '',
    image_uris: 'a.jpg, b.jpg', image_uris_nobg: '',
    category: 'shoes', subcategory: 'Boots', subcategories: 'Boots, Sneakers',
    tags: 'winter, leather', brand: '',
    color_primary: '#8B4513', color_secondary: '', color_palette: '#8B4513, #000000',
    size: '42', purchase_date: '', is_available: 0, unavailable_date: '2026-02-01',
    created_at: '2026-01-01T00:00:00.000Z', updated_at: '2026-01-01T00:00:00.000Z',
  },
  // Columns missing entirely, as a partial SELECT or a very old row gives.
  { id: 'g4', category: 'accessories' },
  // is_available as the string "0" -- truthy in JS, so this garment is available.
  {
    id: 'g5', image_uri: 'x.jpg', category: 'tops', is_available: '0',
    tags: '["a"]', color_primary: '#CC0000',
  },
  // is_available as the empty string and as 0, which are both falsy.
  { id: 'g6', image_uri: 'x.jpg', category: 'tops', is_available: '' },
  { id: 'g7', image_uri: 'x.jpg', category: 'tops', is_available: 0 },
  // Duplicates differing only in case, which the dedup has to collapse.
  {
    id: 'g8', image_uri: 'Front.JPG', image_uris: '["front.jpg","FRONT.JPG"]',
    category: 'tops', subcategories: '["T-Shirt","t-shirt"]',
    color_primary: '#ABCDEF', color_palette: '["#abcdef","#ABCDEF"]',
  },
  // Blank and whitespace entries that are not real values.
  {
    id: 'g9', image_uri: '   ', image_uris: '["  ","front.jpg"]',
    category: 'tops', tags: '["  ","cotton",""]',
    color_primary: '  ', color_palette: '["","#000000"]', size: '   ',
  },
  // Legacy absolute paths, which must be re-based rather than trusted.
  {
    id: 'g10',
    image_uri: 'file:///old/install/garment-images/front.jpg',
    image_uris: '["file:///old/install/garment-images/front.jpg"]',
    image_uri_nobg: 'file:///old/install/garment-images/front-nobg.png',
    category: 'tops', color_primary: '#000000',
  },
  // A SAF document and a remote URL, which must pass through untouched.
  {
    id: 'g11',
    image_uri: 'content://media/external/images/1',
    image_uris: '["content://media/external/images/1","https://example.com/a.jpg"]',
    category: 'tops', color_primary: '#000000',
  },
  // A list column holding valid JSON that is not an array.
  {
    id: 'g12', image_uri: 'x.jpg', category: 'tops',
    tags: '123', subcategories: '"T-Shirt"', color_palette: 'null',
    color_primary: '#000000',
  },
  // Malformed JSON, which falls back to the comma split.
  {
    id: 'g13', image_uri: 'x.jpg', category: 'tops',
    tags: '["unclosed', color_palette: '#000000',
    color_primary: '#000000',
  },
  // Non-string scalars where strings are expected.
  {
    id: 'g14', image_uri: 42, category: 'tops', color_primary: 7,
    is_available: true, size: 10, brand: 99,
  },
  // A no-background list shorter than the photo list, with its gap preserved.
  {
    id: 'g15', image_uri: 'a.jpg', image_uris: '["a.jpg","b.jpg","c.jpg"]',
    image_uris_nobg: '["a-nobg.png","","c-nobg.png"]',
    category: 'tops', color_primary: '#000000',
  },
];

function dumpGarmentRows() {
  const lines: string[] = [];
  for (const row of ROW_VARIANTS) {
    for (const directory of IMAGE_DIRECTORIES) {
      const normalized = normalizeGarmentRow(row, directory);
      lines.push(JSON.stringify({
        row,
        directory,
        normalized: {
          id: normalized.id,
          image_uri: normalized.image_uri,
          image_uri_nobg: normalized.image_uri_nobg,
          image_uris: normalized.image_uris,
          image_uris_nobg: normalized.image_uris_nobg,
          category: normalized.category,
          subcategory: normalized.subcategory,
          subcategories: normalized.subcategories,
          tags: normalized.tags,
          brand: normalized.brand ?? null,
          color_primary: normalized.color_primary,
          color_secondary: normalized.color_secondary,
          color_palette: normalized.color_palette,
          size: normalized.size ?? null,
          purchase_date: normalized.purchase_date ?? null,
          is_available: normalized.is_available,
          unavailable_date: normalized.unavailable_date ?? null,
          created_at: normalized.created_at ?? null,
          updated_at: normalized.updated_at ?? null,
        },
      }));
    }
  }
  return lines;
}


/**
 * The two schemas that exist in the wild, as SQL the port's tests can execute.
 *
 * Emitted from src/db/schema.ts rather than hand-copied, so the native data
 * layer is tested against the schema the app actually applies -- and so a change
 * to it shows up here as a fixture diff rather than as a surprise at runtime.
 *
 * The "upgraded" script starts from a table old enough to predate every ALTER,
 * which is what an install carried forward for long enough produces. It is the
 * one whose created_at/updated_at end up nullable.
 */
function dumpSchemas() {
  const fresh = [
    '-- A fresh install: CREATE TABLE, then the indexes.',
    CREATE_TABLES_SQL.trim(),
    ...INDEX_STATEMENTS.map(s => `${s};`),
  ].join('\n\n');

  // An install old enough to predate every ALTER. Emitted on its own as well as
  // inside the upgraded script, so the Kotlin schema test can start from
  // literally the same state rather than a second copy of it that can drift.
  const oldInstall = [
    '-- An install old enough to predate every additive ALTER.',
    `CREATE TABLE garments (
      id TEXT PRIMARY KEY,
      image_uri TEXT NOT NULL,
      category TEXT NOT NULL
    );`,
    `CREATE TABLE outfits (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      garment_ids TEXT NOT NULL DEFAULT '[]',
      occasion TEXT,
      season TEXT,
      created_at TEXT NOT NULL,
      is_suggested INTEGER NOT NULL DEFAULT 0
    );`,
  ].join('\n\n');

  const upgraded = [
    oldInstall,
    '-- Then the schema as applied on every start. Statements failing because',
    '-- the column exists are expected and ignored, exactly as the app does.',
    '-- Columns are added before the indexes over them, which is the order the',
    '-- app applies -- the other way round throws on an install this old.',
    CREATE_TABLES_SQL.trim(),
    ...ALTER_STATEMENTS.map(s => `${s};`),
    ...INDEX_STATEMENTS.map(s => `${s};`),
  ].join('\n\n');

  return { fresh, upgraded, oldInstall };
}


/**
 * The pair-learning arithmetic, across every rating transition.
 *
 * The undo step is the part worth pinning: a correction has to move the score to
 * where it would have been had the user rated correctly the first time, and must
 * not count a second wear. Every (existing state, rating, previous rating)
 * combination is recorded, including chains, so the inverse is checked at the
 * scores it actually reaches rather than only from zero.
 */
function dumpPairLearning() {
  const lines: string[] = [];
  const ratings = [1, 2, 3, 4, 5];

  const startingStates: (null | { score: number; wear_count: number })[] = [
    null,
    { score: 0, wear_count: 0 },
    { score: 0.3, wear_count: 1 },
    { score: -0.3, wear_count: 1 },
    { score: 0.9, wear_count: 7 },
    { score: -0.9, wear_count: 7 },
    { score: 0.123456789, wear_count: 3 },
  ];

  for (const existing of startingStates) {
    for (const rating of ratings) {
      // A fresh rating.
      lines.push(JSON.stringify({
        existing, rating, previous: null,
        next: foldRatingIntoPair(existing, rating, null),
      }));

      // A correction replacing each possible earlier rating.
      for (const previous of ratings) {
        lines.push(JSON.stringify({
          existing, rating, previous,
          next: foldRatingIntoPair(existing, rating, previous),
        }));
      }
    }
  }

  // Chains: rate, then correct, then correct again. The undo has to hold at
  // scores the process actually produces, not just at the round numbers above.
  let state = foldRatingIntoPair(null, 5, null);
  for (const [rating, previous] of [[1, 5], [4, 1], [2, 4], [5, 2]] as [number, number][]) {
    const next = foldRatingIntoPair(state, rating, previous);
    lines.push(JSON.stringify({ existing: state, rating, previous, next }));
    state = next;
  }

  return lines;
}

/** Pair enumeration, including the id-ordering that makes storage stable. */
function dumpGarmentPairs() {
  const wardrobes = [
    [],
    ['a'],
    ['a', 'b'],
    ['b', 'a'],
    ['c', 'a', 'b'],
    ['g10', 'g2', 'g1'],
    ['same', 'same'],
    ['a', 'b', 'c', 'd'],
  ];

  return wardrobes.map(ids => JSON.stringify({ ids, pairs: garmentPairs(ids) }));
}


/**
 * Wardrobe filtering and ordering, across the filter combinations the screens
 * can produce.
 *
 * The wardrobe below deliberately includes a garment with no timestamp: that is
 * the shape an install upgraded through the ALTER path can hold, and it used to
 * make the whole list disappear.
 */
function dumpGarmentFiltering() {
  const wardrobe: Garment[] = [
    garment({ id: 'a', subcategories: ['T-Shirt'], subcategory: 'T-Shirt', tags: ['cotton', 'summer'], brand: 'Uniqlo', color_primary: '#000000', color_palette: ['#000000'], size: 'M', created_at: '2026-01-01' }),
    garment({ id: 'b', subcategories: ['Blouse'], subcategory: 'Blouse', tags: ['Winter'], brand: 'COS', color_primary: '#FFFFFF', color_palette: ['#FFFFFF', '#CC0000'], size: 'S', created_at: '2026-03-01' }),
    garment({ id: 'c', subcategories: ['Hoodie'], subcategory: 'Hoodie', tags: [], brand: 'uniqlo', color_primary: '#808080', color_palette: ['#808080'], size: 'XL', created_at: '2026-02-01' }),
    garment({ id: 'd', subcategories: [], subcategory: 'Polo', tags: ['all-season'], brand: null, color_primary: '#000080', color_palette: ['#000080'], size: null, created_at: '2026-05-01' }),
    garment({ id: 'e', subcategories: ['Sneakers'], subcategory: 'Sneakers', category: 'shoes', tags: ['summer'], brand: 'Nike', color_primary: '#FFFFFF', color_palette: ['#FFFFFF'], size: '42', created_at: '2026-04-01' }),
    // No timestamp: the upgraded-install shape.
    garment({ id: 'f', subcategories: ['Jeans'], subcategory: 'Jeans', category: 'bottoms', tags: [], brand: 'Levi', color_primary: '#000080', color_palette: ['#000080'], size: 'M', created_at: null as unknown as string }),
  ];

  const filters: GarmentFilter[] = [
    {},
    { subcategory: 'T-Shirt' },
    { subcategory: 'Polo' },
    { season: 'summer' },
    { season: 'winter' },
    { season: 'all-season' },
    { occasion: 'work' },
    { occasion: 'sport' },
    { occasion: 'lounge' },
    { brand: 'uniqlo' },
    { brand: '  UNIQ ' },
    { brand: 'nope' },
    { size: 'm' },
    { size: '4' },
    { color: '#FFFFFF' },
    { color: '#CC0000' },
    { brand: 'Uniqlo', season: 'summer' },
    { occasion: 'casual', size: 'm' },
  ];

  const sorts: GarmentSortOption[] = ['newest', 'oldest'];
  const lines: string[] = [];

  for (const filter of filters) {
    const filtered = filterGarments(wardrobe, filter);
    for (const sort of sorts) {
      lines.push(JSON.stringify({
        filter,
        sort,
        ids: sortGarments(filtered, sort).map(g => g.id),
      }));
    }
  }

  return { lines, wardrobe };
}


/**
 * Form transitions, recorded step by step.
 *
 * Each step names an operation and records the whole state after it, so the port
 * is compared at every point in a sequence rather than only at the end -- two
 * implementations can disagree in the middle and coincide by the finish.
 */
type FormStep = { op: string; args?: unknown[] };

const FORM_SCRIPTS: { name: string; steps: FormStep[] }[] = [
  {
    name: 'build up a gallery',
    steps: [
      { op: 'withImage', args: ['a.jpg'] },
      { op: 'withImage', args: ['b.jpg'] },
      { op: 'withImage', args: ['c.jpg'] },
      { op: 'withBackgroundRemoved', args: ['c-nobg.png'] },
      { op: 'withImagesReordered', args: [2, 0] },
      { op: 'withoutImageAt', args: [1] },
      { op: 'withImage', args: ['d.jpg', true] },
    ],
  },
  {
    name: 'replace and remove around the selection',
    steps: [
      { op: 'withImage', args: ['a.jpg'] },
      { op: 'withImage', args: ['b.jpg'] },
      { op: 'withBackgroundRemoved', args: ['b-nobg.png'] },
      { op: 'withImage', args: ['b2.jpg', true] },
      { op: 'withoutImageAt', args: [0] },
      { op: 'withoutImageAt', args: [0] },
      { op: 'withImage', args: ['fresh.jpg', true] },
    ],
  },
  {
    name: 'seasons, colours and an import',
    steps: [
      { op: 'withSubcategories', args: [['Parka']] },
      { op: 'withDetectedColor', args: ['#000080'] },
      { op: 'withDetectedColor', args: ['#000080'] },
      { op: 'withDetectedColor', args: ['#CC0000'] },
      { op: 'withSubcategories', args: [['Sundress']] },
      { op: 'withImportedPreview', args: [['x.jpg', 'y.jpg'], 'Imported'] },
      { op: 'withImportedPreview', args: [['z.jpg'], 'Ignored'] },
    ],
  },
  {
    name: 'a cut-out-only photo has no original',
    steps: [
      { op: 'withImage', args: ['only.png'] },
      // Same path in both slots: imported already cut out.
      { op: 'withBackgroundRemoved', args: ['only.png'] },
      { op: 'withImage', args: ['second.jpg'] },
      { op: 'withBackgroundRemoved', args: ['second-nobg.png'] },
      { op: 'withImagesReordered', args: [1, 0] },
    ],
  },
  {
    name: 'out-of-range moves are no-ops',
    steps: [
      { op: 'withImage', args: ['a.jpg'] },
      { op: 'withImagesReordered', args: [0, 5] },
      { op: 'withImagesReordered', args: [-1, 0] },
      { op: 'withImagesReordered', args: [0, 0] },
      { op: 'withoutImageAt', args: [0] },
    ],
  },
];

/** A fixed season rule, so the fixture does not depend on the lookup table. */
const SCRIPT_SEASONS: Record<string, string[]> = {
  Parka: ['winter'],
  Sundress: ['summer'],
};

function dumpFormTransitions() {
  const lines: string[] = [];

  for (const script of FORM_SCRIPTS) {
    let state: GarmentFormState = normalizeForm(EMPTY_FORM);

    for (const [index, step] of script.steps.entries()) {
      const args = step.args ?? [];

      switch (step.op) {
        case 'withImage':
          state = withImage(state, args[0] as string, (args[1] as boolean) ?? false);
          break;
        case 'withoutImageAt':
          state = withoutImageAt(state, args[0] as number);
          break;
        case 'withImagesReordered':
          state = withImagesReordered(state, args[0] as number, args[1] as number);
          break;
        case 'withBackgroundRemoved':
          state = withBackgroundRemoved(state, args[0] as string);
          break;
        case 'withSubcategories':
          state = withSubcategories(
            state,
            args[0] as string[],
            subs => subs.flatMap(sub => SCRIPT_SEASONS[sub] ?? []) as GarmentFormState['seasons']
          );
          break;
        case 'withDetectedColor':
          state = withDetectedColor(state, args[0] as string);
          break;
        case 'withImportedPreview':
          state = withImportedPreview(state, {
            downloadedImageUris: args[0] as string[],
            brand: args[1] as string | null,
          });
          break;
        default:
          throw new Error(`Unknown form op: ${step.op}`);
      }

      lines.push(JSON.stringify({
        script: script.name,
        step: index,
        op: step.op,
        args,
        state,
        derived: {
          gallery: galleryItems(state),
          preview: displayedPreviewUri(state),
          hasOriginal: selectedHasOriginal(state),
        },
      }));
    }
  }

  return lines;
}

const BRAND_LISTS = [
  [],
  ['Uniqlo'],
  ['Uniqlo', 'Nike', 'New Balance', 'Adidas', 'COS'],
  Array.from({ length: 20 }, (_, i) => `Brand${i}`),
];

/**
 * Bringing a state into shape.
 *
 * The transition scripts all start from an already-aligned empty form, so the
 * padding and trimming never has work to do there. These are the inputs that
 * give it some.
 */
function dumpFormNormalization() {
  const inputs: Partial<GarmentFormState>[] = [
    {},
    { imageUris: ['a', 'b', 'c'] },
    { imageUris: ['a', 'b', 'c'], bgRemovedUris: ['a1'] },
    { imageUris: ['a', 'b', 'c'], bgRemovedUris: ['a1', '', 'c1'] },
    { imageUris: ['a'], bgRemovedUris: ['x', 'y', 'z'] },
    { imageUris: [], bgRemovedUris: ['orphan'] },
    { colorPalette: [] },
    { colorPalette: ['#FFFFFF', '#000000'] },
    { imageUris: ['a', 'b'], bgRemovedUris: ['a1'], colorPalette: [], brand: '  spaced  ', size: 'M' },
  ];

  return inputs.map(input => JSON.stringify({ input, normalized: normalizeForm(input) }));
}

function dumpBrandSuggestions() {
  const typed = ['', '   ', 'ni', 'NI', 'Nike', '  nike  ', 'qlo', 'zzz', 'brand1', 'a'];
  const lines: string[] = [];

  for (const known of BRAND_LISTS) {
    for (const input of typed) {
      lines.push(JSON.stringify({ known, typed: input, suggestions: brandSuggestions(known, input) }));
    }
  }

  return lines;
}


/**
 * Archive validation, which decides whether a wardrobe gets overwritten.
 *
 * Recorded as accept-or-reject *plus the message*, because the message is the
 * only thing telling someone whether to update the app or give up on the file.
 * A port that rejected the right archives with the wrong explanation would be a
 * worse app, and a comparison of booleans would not notice.
 */
function attempt(operation: () => void): { ok: true } | { ok: false; message: string } {
  try {
    operation();
    return { ok: true };
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : String(error) };
  }
}

function dumpArchiveValidation() {
  const manifests = [
    // Valid.
    '{"version":3}',
    '{"version":3,"created_at":"2026-01-01T00:00:00.000Z","image_count":12}',
    '{"version":3.0}',
    '{"version":3,"unexpected":"ignored"}',
    '{"version":3,"created_at":42,"image_count":"twelve"}',
    // Not JSON at all.
    '',
    'not json',
    '{"version":3',
    // JSON, but not an object describing a backup.
    '[]',
    '"3"',
    'null',
    '42',
    // No usable version.
    '{}',
    '{"version":"3"}',
    '{"version":null}',
    '{"version":true}',
    '{"version":3.5}',
    // Wrong version, in both directions.
    '{"version":4}',
    '{"version":99}',
    '{"version":2}',
    '{"version":1}',
    '{"version":0}',
    '{"version":-1}',
  ];

  const lines: string[] = [];

  for (const text of manifests) {
    lines.push(JSON.stringify({
      kind: 'manifest',
      input: text,
      result: attempt(() => parseArchiveManifest(text)),
    }));
  }

  // Completeness, against a valid manifest.
  const completeness: { imageCount?: number; hasDatabase: boolean; present: number }[] = [
    { hasDatabase: true, present: 0 },
    { hasDatabase: false, present: 0 },
    { hasDatabase: false, present: 5 },
    { imageCount: 0, hasDatabase: true, present: 0 },
    { imageCount: 5, hasDatabase: true, present: 5 },
    { imageCount: 5, hasDatabase: true, present: 4 },
    { imageCount: 5, hasDatabase: true, present: 6 },
    { imageCount: 5, hasDatabase: false, present: 5 },
  ];

  for (const c of completeness) {
    lines.push(JSON.stringify({
      kind: 'completeness',
      input: c,
      result: attempt(() => checkArchiveCompleteness({
        manifest: { version: 3, image_count: c.imageCount },
        hasDatabase: c.hasDatabase,
        imageCount: c.present,
      })),
    }));
  }

  // Legacy payloads.
  for (const version of [1, 2, 3, 0, 4]) {
    for (const database of ['data', '']) {
      lines.push(JSON.stringify({
        kind: 'legacy',
        input: { version, hasDatabase: Boolean(database) },
        result: attempt(() => checkLegacyPayload({ version, database })),
      }));
    }
  }

  return lines;
}

/**
 * What the detail screen shows, from the row up.
 *
 * The input is a raw row rather than a built garment, so the fixture exercises
 * the whole path -- normalization and then the view -- and a divergence in
 * either shows up here.
 */
const DETAIL_ROWS: Record<string, unknown>[] = [
  // The ordinary case: one photo, one colour, a brand and a size.
  {
    id: 'plain', image_uri: 'front.jpg', image_uri_nobg: null,
    image_uris: '["front.jpg"]', image_uris_nobg: '[]',
    category: 'tops', subcategory: 'T-Shirt', subcategories: '["T-Shirt"]',
    tags: '["cotton","summer","striped"]', brand: 'Uniqlo',
    color_primary: '#000000', color_secondary: null, color_palette: '["#000000"]',
    size: 'M', purchase_date: '2026-01-15', is_available: 1, unavailable_date: null,
    created_at: '2026-01-01T00:00:00.000Z', updated_at: '2026-01-01T00:00:00.000Z',
  },
  // Several photos, cut-outs on some of them.
  {
    id: 'gallery', image_uri: 'a.jpg', image_uri_nobg: 'a-cut.png',
    image_uris: '["a.jpg","b.jpg","c.jpg"]', image_uris_nobg: '["a-cut.png","","c-cut.png"]',
    category: 'outerwear', subcategory: 'Coat', subcategories: '["Coat"]',
    tags: '["wool","winter","fall"]', brand: ' COS ',
    color_primary: '#000080', color_secondary: '#FFFFFF',
    color_palette: '["#000080","#FFFFFF","#123456"]',
    size: ' L ', purchase_date: null, is_available: 1, unavailable_date: null,
    created_at: '2026-02-01T00:00:00.000Z', updated_at: '2026-02-01T00:00:00.000Z',
  },
  // A cut-out that replaced its original: no undo to offer.
  {
    id: 'replaced', image_uri: 'only-cut.png', image_uri_nobg: 'only-cut.png',
    image_uris: '["only-cut.png"]', image_uris_nobg: '["only-cut.png"]',
    category: 'bottoms', subcategory: 'Jeans', subcategories: '["Jeans"]',
    tags: '[]', brand: null,
    color_primary: '#000080', color_secondary: null, color_palette: '[]',
    size: null, purchase_date: null, is_available: 1, unavailable_date: null,
    created_at: '2026-03-01T00:00:00.000Z', updated_at: '2026-03-01T00:00:00.000Z',
  },
  // Unavailable, with the date it went.
  {
    id: 'retired', image_uri: 'old.jpg', image_uri_nobg: null,
    image_uris: '["old.jpg"]', image_uris_nobg: '[]',
    category: 'shoes', subcategory: 'Heels', subcategories: '["Heels"]',
    tags: '["leather"]', brand: 'Zara',
    color_primary: '#CC0000', color_secondary: null, color_palette: '["#cc0000"]',
    size: '38', purchase_date: '2024-06-01', is_available: 0,
    unavailable_date: '2026-04-02T10:11:12.000Z',
    created_at: '2024-06-01T00:00:00.000Z', updated_at: '2026-04-02T00:00:00.000Z',
  },
  // Available again, but the old unavailable_date is still on the row.
  {
    id: 'back-in-use', image_uri: 'again.jpg', image_uri_nobg: null,
    image_uris: '["again.jpg"]', image_uris_nobg: '[]',
    category: 'tops', subcategory: 'Shirt', subcategories: '["Shirt"]',
    tags: '["formal","rainy","linen"]', brand: '   ',
    color_primary: '#FFFFFF', color_secondary: null, color_palette: '["#FFFFFF"]',
    size: '', purchase_date: null, is_available: 1,
    unavailable_date: '2026-04-02T10:11:12.000Z',
    created_at: '2026-05-01T00:00:00.000Z', updated_at: '2026-05-01T00:00:00.000Z',
  },
  // No photo at all, and the multi-colour sentinel.
  {
    id: 'photoless', image_uri: '', image_uri_nobg: null,
    image_uris: '[]', image_uris_nobg: '[]',
    category: 'accessories', subcategory: 'Scarf', subcategories: '["Scarf","  "]',
    tags: '["silk","all-season"]', brand: 'Hermes',
    color_primary: '#RAINBOW', color_secondary: null, color_palette: '["#RAINBOW"]',
    size: null, purchase_date: null, is_available: 1, unavailable_date: null,
    created_at: '2026-06-01T00:00:00.000Z', updated_at: '2026-06-01T00:00:00.000Z',
  },
  // The much older row shape: comma-separated lists, no timestamps, no
  // subcategories list -- so occasions come from the singular column.
  {
    id: 'legacy', image_uri: 'legacy.jpg',
    image_uris: 'legacy.jpg,legacy-2.jpg',
    category: 'activewear', subcategory: 'Yoga Pants',
    tags: 'Stretch, SUMMER, stretch',
    color_primary: '#808080',
  },
  // A type the occasion table does not know, in a category that has a fallback.
  {
    id: 'unknown-type', image_uri: 'x.jpg', image_uri_nobg: null,
    image_uris: '["x.jpg"]', image_uris_nobg: '[]',
    category: 'loungewear', subcategory: 'Something New', subcategories: '["Something New"]',
    tags: '[]', brand: null,
    color_primary: '#F5F5DC', color_secondary: null, color_palette: '["#F5F5DC"]',
    size: 'S', purchase_date: null, is_available: 1, unavailable_date: null,
    created_at: '2026-07-01T00:00:00.000Z', updated_at: '2026-07-01T00:00:00.000Z',
  },
];

function dumpGarmentDetail() {
  const lines: string[] = [];

  for (const row of DETAIL_ROWS) {
    const garment = normalizeGarmentRow(row, '');
    // Every index the screen could hand over, plus two it should not: a
    // remembered selection can outlive the photo it referred to.
    for (const selected of [-1, 0, 1, 2, 5]) {
      const view = garmentDetail(garment, selected);
      lines.push(JSON.stringify({ row, selected, view }));
    }
  }

  return lines;
}

/**
 * Filter chip taps, recorded step by step.
 *
 * Each step is a tap and records the whole row afterwards, so the port is
 * compared at every point in a sequence rather than only at the end: two
 * implementations can disagree in the middle and coincide by the finish.
 */
const FILTER_SCRIPTS: { name: string; taps: { row: 'season' | 'occasion'; value: string | null }[] }[] = [
  { name: 'nothing tapped', taps: [] },
  {
    name: 'one season on and off',
    taps: [
      { row: 'season', value: 'summer' },
      { row: 'season', value: 'summer' },
    ],
  },
  {
    name: 'seasons tapped out of order',
    taps: [
      { row: 'season', value: 'winter' },
      { row: 'season', value: 'spring' },
      { row: 'season', value: 'all-season' },
    ],
  },
  {
    name: 'several seasons, then any',
    taps: [
      { row: 'season', value: 'fall' },
      { row: 'season', value: 'winter' },
      { row: 'season', value: null },
    ],
  },
  {
    name: 'one occasion replaced by another',
    taps: [
      { row: 'occasion', value: 'work' },
      { row: 'occasion', value: 'sport' },
    ],
  },
  {
    name: 'the active occasion tapped again',
    taps: [
      { row: 'occasion', value: 'formal' },
      { row: 'occasion', value: 'formal' },
    ],
  },
  {
    name: 'occasion cleared with any',
    taps: [
      { row: 'occasion', value: 'lounge' },
      { row: 'occasion', value: null },
    ],
  },
  {
    name: 'both rows, then each cleared in turn',
    taps: [
      { row: 'season', value: 'summer' },
      { row: 'occasion', value: 'casual' },
      { row: 'season', value: null },
      { row: 'occasion', value: null },
    ],
  },
  {
    name: 'every season on',
    taps: SEASON_OPTIONS.map(season => ({ row: 'season' as const, value: season })),
  },
  {
    name: 'every occasion in turn',
    taps: OCCASION_OPTIONS.map(occasion => ({ row: 'occasion' as const, value: occasion })),
  },
];

function outfitFilterState(filters: OutfitFilters) {
  return {
    seasons: filters.seasons,
    occasion: filters.occasion ?? null,
    unfiltered: isUnfiltered(filters),
    seasonChips: seasonChips(filters).map(c => ({ value: c.value, active: c.active })),
    occasionChips: occasionChips(filters).map(c => ({ value: c.value, active: c.active })),
  };
}

function dumpOutfitFilters() {
  const lines: string[] = [];

  for (const script of FILTER_SCRIPTS) {
    let filters = NO_FILTERS;
    // The starting row matters as much as the end of the sequence: "any" is
    // derived, so an implementation that stored it would already differ here.
    lines.push(JSON.stringify({ script: script.name, step: 0, tap: null, state: outfitFilterState(filters) }));

    script.taps.forEach((tap, index) => {
      filters = tap.row === 'season'
        ? withSeasonToggled(filters, tap.value as SeasonOption | null)
        : withOccasionSelected(filters, tap.value as OccasionOption | null);

      lines.push(JSON.stringify({
        script: script.name,
        step: index + 1,
        tap,
        state: outfitFilterState(filters),
      }));
    });
  }

  return lines;
}

/**
 * The analytics bars.
 *
 * A corpus of shapes rather than of realistic wardrobes: the arithmetic is what
 * is being compared, so the interesting inputs are the ones at the edges -- an
 * empty wardrobe, counts that exceed the total, a negative lifespan, a garment
 * owned for a decade.
 */
const ANALYTICS_CASES: {
  name: string;
  totalItems: number;
  archivedItems: number;
  categoryCounts: { category: string; count: number }[];
  lifespans: { garmentId: string; category: string; subcategories: string[]; days: number }[];
}[] = [
  { name: 'empty wardrobe', totalItems: 0, archivedItems: 0, categoryCounts: [], lifespans: [] },
  {
    name: 'nothing but retired garments',
    totalItems: 0,
    archivedItems: 4,
    categoryCounts: [],
    lifespans: [{ garmentId: 'r1', category: 'tops', subcategories: ['T-Shirt'], days: 500 }],
  },
  {
    name: 'one category',
    totalItems: 4,
    archivedItems: 0,
    categoryCounts: [{ category: 'tops', count: 4 }],
    lifespans: [],
  },
  {
    name: 'an even split',
    totalItems: 10,
    archivedItems: 2,
    categoryCounts: [
      { category: 'tops', count: 5 },
      { category: 'bottoms', count: 5 },
    ],
    lifespans: [],
  },
  {
    name: 'thirds, which do not divide evenly',
    totalItems: 3,
    archivedItems: 0,
    categoryCounts: [
      { category: 'tops', count: 1 },
      { category: 'bottoms', count: 1 },
      { category: 'shoes', count: 1 },
    ],
    lifespans: [],
  },
  {
    name: 'a long tail',
    totalItems: 21,
    archivedItems: 7,
    categoryCounts: [
      { category: 'tops', count: 9 },
      { category: 'bottoms', count: 6 },
      { category: 'shoes', count: 3 },
      { category: 'outerwear', count: 2 },
      { category: 'accessories', count: 1 },
    ],
    lifespans: [],
  },
  {
    // Impossible from the queries as they stand -- both count the same rows --
    // which is exactly why it is here: the guard exists for the day they stop
    // agreeing, and dividing by zero is a NaN rather than a rounding error.
    name: 'a category count against a zero total',
    totalItems: 0,
    archivedItems: 0,
    categoryCounts: [{ category: 'tops', count: 3 }],
    lifespans: [],
  },
  {
    name: 'counts that exceed the total',
    totalItems: 2,
    archivedItems: 0,
    categoryCounts: [{ category: 'tops', count: 5 }],
    lifespans: [],
  },
  {
    name: 'a zero count',
    totalItems: 5,
    archivedItems: 0,
    categoryCounts: [
      { category: 'tops', count: 5 },
      { category: 'shoes', count: 0 },
    ],
    lifespans: [],
  },
  {
    name: 'lifespans around the year mark',
    totalItems: 6,
    archivedItems: 4,
    categoryCounts: [{ category: 'tops', count: 6 }],
    lifespans: [
      { garmentId: 'a', category: 'outerwear', subcategories: ['Coat'], days: 3650 },
      { garmentId: 'b', category: 'tops', subcategories: ['T-Shirt'], days: 365 },
      { garmentId: 'c', category: 'shoes', subcategories: ['Sneakers'], days: 73 },
      { garmentId: 'd', category: 'bottoms', subcategories: ['Jeans'], days: 1 },
    ],
  },
  {
    name: 'a garment retired before it was bought',
    totalItems: 3,
    archivedItems: 1,
    categoryCounts: [{ category: 'tops', count: 3 }],
    lifespans: [
      { garmentId: 'backwards', category: 'tops', subcategories: [], days: -30 },
      { garmentId: 'sameday', category: 'tops', subcategories: ['Shirt'], days: 0 },
    ],
  },
];

function dumpAnalyticsView() {
  return ANALYTICS_CASES.map(testCase => {
    const view = analyticsView({
      totalItems: testCase.totalItems,
      archivedItems: testCase.archivedItems,
      categoryCounts: testCase.categoryCounts,
      lifespans: testCase.lifespans,
    });

    return JSON.stringify({
      name: testCase.name,
      input: {
        totalItems: testCase.totalItems,
        archivedItems: testCase.archivedItems,
        categoryCounts: testCase.categoryCounts,
        lifespans: testCase.lifespans,
      },
      view: {
        totalItems: view.totalItems,
        archivedItems: view.archivedItems,
        isEmpty: view.isEmpty,
        categories: view.categories.map(b => ({
          key: b.key, category: b.category, value: b.value, fraction: b.fraction,
        })),
        lifespans: view.lifespans.map(b => ({
          key: b.key, value: b.value, fraction: b.fraction,
          category: b.entry.category, subcategories: b.entry.subcategories,
        })),
      },
    });
  });
}

mkdirSync(OUT_DIR, { recursive: true });
mkdirSync(DATA_OUT_DIR, { recursive: true });

const files: Record<string, string[]> = {
  'colors.jsonl': dumpColors(),
  'tags.jsonl': dumpTags(),
  'duplicates.jsonl': dumpDuplicates(),
  'occasions.jsonl': dumpOccasions(),
  'suggestions.jsonl': dumpSuggestions(),
  'pair-learning.jsonl': dumpPairLearning(),
  'garment-pairs.jsonl': dumpGarmentPairs(),
};

// The wardrobe and pair scores live in the fixture rather than being rebuilt on
// the Kotlin side: two generators that were meant to agree would be one more
// thing that can silently disagree.
writeFileSync(
  join(OUT_DIR, 'wardrobe.json'),
  JSON.stringify({ garments: WARDROBE, pairScores: PAIR_SCORES }, null, 2) + '\n'
);

const dataFiles: Record<string, string[]> = {
  'image-paths.jsonl': dumpImagePaths(),
  'garment-rows.jsonl': dumpGarmentRows(),
};

for (const [name, lines] of Object.entries(files)) {
  writeFileSync(join(OUT_DIR, name), lines.join('\n') + '\n');
  console.log(`domain/${name}: ${lines.length} cases`);
}

for (const [name, lines] of Object.entries(dataFiles)) {
  writeFileSync(join(DATA_OUT_DIR, name), lines.join('\n') + '\n');
  console.log(`data/${name}: ${lines.length} cases`);
}

const PRESENTATION_OUT_DIR = join(
  __dirname, '..', 'native', 'presentation', 'src', 'test', 'resources', 'parity'
);
mkdirSync(PRESENTATION_OUT_DIR, { recursive: true });

const filtering = dumpGarmentFiltering();
writeFileSync(join(PRESENTATION_OUT_DIR, 'garment-filtering.jsonl'), filtering.lines.join('\n') + '\n');
writeFileSync(
  join(PRESENTATION_OUT_DIR, 'filtering-wardrobe.json'),
  JSON.stringify(filtering.wardrobe, null, 2) + '\n'
);
console.log(`presentation/garment-filtering.jsonl: ${filtering.lines.length} cases`);

const formTransitions = dumpFormTransitions();
writeFileSync(join(PRESENTATION_OUT_DIR, 'form-transitions.jsonl'), formTransitions.join('\n') + '\n');
console.log(`presentation/form-transitions.jsonl: ${formTransitions.length} cases`);

const normalization = dumpFormNormalization();
writeFileSync(
  join(PRESENTATION_OUT_DIR, 'form-normalization.jsonl'),
  normalization.join('\n') + '\n'
);
console.log(`presentation/form-normalization.jsonl: ${normalization.length} cases`);

const brands = dumpBrandSuggestions();
writeFileSync(join(PRESENTATION_OUT_DIR, 'brand-suggestions.jsonl'), brands.join('\n') + '\n');
console.log(`presentation/brand-suggestions.jsonl: ${brands.length} cases`);

const archiveValidation = dumpArchiveValidation();
writeFileSync(
  join(DATA_OUT_DIR, 'archive-validation.jsonl'),
  archiveValidation.join('\n') + '\n'
);
console.log(`data/archive-validation.jsonl: ${archiveValidation.length} cases`);

const detail = dumpGarmentDetail();
writeFileSync(join(PRESENTATION_OUT_DIR, 'garment-detail.jsonl'), detail.join('\n') + '\n');
console.log(`presentation/garment-detail.jsonl: ${detail.length} cases`);

const outfitFilters = dumpOutfitFilters();
writeFileSync(join(PRESENTATION_OUT_DIR, 'outfit-filters.jsonl'), outfitFilters.join('\n') + '\n');
console.log(`presentation/outfit-filters.jsonl: ${outfitFilters.length} cases`);

const analytics = dumpAnalyticsView();
writeFileSync(join(PRESENTATION_OUT_DIR, 'analytics-view.jsonl'), analytics.join('\n') + '\n');
console.log(`presentation/analytics-view.jsonl: ${analytics.length} cases`);

const schemas = dumpSchemas();
writeFileSync(join(DATA_OUT_DIR, 'schema-fresh.sql'), schemas.fresh + '\n');
writeFileSync(join(DATA_OUT_DIR, 'schema-upgraded.sql'), schemas.upgraded + '\n');
writeFileSync(join(DATA_OUT_DIR, 'schema-old-install.sql'), schemas.oldInstall + '\n');
console.log(`data/schema-fresh.sql: ${schemas.fresh.split('\n').length} lines`);
console.log(`data/schema-upgraded.sql: ${schemas.upgraded.split('\n').length} lines`);
console.log(`data/schema-old-install.sql: ${schemas.oldInstall.split('\n').length} lines`);
