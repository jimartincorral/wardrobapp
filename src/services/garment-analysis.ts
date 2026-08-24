import * as ImageManipulator from 'expo-image-manipulator';
import jpeg from 'jpeg-js';
import { SEASON_OPTIONS } from '../constants/style-filters';
import type { SeasonOption } from '../constants/style-filters';
import { dominantColorOf } from '../utils/dominant-color';

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

/**
 * The palette colour a JPEG averages out to.
 *
 * Decoding is this module's half of the job; the arithmetic is in
 * `utils/dominant-color`, where it can be run without React Native -- which is
 * what lets the Kotlin port be held to it.
 */
export function estimateDominantColor(data: Uint8Array): string {
  return dominantColorOf(jpeg.decode(data, { useTArray: true }).data);
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
