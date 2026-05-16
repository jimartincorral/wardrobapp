import { describe, expect, it } from 'vitest';
import { mergeStructuredTags, splitStructuredTags } from './style-tags';

describe('mergeStructuredTags', () => {
  it('normalizes and deduplicates tags across all inputs', () => {
    const merged = mergeStructuredTags(
      [' Summer ', 'Denim', 'summer', ''],
      ['summer'],
      ['warm'],
      ['work']
    );

    expect(merged).toEqual(['summer', 'denim', 'warm', 'work']);
  });
});

describe('splitStructuredTags', () => {
  it('splits structured tags from custom tags case-insensitively', () => {
    const split = splitStructuredTags([' Summer ', 'RAINY', 'Casual', 'Vintage']);

    expect(split.seasons).toEqual(['summer']);
    expect(split.weather).toEqual(['rainy']);
    expect(split.occasions).toEqual(['casual']);
    expect(split.customTags).toEqual(['vintage']);
  });
});
