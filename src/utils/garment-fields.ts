import type { Garment } from '../types';
import { resolveImageRef } from './image-paths';

function parseStringArray(value: unknown, preserveEmpty = false): string[] {
  if (Array.isArray(value)) {
    return value.map(item => String(item).trim()).filter(item => preserveEmpty || Boolean(item));
  }

  if (typeof value !== 'string' || !value.trim()) return [];

  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) {
      return parsed.map(item => String(item).trim()).filter(item => preserveEmpty || Boolean(item));
    }
  } catch {
    return value.split(',').map(item => item.trim()).filter(item => preserveEmpty || Boolean(item));
  }

  return [];
}

function uniqueCaseInsensitive(values: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];

  for (const value of values) {
    const trimmed = value.trim();
    const normalized = trimmed.toLowerCase();
    if (!trimmed || seen.has(normalized)) continue;
    seen.add(normalized);
    result.push(trimmed);
  }

  return result;
}

/**
 * Coerce a scalar text column, preserving absence.
 *
 * These columns used to be spread straight from the row, so a column holding a
 * number reached callers as a number in a field the type declares as
 * `string | null`. `size` is the one that bites: duplicate detection calls
 * `size?.trim()`, and `?.` guards null but not a missing method, so a numeric
 * size threw `TypeError: size?.trim is not a function`. Absence stays absence --
 * only non-null non-strings are coerced.
 */
function asText(value: unknown): string | null {
  return value === null || value === undefined ? null : String(value);
}

/**
 * Turn a database row into a `Garment`.
 *
 * Pure: `imageDirectory` is supplied by the caller rather than looked up here,
 * so this module — the one every garment read passes through — has no
 * dependency on the filesystem, the database, or the platform.
 */
export function normalizeGarmentRow(row: any, imageDirectory: string): Garment {
  const resolve = (ref: string) => resolveImageRef(ref, imageDirectory);
  const tags = parseStringArray(row.tags).map(tag => tag.toLowerCase());
  const subcategories = uniqueCaseInsensitive([
    ...parseStringArray(row.subcategories),
    typeof row.subcategory === 'string' ? row.subcategory : '',
  ]);
  const image_uris = uniqueCaseInsensitive([
    ...parseStringArray(row.image_uris),
    typeof row.image_uri === 'string' ? row.image_uri : '',
  ]);
  const image_uris_nobg = parseStringArray(row.image_uris_nobg, true);
  if (image_uris_nobg.length === 0 && typeof row.image_uri_nobg === 'string') {
    image_uris_nobg.push(row.image_uri_nobg);
  }
  const color_palette = uniqueCaseInsensitive([
    ...parseStringArray(row.color_palette),
    typeof row.color_primary === 'string' ? row.color_primary : '',
    typeof row.color_secondary === 'string' ? row.color_secondary : '',
  ]);

  const image_uri = image_uris[0] ?? String(row.image_uri ?? '');
  const image_uri_nobg = image_uris_nobg[0] ?? (typeof row.image_uri_nobg === 'string' ? row.image_uri_nobg : null);
  const color_primary = color_palette[0] ?? String(row.color_primary ?? '#000000');
  const color_secondary = color_palette[1] ?? (typeof row.color_secondary === 'string' ? row.color_secondary : null);

  // Photo references are stored as bare filenames (and, in rows from older
  // builds, as absolute paths that may no longer exist). Re-attach the current
  // directory here so every consumer gets something loadable.
  const resolvedUris = (image_uris.length > 0 ? image_uris : [image_uri].filter(Boolean)).map(resolve);
  const resolvedNoBgUris = image_uris_nobg.map(resolve);

  return {
    ...row,
    // Scalar text columns are normalized rather than inherited from the spread:
    // the row is `any`, so nothing else stops a number reaching a string field.
    id: asText(row.id) ?? '',
    category: asText(row.category) ?? '',
    brand: asText(row.brand),
    size: asText(row.size),
    purchase_date: asText(row.purchase_date),
    unavailable_date: asText(row.unavailable_date),
    image_uri: resolve(image_uri),
    image_uri_nobg: image_uri_nobg ? resolve(image_uri_nobg) : image_uri_nobg,
    image_uris: resolvedUris,
    image_uris_nobg: resolvedNoBgUris,
    subcategory: subcategories[0] ?? (typeof row.subcategory === 'string' ? row.subcategory : null),
    subcategories,
    tags,
    color_primary,
    color_secondary,
    color_palette: color_palette.length > 0 ? color_palette : [color_primary].filter(Boolean),
    is_available: Boolean(row.is_available),
  };
}

export function getGarmentImageUris(garment: Garment): string[] {
  return garment.image_uris.length > 0 ? garment.image_uris : [garment.image_uri].filter(Boolean);
}

export function getGarmentNoBgImageUris(garment: Garment): string[] {
  return garment.image_uris_nobg.length > 0 ? garment.image_uris_nobg : [garment.image_uri_nobg ?? ''];
}

export function getGarmentDisplayImage(garment: Garment): string {
  return getGarmentNoBgImageUris(garment).find(Boolean) ?? getGarmentImageUris(garment)[0] ?? garment.image_uri;
}

export function getGarmentColorPalette(garment: Garment): string[] {
  return garment.color_palette.length > 0
    ? garment.color_palette
    : [garment.color_primary, garment.color_secondary ?? ''].filter(Boolean);
}

export function getGarmentPrimaryColor(garment: Garment): string {
  return getGarmentColorPalette(garment)[0] ?? garment.color_primary;
}
