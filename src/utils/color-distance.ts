/**
 * Colour comparison for garments.
 *
 * Two different questions get asked of colours here, and they need different
 * answers:
 *
 *  - "are these the same colour?" — duplicate detection. Answered by CIE76 ΔE,
 *    a perceptual *magnitude*.
 *  - "do these go together?" — outfit harmony. Answered by hue angle, because
 *    whether two colours clash is a question about hue, not about how far apart
 *    they are overall.
 *
 * Conflating the two is what made harmony wrong: ΔE is dominated by lightness,
 * so navy-and-red (ΔE 127) and blue-and-orange (ΔE 139) were classified as
 * clashing while beige-on-brown (ΔE 72) was classified as a great match.
 */

/** Sentinel stored for garments the user marked as multi-coloured. */
export const MULTI_COLOR = '#RAINBOW';

/**
 * Distance reported between a multi-coloured garment and a specific colour.
 * Large enough to read as "not the same colour" without implying a measurement.
 */
const MULTI_COLOR_DISTANCE = 100;

/** Below this Lab chroma a colour reads as achromatic: it goes with anything. */
const NEUTRAL_CHROMA = 15;

/** ΔE below which two colours are the same colour for practical purposes. */
const SAME_COLOR_DELTA_E = 5;

/**
 * Scale for turning ΔE into a 0..1 similarity.
 *
 * `1 - ΔE/100` used to do this, but ΔE across this app's own palette reaches
 * 176, so 19% of colour pairs clamped to exactly 0 and became indistinguishable
 * from each other. An exponential decay is monotone over the whole range and
 * spends most of its resolution on the 0-40 band that actually matters.
 */
const SIMILARITY_SCALE = 25;

type Rgb = [number, number, number];
type Lab = [number, number, number];

/**
 * Parse `#RGB` or `#RRGGBB`, with or without the hash, in either case.
 *
 * Returns null rather than NaN components for anything else. The old version
 * returned `[255, 15, NaN]` for `#fff`, and that NaN propagated silently: every
 * comparison against it was false, so a malformed colour ended up scoring as a
 * mild *positive* harmony match. Restored backups and imported URLs are both
 * sources of colours this code did not write.
 */
