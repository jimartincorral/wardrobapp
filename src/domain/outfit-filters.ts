/**
 * The season and occasion chips on the outfits screen.
 *
 * Small, but worth having in one place: the screen decided whether a chip was
 * active twice per chip -- once for its background and once for its text -- and
 * the two expressions had to agree. They are the sort of thing that stays in
 * step right up until one of them is edited.
 *
 * The two rows behave differently on purpose. Seasons are a set, because a
 * garment for spring is often a garment for fall; occasion is one choice,
 * because an outfit is for one thing at a time.
 */
import { OCCASION_OPTIONS, SEASON_OPTIONS } from '../constants/style-filters';
import type { OccasionOption, SeasonOption } from '../constants/style-filters';

export type OutfitFilters = {
  seasons: SeasonOption[];
  occasion?: OccasionOption;
};

/** One chip: what it stands for, and whether it is on. */
export type FilterChip<T> = {
  /** Null is the "any" chip, which stands for no choice rather than a value. */
  value: T | null;
  active: boolean;
};

export const NO_FILTERS: OutfitFilters = { seasons: [] };

/**
 * The season row.
 *
 * "Any" is active precisely when nothing else is, so the row always has exactly
 * one reading: either the user has chosen seasons or they have not.
 */
export function seasonChips(filters: OutfitFilters): FilterChip<SeasonOption>[] {
  return [
    { value: null, active: filters.seasons.length === 0 },
    ...SEASON_OPTIONS.map(season => ({
      value: season,
      active: filters.seasons.includes(season),
    })),
  ];
}

export function occasionChips(filters: OutfitFilters): FilterChip<OccasionOption>[] {
  return [
    { value: null, active: filters.occasion === undefined },
    ...OCCASION_OPTIONS.map(occasion => ({
      value: occasion,
      active: filters.occasion === occasion,
    })),
  ];
}

/**
 * Tapping a season chip.
 *
 * "Any" clears the set rather than being a value in it. Tapping a season that is
 * already on takes it off, so the row can be emptied without reaching for "any"
 * -- and emptying it that way lands in the same state, which is why "any" is
 * derived rather than stored.
 */
export function withSeasonToggled(
  filters: OutfitFilters,
  season: SeasonOption | null
): OutfitFilters {
  if (season === null) return { ...filters, seasons: [] };

  const seasons = filters.seasons.includes(season)
    ? filters.seasons.filter(s => s !== season)
    : [...filters.seasons, season];

  // Kept in the app's own order rather than the order they were tapped, so the
  // same choice always reads the same -- and so it matches what the chips show.
  return { ...filters, seasons: SEASON_OPTIONS.filter(s => seasons.includes(s)) };
}

/**
 * Tapping an occasion chip.
 *
 * Tapping the active one clears it, which is the same as tapping "any" -- so
 * there is no state the user can reach and not get back out of.
 */
export function withOccasionSelected(
  filters: OutfitFilters,
  occasion: OccasionOption | null
): OutfitFilters {
  if (occasion === null || filters.occasion === occasion) {
    return { ...filters, occasion: undefined };
  }

  return { ...filters, occasion };
}

/** True when nothing is filtered, which is what the empty-state copy turns on. */
export function isUnfiltered(filters: OutfitFilters): boolean {
  return filters.seasons.length === 0 && filters.occasion === undefined;
}
