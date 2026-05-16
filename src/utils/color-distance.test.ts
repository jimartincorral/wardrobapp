import { describe, expect, it } from 'vitest';
import { colorDistance, colorHarmonyScore, colorSimilarity } from './color-distance';

describe('color utilities', () => {
  it('returns zero distance and full similarity for identical colors', () => {
    expect(colorDistance('#FF0000', '#FF0000')).toBe(0);
    expect(colorSimilarity('#FF0000', '#FF0000')).toBe(1);
  });

  it('handles rainbow placeholder color', () => {
    expect(colorDistance('#RAINBOW', '#FF0000')).toBe(100);
    expect(colorSimilarity('#RAINBOW', '#FF0000')).toBe(0);
    expect(colorHarmonyScore('#RAINBOW', '#FF0000')).toBe(0);
  });

  it('gives a neutral harmony boost for neutral colors', () => {
    expect(colorHarmonyScore('#000000', '#00AAFF')).toBe(0.5);
  });
});
