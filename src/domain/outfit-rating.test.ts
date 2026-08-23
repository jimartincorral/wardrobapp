import { describe, expect, it } from 'vitest';

import { MAX_RATING, ratingSummary } from './outfit-rating';

describe('ratingSummary', () => {
  it('has nothing to say about an unrated outfit', () => {
    expect(ratingSummary([])).toEqual({
      count: 0,
      average: null,
      stars: 0,
      label: null,
      showsAverage: false,
    });
  });

  it('averages what it was given', () => {
    const summary = ratingSummary([4, 5]);
    expect(summary.count).toBe(2);
    expect(summary.average).toBe(4.5);
    expect(summary.label).toBe('4.5');
  });

  it('rounds up at the half rather than down', () => {
    // Rated 4 and 5: showing four stars reads as the lower of the two opinions.
    expect(ratingSummary([4, 5]).stars).toBe(5);
    expect(ratingSummary([3, 4]).stars).toBe(4);
  });

  it('rounds down below the half', () => {
    expect(ratingSummary([3, 3, 4]).stars).toBe(3);
  });

  it('shows one decimal place, even when the average is whole', () => {
    expect(ratingSummary([4, 4]).label).toBe('4.0');
  });

  it('ignores a rating of zero, which means unrated rather than terrible', () => {
    // The star row cannot produce a zero; a row that has one has no rating.
    expect(ratingSummary([0, 4]).count).toBe(1);
    expect(ratingSummary([0, 4]).average).toBe(4);
    expect(ratingSummary([0]).showsAverage).toBe(false);
  });

  it('ignores values that are not numbers', () => {
    expect(ratingSummary([Number.NaN, 5]).count).toBe(1);
    expect(ratingSummary([Number.POSITIVE_INFINITY, 5]).count).toBe(1);
  });

  it('never fills more stars than there are', () => {
    // Not reachable from the star row, but reachable from a restored backup or a
    // hand-edited database.
    expect(ratingSummary([9, 9]).stars).toBe(MAX_RATING);
    // The average itself is reported as it is; only the stars are clamped.
    expect(ratingSummary([9, 9]).average).toBe(9);
  });

  it('handles a single rating', () => {
    const summary = ratingSummary([3]);
    expect(summary.count).toBe(1);
    expect(summary.stars).toBe(3);
    expect(summary.label).toBe('3.0');
    expect(summary.showsAverage).toBe(true);
  });
});
