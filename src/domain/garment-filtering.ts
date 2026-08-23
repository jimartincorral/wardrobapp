/**
 * Narrowing and ordering a wardrobe.
 *
 * Pure domain logic, extracted from the list hook so it can be tested without
 * React — and so the native port has one description of the behaviour to follow
 * rather than a re-reading of a component.
 *
 * The database applies the filters it can express (category, availability, a
 * text search across several columns). These are the rest: the ones that need a
 * parsed garment, because they look inside JSON columns or at a value derived
 * from the garment's type.
 */
import type { OccasionOption, SeasonOption } from '../constants/style-filters';
import { getGarmentOccasions } from '../utils/garment-occasions';
import { getGarmentColorPalette } from '../utils/garment-fields';
import type { Garment } from '../types';

export type GarmentSortOption = 'newest' | 'oldest';

export interface GarmentFilter {
  subcategory?: string;
  season?: SeasonOption;
  occasion?: OccasionOption;
  brand?: string;
  size?: string;
  color?: string;
}

/** Case-insensitive "contains", with a blank needle matching everything. */
function contains(haystack: string | null | undefined, needle: string): boolean {
  return (haystack ?? '').toLowerCase().includes(needle.toLowerCase().trim());
}

export function filterGarments(garments: Garment[], filter: GarmentFilter = {}): Garment[] {
  return garments.filter(garment => {
    if (filter.subcategory) {
      const subs = garment.subcategories.length > 0
        ? garment.subcategories
        : (garment.subcategory ? [garment.subcategory] : []);
      if (!subs.includes(filter.subcategory)) return false;
    }

    if (filter.season) {
      const tags = garment.tags.map(tag => tag.toLowerCase());
      if (!tags.includes(filter.season)) return false;
    }

    // Occasion is derived from the garment's type, not stored as a tag.
    if (filter.occasion && !getGarmentOccasions(garment).includes(filter.occasion)) return false;

    if (filter.brand && !contains(garment.brand, filter.brand)) return false;
    if (filter.size && !contains(garment.size, filter.size)) return false;

    if (filter.color && !getGarmentColorPalette(garment).includes(filter.color)) return false;

    return true;
  });
}

/**
 * Compare two timestamps, tolerating their absence.
 *
 * `created_at` is declared non-null but really can be null: an install upgraded
 * through the ALTER path gets the column without NOT NULL, since SQLite cannot
 * add a NOT NULL column without a default. Dereferencing it threw
 * `Cannot read properties of null` for any wardrobe big enough for the null to
 * land on the right-hand side of a comparison — and the list hook caught that,
 * so the screen showed an *empty wardrobe* instead of an error.
 *
 * An absent timestamp sorts as the earliest possible, so such a garment appears
 * last under 'newest' and first under 'oldest' rather than disappearing.
 */
function compareCreatedAt(a: Garment, b: Garment): number {
  return (a.created_at ?? '').localeCompare(b.created_at ?? '');
}

export function sortGarments(
  garments: Garment[],
  sort: GarmentSortOption = 'newest'
): Garment[] {
  const sorted = [...garments];
  return sort === 'oldest'
    ? sorted.sort(compareCreatedAt)
    : sorted.sort((a, b) => compareCreatedAt(b, a));
}
