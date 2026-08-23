import { describe, expect, it } from 'vitest';
import { getCurrentSeason } from './date-helpers';

describe('getCurrentSeason', () => {
  // The suggestion engine falls back to this whenever the user has not picked a
  // season, so it decides what a wardrobe is judged against most of the time --
  // and the month boundaries are the kind of thing that is wrong silently. Its
  // own service test mocks it, so nothing checked it directly until now.
  const seasonInMonth = (month: number) => getCurrentSeason(new Date(2026, month, 15));

  it('runs March to May as spring', () => {
    expect([2, 3, 4].map(seasonInMonth)).toEqual(['spring', 'spring', 'spring']);
  });

  it('runs June to August as summer', () => {
    expect([5, 6, 7].map(seasonInMonth)).toEqual(['summer', 'summer', 'summer']);
  });

  it('runs September to November as fall', () => {
    expect([8, 9, 10].map(seasonInMonth)).toEqual(['fall', 'fall', 'fall']);
  });

  it('wraps December to February as winter', () => {
    expect([11, 0, 1].map(seasonInMonth)).toEqual(['winter', 'winter', 'winter']);
  });

  it('covers every month, and never says all-season', () => {
    // 'all-season' is something a garment can be, not a time of year.
    const seasons = new Set(Array.from({ length: 12 }, (_, m) => seasonInMonth(m)));

    expect(seasons).toEqual(new Set(['spring', 'summer', 'fall', 'winter']));
  });
});
