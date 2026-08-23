/**
 * What the analytics screen shows.
 *
 * Pure: counts and lifespans in, bars out. Nothing here reads a database or
 * formats a label -- the arithmetic is the part worth being sure about, since a
 * bar that is the wrong length is wrong in a way nobody notices.
 */

/** A garment as the lifespan chart needs it, which is only its name. */
export type LifespanEntry = {
  garmentId: string;
  category: string;
  subcategories: string[];
  days: number;
};

/** One bar: the number it reports, and how much of the track to fill. */
export type Bar = {
  key: string;
  value: number;
  /** 0 to 1. Never negative, never over 1, never NaN. */
  fraction: number;
};

export type CategoryBar = Bar & { category: string };
export type LifespanBar = Bar & { entry: LifespanEntry };

/**
 * A lifespan bar is full at a year.
 *
 * An arbitrary scale, but a readable one: most garments people retire have been
 * owned for months, and against a longest-owned-garment scale everything else
 * would be a sliver.
 */
export const LIFESPAN_FULL_BAR_DAYS = 365;

/** How many lifespans the chart has room for. */
export const LIFESPAN_BARS = 3;

/**
 * A share of a whole, safe at the edges.
 *
 * An empty wardrobe divides by zero -- which is not a rounding problem but a
 * NaN, and a NaN width draws nothing at all while reporting a count beside it.
 */
function share(value: number, total: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(total) || total <= 0) return 0;
  return Math.min(1, Math.max(0, value / total));
}

export type AnalyticsView = {
  totalItems: number;
  archivedItems: number;
  categories: CategoryBar[];
  lifespans: LifespanBar[];
  /** True for a wardrobe with nothing in it, which is what the nudge turns on. */
  isEmpty: boolean;
};

export function analyticsView(input: {
  totalItems: number;
  archivedItems: number;
  categoryCounts: { category: string; count: number }[];
  lifespans: LifespanEntry[];
}): AnalyticsView {
  const categories = input.categoryCounts.map(entry => ({
    key: entry.category,
    category: entry.category,
    value: entry.count,
    fraction: share(entry.count, input.totalItems),
  }));

  const lifespans = input.lifespans.slice(0, LIFESPAN_BARS).map(entry => ({
    key: entry.garmentId,
    entry,
    value: entry.days,
    // A garment retired before the purchase date recorded for it gives a
    // negative span -- an edit away, and not something the chart should try to
    // draw. Clamped rather than dropped: the number beside the bar is still the
    // truth about the row.
    fraction: share(entry.days, LIFESPAN_FULL_BAR_DAYS),
  }));

  return {
    totalItems: input.totalItems,
    archivedItems: input.archivedItems,
    categories,
    lifespans,
    isEmpty: input.totalItems === 0,
  };
}
