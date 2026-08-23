import { describe, expect, it } from 'vitest';
import { mergeStructuredTags, splitStructuredTags, isLegacyStructuredTag } from './style-tags';

describe('mergeStructuredTags', () => {
  it('lowercases and de-duplicates custom tags and seasons', () => {
    expect(mergeStructuredTags(['Cotton', 'cotton', 'Striped'], ['summer', 'summer'])).toEqual([
      'cotton',
      'striped',
      'summer',
    ]);
  });

  it('drops blank tags', () => {
    expect(mergeStructuredTags(['  ', 'linen'], [])).toEqual(['linen']);
  });
});

describe('splitStructuredTags', () => {
  it('separates seasons from custom tags', () => {
    const result = splitStructuredTags(['cotton', 'winter', 'striped', 'all-season']);
    expect(result.seasons).toEqual(['winter', 'all-season']);
    expect(result.customTags).toEqual(['cotton', 'striped']);
  });

  it('normalizes case and drops blanks, whatever it is handed', () => {
    // Tags arrive lowercased from row normalization, so nothing in the app
    // exercises this -- but the function is what defines "a tag", and the Kotlin
    // port has to agree about it.
    const result = splitStructuredTags(['Cotton', ' SUMMER ', '   ', 'Formal', 'STRIPED']);

    expect(result.customTags).toEqual(['cotton', 'striped']);
    expect(result.seasons).toEqual(['summer']);
  });

  it('discards weather and occasion values left over from older versions', () => {
    // Restoring an old backup reintroduces these; without this they would
    // resurface as if the user had typed them as custom tags.
    const result = splitStructuredTags(['hot', 'casual', 'wool', 'summer', 'party']);
    expect(result.customTags).toEqual(['wool']);
    expect(result.seasons).toEqual(['summer']);
  });
});

describe('isLegacyStructuredTag', () => {
  it('recognises removed weather and occasion values regardless of casing', () => {
    expect(isLegacyStructuredTag('Rainy')).toBe(true);
    expect(isLegacyStructuredTag(' WORK ')).toBe(true);
    expect(isLegacyStructuredTag('wool')).toBe(false);
    expect(isLegacyStructuredTag('summer')).toBe(false);
  });
});
