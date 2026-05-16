/**
 * Convert hex color to RGB.
 */
function hexToRgb(hex: string): [number, number, number] {
  const clean = hex.replace('#', '');
  return [
    parseInt(clean.substring(0, 2), 16),
    parseInt(clean.substring(2, 4), 16),
    parseInt(clean.substring(4, 6), 16),
  ];
}

/**
 * Convert RGB to CIE LAB color space.
 */
function rgbToLab(r: number, g: number, b: number): [number, number, number] {
  // Normalize to 0-1
  let rn = r / 255;
  let gn = g / 255;
  let bn = b / 255;

  // sRGB to linear
  rn = rn > 0.04045 ? Math.pow((rn + 0.055) / 1.055, 2.4) : rn / 12.92;
  gn = gn > 0.04045 ? Math.pow((gn + 0.055) / 1.055, 2.4) : gn / 12.92;
  bn = bn > 0.04045 ? Math.pow((bn + 0.055) / 1.055, 2.4) : bn / 12.92;

  // Linear RGB to XYZ
  let x = (rn * 0.4124564 + gn * 0.3575761 + bn * 0.1804375) / 0.95047;
  let y = (rn * 0.2126729 + gn * 0.7151522 + bn * 0.0721750) / 1.00000;
  let z = (rn * 0.0193339 + gn * 0.1191920 + bn * 0.9503041) / 1.08883;

  // XYZ to LAB
  const epsilon = 0.008856;
  const kappa = 903.3;
  x = x > epsilon ? Math.cbrt(x) : (kappa * x + 16) / 116;
  y = y > epsilon ? Math.cbrt(y) : (kappa * y + 16) / 116;
  z = z > epsilon ? Math.cbrt(z) : (kappa * z + 16) / 116;

  return [116 * y - 16, 500 * (x - y), 200 * (y - z)];
}

/**
 * Compute CIE76 Delta-E color distance between two hex colors.
 * Returns 0 for identical colors, higher values for more different colors.
 * Typical ranges: 0-2.3 (not perceptible), 2.3-10 (noticeable), >10 (very different).
 */
export function colorDistance(hex1: string, hex2: string): number {
  if (hex1 === '#RAINBOW' || hex2 === '#RAINBOW') return 100;

  const [r1, g1, b1] = hexToRgb(hex1);
  const [r2, g2, b2] = hexToRgb(hex2);
  const [l1, a1, b1Lab] = rgbToLab(r1, g1, b1);
  const [l2, a2, b2Lab] = rgbToLab(r2, g2, b2);

  return Math.sqrt(
    Math.pow(l2 - l1, 2) + Math.pow(a2 - a1, 2) + Math.pow(b2Lab - b1Lab, 2)
  );
}

/**
 * Returns a similarity score between 0 and 1 based on color distance.
 * 1 = identical colors, 0 = very different colors.
 */
export function colorSimilarity(hex1: string, hex2: string): number {
  const distance = colorDistance(hex1, hex2);
  // Max perceptible distance is ~100 for very different colors
  return Math.max(0, 1 - distance / 100);
}

/**
 * Check if two colors are complementary (opposite on color wheel).
 */
export function areComplementary(hex1: string, hex2: string): boolean {
  const dist = colorDistance(hex1, hex2);
  return dist > 60 && dist < 90;
}

/**
 * Check if two colors are analogous (close on color wheel).
 */
export function areAnalogous(hex1: string, hex2: string): boolean {
  const dist = colorDistance(hex1, hex2);
  return dist > 10 && dist < 40;
}

/**
 * Returns a color harmony score between -1 and 1.
 * Positive = harmonious, negative = clashing.
 */
export function colorHarmonyScore(hex1: string, hex2: string): number {
  if (hex1 === '#RAINBOW' || hex2 === '#RAINBOW') return 0;

  const dist = colorDistance(hex1, hex2);

  // Neutrals (black, white, gray) go with everything
  const neutrals = ['#000000', '#FFFFFF', '#808080', '#C0C0C0'];
  if (neutrals.includes(hex1.toUpperCase()) || neutrals.includes(hex2.toUpperCase())) {
    return 0.5;
  }

  // Identical = fine but boring
  if (dist < 5) return 0.3;
  // Analogous = good
  if (dist < 40) return 0.6;
  // Complementary = great
  if (dist > 60 && dist < 90) return 0.8;
  // Too different = may clash
  if (dist > 90) return -0.2;
  // Medium distance = neutral
  return 0.2;
}
