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

mkdirSync(OUT_DIR, { recursive: true });

const files: Record<string, string[]> = {
  'colors.jsonl': dumpColors(),
  'tags.jsonl': dumpTags(),
  'duplicates.jsonl': dumpDuplicates(),
  'occasions.jsonl': dumpOccasions(),
};

for (const [name, lines] of Object.entries(files)) {
  writeFileSync(join(OUT_DIR, name), lines.join('\n') + '\n');
  console.log(`${name}: ${lines.length} cases`);
}
