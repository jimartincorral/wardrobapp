import { paletteColorFor } from './garment-detail';

/**
 * What the statistics screen shows.
 *
 * Counts in, bars out. Extracted because the arithmetic was inline in the screen
 * in two places and untested in both: every bar's width is a division, and a bar
 * of the wrong length is wrong in a way nobody notices.
 *
 * Labels are deliberately *not* here. The screen resolves every key through
 * `t(...)`, so a module that owned the text would need the translator — and the
 * Kotlin port has no translations yet. Keys, counts, fractions and swatches port;
 * words do not.
 */

/** One (key, count) pair, as the distribution queries return them. */
export interface Distribution {
  key: string;
  count: number;
}

/** One bar: what it counts, and how much of the track to fill. */
export interface StatBar {
  key: string;
  count: number;
  /** 0 to 1. Never negative, never over 1, never NaN. */
  fraction: number;
}

/** A colour's bar, with the swatch to draw beside it. */
export interface ColorBar extends StatBar {
  /**
   * A hex to fill with, or `multi` for the many-coloured swatch.
   *
   * A colour that is not in the palette keeps its stored value: it is a hex
   * already, just not one with a name.
   */
  swatch: string;
}

export type BrandSort = 'count' | 'alpha';

/** The key the distributions use for a garment with no subcategory recorded. */
export const NO_SUBCATEGORY = '__none__';

/** The swatch value meaning "many colours" rather than one. */
export const MULTI_SWATCH = 'multi';

export interface StatisticsView {
  total: number;
  /** True when there is nothing to measure, so the screen can say so instead. */
  isEmpty: boolean;

  /** How many distinct values each distribution found. */
  distinctCategories: number;
  distinctColors: number;
  distinctBrands: number;

  categories: StatBar[];
  colors: ColorBar[];
  brands: StatBar[];

  /**
   * Subcategory bars per category, keyed by category.
   *
   * Each group is scaled against its *own* largest bar rather than the category
   * chart's, so opening a small category still shows a readable spread instead
   * of four slivers.
   */
  subcategories: Record<string, StatBar[]>;
}

export function statisticsView(input: {
  total: number;
  categories: Distribution[];
  colors: Distribution[];
  brands: Distribution[];
  subcategories: Record<string, Distribution[]>;
  brandSort?: BrandSort;
}): StatisticsView {
  const brands = bars(input.brands);

  return {
    total: input.total,
    isEmpty: input.total <= 0,

    distinctCategories: input.categories.length,
    distinctColors: input.colors.length,
    distinctBrands: input.brands.length,

    categories: bars(input.categories),
    colors: bars(input.colors).map(bar => ({ ...bar, swatch: swatchFor(bar.key) })),
    brands: input.brandSort === 'alpha' ? alphabetically(brands) : brands,

    subcategories: Object.fromEntries(
      Object.entries(input.subcategories).map(([category, subs]) => [
        category,
        // Prefixed, because the same subcategory name appears under more than one
        // category and a list keyed on the bare name would collapse them.
        bars(subs).map(bar => ({ ...bar, key: `${category}:${bar.key}` })),
      ])
    ),
  };
}

/** Scale a distribution against its own largest count. */
function bars(distribution: Distribution[]): StatBar[] {
  const max = Math.max(...distribution.map(d => d.count), 0);

  return distribution.map(d => ({
    key: d.key,
    count: d.count,
    fraction: share(d.count, max),
  }));
}

/**
 * A count as a portion of the largest.
 *
 * Counts come from `COUNT(*)` so they should be whole and positive, but this is
 * used as a width: a negative would draw backwards and a NaN would draw nothing,
 * and neither would look like a bug in a query.
 *
 * Those are the only guards it needs, which is worth saying because the obvious
 * extra two are not: clamping to 1 cannot fire, since `max` is this list's own
 * largest and nothing exceeds it, and flooring `max` at 1 cannot fire either,
 * since a `max` of zero means every count was zero and this returned already.
 * Both were here, and mutation testing on the Kotlin side found them -- dead
 * guards that read as caution are worse than none, since they suggest a case
 * that was considered and handled.
 */
function share(count: number, max: number): number {
  if (!Number.isFinite(count) || count <= 0) return 0;
  return count / max;
}

/**
 * What to draw beside a colour's bar.
 *
 * A named colour draws the palette's own hex rather than the stored one, so a
 * wardrobe holding both `#cc0000` and `#CC0000` shows one swatch and not two
 * spellings of it. An unnamed colour keeps its stored value: it is a hex too,
 * just not one with a name.
 */
function swatchFor(key: string): string {
  const named = paletteColorFor(key);
  if (named === null) return key;
  return named.key === MULTI_SWATCH ? MULTI_SWATCH : named.hex;
}

/**
 * By name rather than by count.
 *
 * `localeCompare` so that accented brands sort where a reader expects rather
 * than after Z, which is where a plain code-point comparison puts them.
 */
function alphabetically(brands: StatBar[]): StatBar[] {
  return [...brands].sort((a, b) => a.key.localeCompare(b.key));
}
