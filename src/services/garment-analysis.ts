import * as ImageManipulator from 'expo-image-manipulator';
import jpeg from 'jpeg-js';
import { GARMENT_COLORS } from '../constants/colors';
import { SEASON_OPTIONS } from '../constants/style-filters';
import type { SeasonOption } from '../constants/style-filters';
import { colorDistance } from '../utils/color-distance';

/**
 * Garment analysis.
 *
 * This used to run MobileNet (via TensorFlow.js) to guess a garment's type from
 * its photo. In practice the guesses were not accurate enough to be useful --
 * the smallest MobileNet variant classifying against ImageNet's vocabulary
 * cannot reliably tell a blouse from a shirt -- so the model was removed along
 * with the whole TensorFlow stack.
 *
 * What is left is what never needed a model in the first place, and what the
 * feature was actually delivering:
 *
 *   - the dominant colour, read straight from the image's pixels
 *   - the seasons a garment type implies, from a lookup table
 *
 * Dropping the model also made this cross-platform: it no longer depends on
 * native GL, and there is no ~2MB model download on first use.
 */

type AnalysisProgressCallback = (percent: number) => void;

/** Seasons implied by a garment type. Occasion is derived separately, from the
 * same subcategory, in utils/garment-occasions. */
const SEASON_RULES: Record<string, SeasonOption[]> = {
  Shorts: ['summer'],
  'Tank Top': ['summer'],
  Sandals: ['summer'],
  Sundress: ['summer'],
  Coat: ['winter'],
  Parka: ['winter'],
  Thermal: ['winter'],
  Sweater: ['fall', 'winter'],
  Hoodie: ['fall', 'winter'],
  Boots: ['fall', 'winter'],
  Windbreaker: ['spring', 'fall'],
  Cardigan: ['spring', 'fall'],
  Robe: ['fall', 'winter'],
};

function base64ToBytes(base64: string): Uint8Array {
  if (typeof atob === 'function') {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  if (typeof Buffer !== 'undefined') {
    return Uint8Array.from(Buffer.from(base64, 'base64'));
  }

  throw new Error('No base64 decoder available on this platform');
}

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b].map(n => Math.max(0, Math.min(255, n)).toString(16).padStart(2, '0')).join('')}`.toUpperCase();
}

function nearestGarmentColor(hex: string): string {
  const palette = GARMENT_COLORS.filter(c => c.hex !== '#RAINBOW');
  let nearest = palette[0]?.hex ?? '#000000';
  let nearestDistance = Number.POSITIVE_INFINITY;
  for (const color of palette) {
    const dist = colorDistance(hex, color.hex);
    if (dist < nearestDistance) {
      nearestDistance = dist;
      nearest = color.hex;
    }
  }
  return nearest;
}

export function estimateDominantColor(data: Uint8Array): string {
  const decoded = jpeg.decode(data, { useTArray: true });
  const pixels = decoded.data;
  let rTotal = 0;
  let gTotal = 0;
  let bTotal = 0;
  let count = 0;

  for (let i = 0; i < pixels.length; i += 16) {
    const alpha = pixels[i + 3];
    if (alpha < 16) continue;
    rTotal += pixels[i];
    gTotal += pixels[i + 1];
    bTotal += pixels[i + 2];
    count += 1;
  }

  if (count === 0) return '#000000';
  const avgHex = rgbToHex(Math.round(rTotal / count), Math.round(gTotal / count), Math.round(bTotal / count));
  return nearestGarmentColor(avgHex);
}

/**
 * Seasons implied by the chosen garment types. Returns [] when nothing is
 * implied, so callers can tell "no opinion" from "all-season".
 */
export function getSeasonsForSubcategories(subcategories: string[]): SeasonOption[] {
  const seasons = new Set<SeasonOption>();

  for (const subcategory of subcategories) {
    for (const season of SEASON_RULES[subcategory] ?? []) seasons.add(season);
  }

  return SEASON_OPTIONS.filter(option => seasons.has(option));
}

/**
 * Pure pixel maths and a lookup table -- available everywhere, unlike the
 * Android-only TensorFlow path this replaced.
 */
export function isGarmentAnalysisAvailable(): boolean {
  return true;
}

/**
 * Read the dominant colour out of a garment photo, snapped to the nearest
 * colour in the app's palette. Returns null if the image can't be read.
 */
export async function detectDominantColor(
  imageUri: string,
  onProgress?: AnalysisProgressCallback
): Promise<string | null> {
  const updateProgress = (percent: number) => onProgress?.(Math.max(0, Math.min(100, Math.round(percent))));

  try {
    updateProgress(10);
    // Downscale hard first: averaging a 64px thumbnail is both faster and less
    // sensitive to detail than averaging the full image.
    const manipulated = await ImageManipulator.manipulateAsync(
      imageUri,
      [{ resize: { width: 64 } }],
      { format: ImageManipulator.SaveFormat.JPEG, compress: 0.8, base64: true }
    );
    updateProgress(60);

    if (!manipulated.base64) return null;
    const color = estimateDominantColor(base64ToBytes(manipulated.base64));
    updateProgress(100);
    return color;
  } catch (error) {
    console.warn('Colour detection failed:', error);
    return null;
  }
}
