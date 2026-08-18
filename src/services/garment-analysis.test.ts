import { describe, expect, it, vi } from 'vitest';

vi.mock('expo-image-manipulator', () => ({
  manipulateAsync: vi.fn(),
  SaveFormat: { JPEG: 'jpeg' },
}));

import { estimateDominantColor, getSeasonsForSubcategories, isGarmentAnalysisAvailable } from './garment-analysis';

/** Build a solid-colour JPEG so the estimator has something real to decode. */
async function solidJpeg(r: number, g: number, b: number): Promise<Uint8Array> {
  const jpeg = (await import('jpeg-js')).default;
  const width = 8;
  const height = 8;
  const data = new Uint8Array(width * height * 4);
  for (let i = 0; i < data.length; i += 4) {
    data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = 255;
  }
  return jpeg.encode({ data, width, height }, 90).data;
}

describe('estimateDominantColor', () => {
  it('snaps a solid image to the nearest palette colour', async () => {
    expect(estimateDominantColor(await solidJpeg(250, 250, 250))).toBe('#FFFFFF');
    expect(estimateDominantColor(await solidJpeg(5, 5, 5))).toBe('#000000');
  });

  it('never returns a colour outside the app palette', async () => {
    // 0,102,204-ish blue: must come back as a palette entry, not the raw average.
    const result = estimateDominantColor(await solidJpeg(0, 100, 200));
    expect(result).toMatch(/^#[0-9A-F]{6}$/);
    expect(result).not.toBe('#0064C8');
  });
});

describe('getSeasonsForSubcategories', () => {
  it('derives seasons from garment types', () => {
    expect(getSeasonsForSubcategories(['Shorts'])).toEqual(['summer']);
    expect(getSeasonsForSubcategories(['Sweater'])).toEqual(['fall', 'winter']);
  });

  it('unions across types in canonical order', () => {
    expect(getSeasonsForSubcategories(['Parka', 'Shorts'])).toEqual(['summer', 'winter']);
  });

  it('returns nothing when no type implies a season, so callers can tell "no opinion" apart', () => {
    expect(getSeasonsForSubcategories(['T-Shirt'])).toEqual([]);
    expect(getSeasonsForSubcategories([])).toEqual([]);
  });
});

describe('isGarmentAnalysisAvailable', () => {
  it('is available on every platform now the native model is gone', () => {
    expect(isGarmentAnalysisAvailable()).toBe(true);
  });
});
