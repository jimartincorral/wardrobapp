import { describe, expect, it } from 'vitest';
import {
  NO_FILTERS,
  isUnfiltered,
  occasionChips,
  seasonChips,
  withOccasionSelected,
  withSeasonToggled,
} from './outfit-filters';

const activeSeasons = (filters = NO_FILTERS) =>
  seasonChips(filters).filter(c => c.active).map(c => c.value);

const activeOccasions = (filters = NO_FILTERS) =>
  occasionChips(filters).filter(c => c.active).map(c => c.value);

describe('the season row', () => {
  it('shows "any" as the only thing on when nothing is chosen', () => {
    expect(activeSeasons()).toEqual([null]);
  });

  it('turns "any" off as soon as a season is chosen', () => {
    const filters = withSeasonToggled(NO_FILTERS, 'summer');

    expect(activeSeasons(filters)).toEqual(['summer']);
  });

  it('holds several seasons at once', () => {
    // A garment for spring is often a garment for fall, so this is a set.
    let filters = withSeasonToggled(NO_FILTERS, 'spring');
    filters = withSeasonToggled(filters, 'fall');

    expect(activeSeasons(filters)).toEqual(['spring', 'fall']);
  });

  it('takes a season off when it is tapped again', () => {
    let filters = withSeasonToggled(NO_FILTERS, 'summer');
    filters = withSeasonToggled(filters, 'summer');

    expect(activeSeasons(filters)).toEqual([null]);
    expect(isUnfiltered(filters)).toBe(true);
  });

  it('lands in the same state whether emptied by tapping or by "any"', () => {
    // "any" is derived rather than stored, so there is only one empty state to
    // reach -- which is what makes the row always readable one way.
    const tappedOff = withSeasonToggled(withSeasonToggled(NO_FILTERS, 'winter'), 'winter');
    const cleared = withSeasonToggled(withSeasonToggled(NO_FILTERS, 'winter'), null);

    expect(tappedOff).toEqual(cleared);
  });

  it('clears everything when "any" is tapped', () => {
    let filters = withSeasonToggled(NO_FILTERS, 'spring');
    filters = withSeasonToggled(filters, 'summer');
    filters = withSeasonToggled(filters, null);

    expect(activeSeasons(filters)).toEqual([null]);
  });

  it('keeps seasons in the app order, not the order they were tapped', () => {
    // So the chips and the stored state agree, and so the same choice always
    // reads the same. The engine is indifferent -- it tests membership -- which
    // is what makes this safe to normalise.
    let filters = withSeasonToggled(NO_FILTERS, 'winter');
    filters = withSeasonToggled(filters, 'spring');

    expect(filters.seasons).toEqual(['spring', 'winter']);
  });

  it('offers "any" plus every season, once each', () => {
    expect(seasonChips(NO_FILTERS).map(c => c.value)).toEqual([
      null, 'spring', 'summer', 'fall', 'winter', 'all-season',
    ]);
  });
});

describe('the occasion row', () => {
  it('shows "any" as the only thing on when nothing is chosen', () => {
    expect(activeOccasions()).toEqual([null]);
  });

  it('holds one choice at a time', () => {
    // An outfit is for one thing, unlike a season.
    let filters = withOccasionSelected(NO_FILTERS, 'work');
    filters = withOccasionSelected(filters, 'sport');

    expect(activeOccasions(filters)).toEqual(['sport']);
  });

  it('clears the choice when the active chip is tapped again', () => {
    let filters = withOccasionSelected(NO_FILTERS, 'formal');
    filters = withOccasionSelected(filters, 'formal');

    expect(activeOccasions(filters)).toEqual([null]);
  });

  it('lands in the same state whether cleared by re-tapping or by "any"', () => {
    const reTapped = withOccasionSelected(withOccasionSelected(NO_FILTERS, 'lounge'), 'lounge');
    const cleared = withOccasionSelected(withOccasionSelected(NO_FILTERS, 'lounge'), null);

    expect(reTapped).toEqual(cleared);
  });

  it('offers "any" plus every occasion, once each', () => {
    expect(occasionChips(NO_FILTERS).map(c => c.value)).toEqual([
      null, 'casual', 'work', 'formal', 'sport', 'lounge',
    ]);
  });
});

describe('the two rows together', () => {
  it('keeps a season choice when the occasion changes, and the other way round', () => {
    let filters = withSeasonToggled(NO_FILTERS, 'summer');
    filters = withOccasionSelected(filters, 'work');

    expect(filters).toEqual({ seasons: ['summer'], occasion: 'work' });
    expect(isUnfiltered(filters)).toBe(false);

    filters = withSeasonToggled(filters, null);
    expect(filters).toEqual({ seasons: [], occasion: 'work' });
    expect(isUnfiltered(filters)).toBe(false);
  });

  it('is unfiltered only when both rows are empty', () => {
    expect(isUnfiltered(NO_FILTERS)).toBe(true);
    expect(isUnfiltered(withSeasonToggled(NO_FILTERS, 'fall'))).toBe(false);
    expect(isUnfiltered(withOccasionSelected(NO_FILTERS, 'casual'))).toBe(false);
  });
});
