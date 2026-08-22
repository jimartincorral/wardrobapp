import { describe, expect, it } from 'vitest';
import {
  colorDistance,
  colorHarmonyScore,
  colorRelationship,
  colorSimilarity,
  parseHexColor,
} from './color-distance';
import { GARMENT_COLORS } from '../constants/colors';

const HEX = Object.fromEntries(GARMENT_COLORS.map(c => [c.key, c.hex])) as Record<string, string>;
const PALETTE = GARMENT_COLORS.filter(c => c.hex !== '#RAINBOW').map(c => c.hex);

describe('parseHexColor', () => {
  it('parses six-digit hex in either case, with or without the hash', () => {
    expect(parseHexColor('#CC0000')).toEqual([204, 0, 0]);
    expect(parseHexColor('cc0000')).toEqual([204, 0, 0]);
    expect(parseHexColor('#cC0000')).toEqual([204, 0, 0]);
  });

  it('expands three-digit hex', () => {
    // The old parser read '#fff' as [255, 15, NaN].
    expect(parseHexColor('#fff')).toEqual([255, 255, 255]);
    expect(parseHexColor('#0a0')).toEqual([0, 170, 0]);
  });

  it('rejects anything else rather than returning NaN components', () => {
    for (const bad of ['', '#', '#ff', '#fffff', '#gggggg', 'rebeccapurple', '#RAINBOW']) {
      expect(parseHexColor(bad)).toBeNull();
    }
  });
});

describe('colorDistance', () => {
  it('reports 0 for the same colour, whatever the casing', () => {
    expect(colorDistance('#CC0000', '#cc0000')).toBe(0);
    expect(colorDistance('#CC0000', 'CC0000')).toBe(0);
  });

  it('treats two multi-coloured garments as the same colour', () => {
    // The sentinel check used to run before the equality check and reported 100.
    expect(colorDistance('#RAINBOW', '#RAINBOW')).toBe(0);
  });

  it('keeps multi-coloured apart from a specific colour', () => {
    expect(colorDistance('#RAINBOW', HEX.red)).toBeGreaterThan(50);
  });

  it('returns null for an unparseable colour instead of NaN', () => {
    expect(colorDistance('nonsense', HEX.red)).toBeNull();
    expect(colorDistance(HEX.red, '')).toBeNull();
  });

  it('matches reference Lab values for a known colour', () => {
    // #FF0000 is L 53.24 / a 80.09 / b 67.20, so its distance from black is
    // sqrt(53.24^2 + 80.09^2 + 67.20^2) — a check on the conversion itself.
    const expected = Math.sqrt(53.24 ** 2 + 80.09 ** 2 + 67.2 ** 2);
    expect(colorDistance('#FF0000', '#000000')!).toBeCloseTo(expected, 0);
  });
});

describe('colorSimilarity', () => {
  it('scores identical colours 1', () => {
    expect(colorSimilarity(HEX.red, HEX.red)).toBe(1);
  });

  it('decays monotonically and never bottoms out across the palette', () => {
    // `1 - dE/100` clamped 52 of 276 palette pairs to exactly 0, making very
    // different colours indistinguishable from each other.
    const scores: number[] = [];
    for (let i = 0; i < PALETTE.length; i++) {
      for (let j = i + 1; j < PALETTE.length; j++) {
        scores.push(colorSimilarity(PALETTE[i], PALETTE[j]));
      }
    }
    expect(scores.filter(s => s === 0)).toHaveLength(0);
    expect(Math.min(...scores)).toBeGreaterThan(0);
  });

  it('ranks a near match above a distant one', () => {
    const near = colorSimilarity(HEX.red, HEX.burgundy);
    const far = colorSimilarity(HEX.red, HEX.lightBlue);
    expect(near).toBeGreaterThan(far);
  });

  it('scores an unknown colour 0, so absence of data is not a match', () => {
    expect(colorSimilarity('nonsense', HEX.red)).toBe(0);
  });
});

describe('colorRelationship', () => {
  it('calls genuine contrasts contrasting', () => {
    // Every one of these scored -0.2 ("clashing") under the old ΔE bands.
    for (const [a, b] of [
      ['blue', 'orange'],
      ['red', 'green'],
      ['purple', 'gold'],
      ['navy', 'red'],
    ]) {
      expect(colorRelationship(HEX[a], HEX[b])).toBe('contrasting');
    }
  });

  it('calls genuine analogues analogous', () => {
    for (const [a, b] of [
      ['navy', 'blue'],
      ['gold', 'yellow'],
      ['red', 'burgundy'],
    ]) {
      expect(colorRelationship(HEX[a], HEX[b])).toBe('analogous');
    }
  });

  it('treats low-chroma colours as neutral, not just the four greys', () => {
    expect(colorRelationship(HEX.black, HEX.red)).toBe('neutral');
    expect(colorRelationship(HEX.silver, HEX.green)).toBe('neutral');
    // Beige and lavender are near-achromatic and behave as neutrals in practice.
    expect(colorRelationship(HEX.beige, HEX.brown)).toBe('neutral');
    expect(colorRelationship(HEX.lavender, HEX.orange)).toBe('neutral');
  });

  it('reports the same colour as same', () => {
    expect(colorRelationship(HEX.red, HEX.red)).toBe('same');
  });

  it('reports unknown for multi-coloured or unparseable input', () => {
    expect(colorRelationship('#RAINBOW', HEX.red)).toBe('unknown');
    expect(colorRelationship('nonsense', HEX.red)).toBe('unknown');
  });

  it('is symmetric', () => {
    for (const a of PALETTE.slice(0, 8)) {
      for (const b of PALETTE.slice(0, 8)) {
        expect(colorRelationship(a, b)).toBe(colorRelationship(b, a));
      }
    }
  });
});

describe('colorHarmonyScore', () => {
  it('never penalises a legitimate colour pair', () => {
    // The old model returned -0.2 for anything over ΔE 90, which pushed the
    // suggestion engine away from navy-and-red and blue-and-orange.
    for (let i = 0; i < PALETTE.length; i++) {
      for (let j = 0; j < PALETTE.length; j++) {
        expect(colorHarmonyScore(PALETTE[i], PALETTE[j])).toBeGreaterThanOrEqual(0);
      }
    }
  });

  it('rates a real contrast above a near-miss', () => {
    const contrast = colorHarmonyScore(HEX.navy, HEX.red);
    const nearMiss = colorHarmonyScore(HEX.green, HEX.teal);
    expect(contrast).toBeGreaterThan(nearMiss);
  });

  it('rates a contrast above wearing the same colour twice', () => {
    expect(colorHarmonyScore(HEX.blue, HEX.orange)).toBeGreaterThan(
      colorHarmonyScore(HEX.blue, HEX.blue)
    );
  });

  it('has no opinion about an unknown colour', () => {
    // Not a positive bonus, which is what a NaN distance used to produce.
    expect(colorHarmonyScore('nonsense', HEX.red)).toBe(0);
    expect(colorHarmonyScore('#RAINBOW', HEX.red)).toBe(0);
  });
});
