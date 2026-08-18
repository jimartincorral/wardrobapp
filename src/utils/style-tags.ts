import { SEASON_OPTIONS } from '../constants/style-filters';
import type { SeasonOption } from '../constants/style-filters';

const seasonSet = new Set<string>(SEASON_OPTIONS);

/**
 * Tag values that used to be structured filters and no longer are.
 *
 * Weather was removed outright (it largely restated season), and occasion is
 * now derived from a garment's type rather than tagged. Old rows still contain
 * these values inside their tags array, so they are filtered out on read as
 * well as stripped by a one-time migration -- the migration alone is not
 * enough, because restoring an older backup would reintroduce them and they
 * would then surface as if the user had typed them as custom tags.
 */
const LEGACY_STRUCTURED_TAGS = new Set<string>([
  // weather
  'hot', 'warm', 'cool', 'cold', 'rainy', 'snowy', 'windy',
  // occasion
  'casual', 'work', 'formal', 'sport', 'lounge', 'party', 'travel',
]);

export function isLegacyStructuredTag(tag: string): boolean {
  return LEGACY_STRUCTURED_TAGS.has(normalizeTag(tag));
}

function normalizeTag(tag: string): string {
  return tag.trim().toLowerCase();
}

export function mergeStructuredTags(
  customTags: string[],
  seasons: SeasonOption[]
): string[] {
  const all = [...customTags, ...seasons];
  const seen = new Set<string>();
  const merged: string[] = [];

  for (const tag of all) {
    const normalized = normalizeTag(tag);
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    merged.push(normalized);
  }

  return merged;
}

export function splitStructuredTags(tags: string[]): {
  customTags: string[];
  seasons: SeasonOption[];
} {
  const customTags: string[] = [];
  const seasons: SeasonOption[] = [];

  for (const rawTag of tags) {
    const tag = normalizeTag(rawTag);
    if (!tag) continue;
    if (seasonSet.has(tag)) {
      seasons.push(tag as SeasonOption);
      continue;
    }
    if (LEGACY_STRUCTURED_TAGS.has(tag)) continue;
    customTags.push(tag);
  }

  return { customTags, seasons };
}