export function parseHexColor(hex: string): Rgb | null {
  if (typeof hex !== 'string') return null;

  const clean = hex.trim().replace(/^#/, '');
  if (!/^[0-9a-f]{3}$|^[0-9a-f]{6}$/i.test(clean)) return null;

  const full = clean.length === 3
    ? clean.split('').map(c => c + c).join('')
    : clean;

  return [
    parseInt(full.slice(0, 2), 16),
    parseInt(full.slice(2, 4), 16),
    parseInt(full.slice(4, 6), 16),
  ];
}

/** Convert sRGB to CIE Lab (D65 white point). */
function rgbToLab(r: number, g: number, b: number): Lab {
  let rn = r / 255;
  let gn = g / 255;
  let bn = b / 255;

  // sRGB to linear
  rn = rn > 0.04045 ? Math.pow((rn + 0.055) / 1.055, 2.4) : rn / 12.92;
  gn = gn > 0.04045 ? Math.pow((gn + 0.055) / 1.055, 2.4) : gn / 12.92;
  bn = bn > 0.04045 ? Math.pow((bn + 0.055) / 1.055, 2.4) : bn / 12.92;

  // Linear RGB to XYZ, normalised against D65
  let x = (rn * 0.4124564 + gn * 0.3575761 + bn * 0.1804375) / 0.95047;
  let y = (rn * 0.2126729 + gn * 0.7151522 + bn * 0.0721750) / 1.00000;
  let z = (rn * 0.0193339 + gn * 0.1191920 + bn * 0.9503041) / 1.08883;

  const epsilon = 0.008856;
  const kappa = 903.3;
  x = x > epsilon ? Math.cbrt(x) : (kappa * x + 16) / 116;
  y = y > epsilon ? Math.cbrt(y) : (kappa * y + 16) / 116;
  z = z > epsilon ? Math.cbrt(z) : (kappa * z + 16) / 116;

  return [116 * y - 16, 500 * (x - y), 200 * (y - z)];
}

function labOf(hex: string): Lab | null {
  const rgb = parseHexColor(hex);
  return rgb ? rgbToLab(rgb[0], rgb[1], rgb[2]) : null;
}

const isMultiColor = (hex: string) => hex.trim().toUpperCase() === MULTI_COLOR;

const sameHexString = (hex1: string, hex2: string) =>
  hex1.trim().toUpperCase() === hex2.trim().toUpperCase();

/**
 * CIE76 ΔE between two hex colours: 0 for identical, larger for more different.
 *
 * Returns null when either colour cannot be parsed, so callers decide what an
 * unknown colour means rather than inheriting a NaN.
 */
export function colorDistance(hex1: string, hex2: string): number | null {
  // Equality first: two multi-coloured garments are the same colour as each
  // other, and the sentinel check below used to report them as 100 apart.
  if (sameHexString(hex1, hex2)) return 0;
  if (isMultiColor(hex1) || isMultiColor(hex2)) return MULTI_COLOR_DISTANCE;

  const lab1 = labOf(hex1);
  const lab2 = labOf(hex2);
  if (!lab1 || !lab2) return null;

  return Math.sqrt(
    Math.pow(lab2[0] - lab1[0], 2) +
    Math.pow(lab2[1] - lab1[1], 2) +
    Math.pow(lab2[2] - lab1[2], 2)
  );
}

/**
 * How alike two colours are, from 1 (identical) down towards 0.
 *
 * An unknown colour scores 0: absence of information must not read as a match.
 */
export function colorSimilarity(hex1: string, hex2: string): number {
  const distance = colorDistance(hex1, hex2);
  if (distance === null) return 0;
  return Math.exp(-distance / SIMILARITY_SCALE);
}

/** How two colours relate, in the terms that decide whether they go together. */
export type ColorRelationship =
  | 'unknown'
  | 'same'
  | 'neutral'
  | 'analogous'
  | 'near-miss'
  | 'contrasting';

/** Smallest angle between two hues, in degrees (0..180). */
function hueGap(a: number, b: number): number {
  const gap = Math.abs(a - b) % 360;
  return gap > 180 ? 360 - gap : gap;
}

/**
 * Classify a colour pair.
 *
 * Hue drives this, with chroma deciding what counts as a neutral. Lab hue angles
 * are not spaced like an artist's colour wheel, so the bands below are set
 * against this app's actual palette rather than to textbook angles: true
 * contrasts (navy/red, blue/orange, red/green) land above 90 degrees apart,
 * and genuine analogues (navy/blue, gold/yellow, red/burgundy) below 45.
 */
export function colorRelationship(hex1: string, hex2: string): ColorRelationship {
  if (isMultiColor(hex1) || isMultiColor(hex2)) return 'unknown';

  const lab1 = labOf(hex1);
  const lab2 = labOf(hex2);
  if (!lab1 || !lab2) return 'unknown';

  const deltaE = Math.sqrt(
    Math.pow(lab2[0] - lab1[0], 2) +
    Math.pow(lab2[1] - lab1[1], 2) +
    Math.pow(lab2[2] - lab1[2], 2)
  );
  if (deltaE < SAME_COLOR_DELTA_E) return 'same';

  const chroma1 = Math.hypot(lab1[1], lab1[2]);
  const chroma2 = Math.hypot(lab2[1], lab2[2]);
  // A greyed-out colour has no hue worth comparing, so it sits with anything.
  // Derived from chroma rather than a hardcoded list of four hexes, which meant
  // beige and lavender were treated as loud colours.
  if (chroma1 < NEUTRAL_CHROMA || chroma2 < NEUTRAL_CHROMA) return 'neutral';

  const hue1 = (Math.atan2(lab1[2], lab1[1]) * 180) / Math.PI;
  const hue2 = (Math.atan2(lab2[2], lab2[1]) * 180) / Math.PI;
  const gap = hueGap(hue1, hue2);

  if (gap <= 45) return 'analogous';
  if (gap <= 90) return 'near-miss';
  return 'contrasting';
}

/**
 * How well two colours go together, from 0 (no opinion) to 1.
 *
 * Nothing scores negative any more. The old version penalised anything more
 * than ΔE 90 apart, which meant it actively pushed the suggestion engine away
 * from navy-and-red and blue-and-orange.
 */
export function colorHarmonyScore(hex1: string, hex2: string): number {
  switch (colorRelationship(hex1, hex2)) {
    case 'same':
      return 0.3; // Works, but reads as unconsidered.
    case 'neutral':
      return 0.5;
    case 'analogous':
      return 0.6;
    case 'near-miss':
      return 0.2; // Close enough to look accidental rather than chosen.
    case 'contrasting':
      return 0.7;
    case 'unknown':
      return 0; // No information is not the same as a good match.
  }
}
