import type { Garment } from '../types';

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

export function normalizeGarmentRow(row: any): Garment {
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

  return {
    ...row,
    image_uri,
    image_uri_nobg,
    image_uris: image_uris.length > 0 ? image_uris : [image_uri].filter(Boolean),
    image_uris_nobg,
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
