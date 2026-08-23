import { describe, expect, it } from 'vitest';
import { LIFESPAN_FULL_BAR_DAYS, analyticsView } from './analytics-view';
import type { LifespanEntry } from './analytics-view';

const entry = (garmentId: string, days: number): LifespanEntry => ({
  garmentId,
  category: 'tops',
  subcategories: ['T-Shirt'],
  days,
});

const view = (over: Partial<Parameters<typeof analyticsView>[0]> = {}) =>
  analyticsView({
    totalItems: 0,
    archivedItems: 0,
    categoryCounts: [],
    lifespans: [],
    ...over,
  });

describe('the category chart', () => {
  it('gives each category its share of the wardrobe', () => {
    const result = view({
      totalItems: 10,
      categoryCounts: [{ category: 'tops', count: 6 }, { category: 'shoes', count: 4 }],
    });

    expect(result.categories.map(c => c.fraction)).toEqual([0.6, 0.4]);
    expect(result.categories.map(c => c.value)).toEqual([6, 4]);
  });

  it('fills the track when everything is in one category', () => {
    const result = view({ totalItems: 4, categoryCounts: [{ category: 'tops', count: 4 }] });

    expect(result.categories[0].fraction).toBe(1);
  });

  it('accounts for the whole wardrobe across the bars', () => {
    // The counts and the total come from different queries; if they ever stopped
    // counting the same garments the bars would quietly overflow their track.
    const result = view({
      totalItems: 7,
      categoryCounts: [
        { category: 'tops', count: 3 },
        { category: 'bottoms', count: 3 },
        { category: 'shoes', count: 1 },
      ],
    });

    const total = result.categories.reduce((sum, c) => sum + c.fraction, 0);
    expect(total).toBeCloseTo(1, 10);
  });

  it('draws nothing rather than NaN for an empty wardrobe', () => {
    // Dividing by zero here is not a rounding problem: a NaN width draws no bar
    // at all while the count sits beside it, so the screen contradicts itself.
    const result = view({ totalItems: 0, categoryCounts: [{ category: 'tops', count: 0 }] });

    expect(result.categories[0].fraction).toBe(0);
    expect(Number.isNaN(result.categories[0].fraction)).toBe(false);
  });

  it('never overflows its track, even if the counts disagree with the total', () => {
    const result = view({ totalItems: 2, categoryCounts: [{ category: 'tops', count: 5 }] });

    expect(result.categories[0].fraction).toBe(1);
    // The number is still reported as it was counted.
    expect(result.categories[0].value).toBe(5);
  });

  it('keeps the order it was given', () => {
    // The query orders by count descending; re-sorting here would fight it.
    const result = view({
      totalItems: 6,
      categoryCounts: [
        { category: 'shoes', count: 1 },
        { category: 'tops', count: 5 },
      ],
    });

    expect(result.categories.map(c => c.category)).toEqual(['shoes', 'tops']);
  });
});

describe('the lifespan chart', () => {
  it('fills the track at a year', () => {
    const result = view({ lifespans: [entry('a', LIFESPAN_FULL_BAR_DAYS)] });

    expect(result.lifespans[0].fraction).toBe(1);
  });

  it('scales a shorter life against the year', () => {
    const result = view({ lifespans: [entry('a', 73)] });

    expect(result.lifespans[0].fraction).toBeCloseTo(0.2, 10);
  });

  it('caps a garment owned for years', () => {
    const result = view({ lifespans: [entry('a', 4000)] });

    expect(result.lifespans[0].fraction).toBe(1);
    expect(result.lifespans[0].value).toBe(4000);
  });

  it('does not try to draw a negative span', () => {
    // A garment retired before the purchase date recorded for it -- one edit
    // away. The bar cannot be negative; the number beside it is still the row.
    const result = view({ lifespans: [entry('a', -30)] });

    expect(result.lifespans[0].fraction).toBe(0);
    expect(result.lifespans[0].value).toBe(-30);
  });

  it('shows only the first three', () => {
    const result = view({
      lifespans: [entry('a', 400), entry('b', 300), entry('c', 200), entry('d', 100)],
    });

    expect(result.lifespans.map(l => l.key)).toEqual(['a', 'b', 'c']);
  });

  it('carries what the label needs', () => {
    const result = view({ lifespans: [entry('a', 100)] });

    expect(result.lifespans[0].entry.category).toBe('tops');
    expect(result.lifespans[0].entry.subcategories).toEqual(['T-Shirt']);
  });
});

describe('the summary', () => {
  it('reports both counts as given', () => {
    const result = view({ totalItems: 12, archivedItems: 3 });

    expect(result.totalItems).toBe(12);
    expect(result.archivedItems).toBe(3);
  });

  it('is empty only when nothing is in use', () => {
    // Archived garments are not in the wardrobe, so a wardrobe of nothing but
    // retired garments still needs the getting-started nudge.
    expect(view({ totalItems: 0, archivedItems: 0 }).isEmpty).toBe(true);
    expect(view({ totalItems: 0, archivedItems: 5 }).isEmpty).toBe(true);
    expect(view({ totalItems: 1, archivedItems: 0 }).isEmpty).toBe(false);
  });
});
