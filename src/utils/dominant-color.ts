/**
 * The colour a garment photo suggests, from its pixels.
 *
 * Extracted from `services/garment-analysis.ts`, which is where this used to live
 * and where it could not be tested against the Kotlin port: that module imports
 * `expo-image-manipulator`, so anything reading it pulls in React Native, and
 * `scripts/dump-domain-parity.ts` runs under plain node. The arithmetic here has
 * no platform in it -- pixels in, a palette entry out -- so it belongs alongside
 * the other pure helpers, the same move `domain/garment-form.ts` was for the
 * form's rules.
 *
 * The service still owns the part that is genuinely platform-bound: getting from
 * a photo on disk to a small array of pixels.
 */
import { GARMENT_COLORS } from '../constants/colors';
import { MULTI_COLOR, colorDistance } from './color-distance';

/**
 * How much of the image to look at.
 *
 * Every fourth pixel, which for an RGBA array is a stride of 16 bytes. Averaging
 * a quarter of a thumbnail is indistinguishable from averaging all of it, and this
 * runs while someone is waiting for a form to fill itself in.
 */
const SAMPLE_STRIDE = 16;

/**
 * The alpha below which a pixel does not count.
 *
 * Not 0: a cut-out's edge is antialiased, so the pixels just outside the garment
 * carry a little of its colour and a lot of nothing. Counting those pulls every
 * average towards black.
 *
 * Dead code on this side, as it happens -- the service hands over a decoded JPEG,
 * and JPEG has no alpha channel, so every pixel arrives fully opaque. It matters
 * in the Kotlin port, which is handed PNG cut-outs as well.
 */
const MINIMUM_ALPHA = 16;

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b]
    .map(n => Math.max(0, Math.min(255, n)).toString(16).padStart(2, '0'))
    .join('')}`.toUpperCase();
}

/**
 * The average colour of the pixels worth counting, as a hex string.
 *
 * Black when nothing was worth counting, which is what a fully transparent image
 * comes to. Returning a colour rather than null on purpose: the caller's next move
 * is to snap it to the palette either way, and black is the honest answer for an
 * image with nothing in it.
 */
export function averageOpaqueColor(pixels: Uint8Array): string {
  let rTotal = 0;
  let gTotal = 0;
  let bTotal = 0;
  let count = 0;

  for (let i = 0; i < pixels.length; i += SAMPLE_STRIDE) {
    if (pixels[i + 3] < MINIMUM_ALPHA) continue;
    rTotal += pixels[i];
    gTotal += pixels[i + 1];
    bTotal += pixels[i + 2];
    count += 1;
  }

  if (count === 0) return '#000000';

  return rgbToHex(
    Math.round(rTotal / count),
    Math.round(gTotal / count),
    Math.round(bTotal / count)
  );
}

/**
 * The palette entry closest to a colour.
 *
 * "Multi" is excluded because it is not a colour -- it stands for a garment with
 * several, and nothing should be snapped to it by accident.
 */
export function nearestGarmentColor(hex: string): string {
  const palette = GARMENT_COLORS.filter(c => c.hex !== MULTI_COLOR);
  let nearest = palette[0]?.hex ?? '#000000';
  let nearestDistance = Number.POSITIVE_INFINITY;

  for (const color of palette) {
    const dist = colorDistance(hex, color.hex);
    // Skip unparseable comparisons explicitly: `null < Infinity` is true in JS,
    // so a bare comparison would pick an unknown colour as the nearest one.
    if (dist !== null && dist < nearestDistance) {
      nearestDistance = dist;
      nearest = color.hex;
    }
  }

  return nearest;
}

/** The palette colour a photo's pixels average out to. */
export function dominantColorOf(pixels: Uint8Array): string {
  return nearestGarmentColor(averageOpaqueColor(pixels));
}
