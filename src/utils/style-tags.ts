import { OCCASION_OPTIONS, SEASON_OPTIONS, WEATHER_OPTIONS } from '../constants/style-filters';
import type { OccasionOption, SeasonOption, WeatherOption } from '../constants/style-filters';

const seasonSet = new Set<string>(SEASON_OPTIONS);
const weatherSet = new Set<string>(WEATHER_OPTIONS);
const occasionSet = new Set<string>(OCCASION_OPTIONS);

function normalizeTag(tag: string): string {
  return tag.trim().toLowerCase();
}

export function mergeStructuredTags(
  customTags: string[],
  seasons: SeasonOption[],
  weather: WeatherOption[],
  occasions: OccasionOption[]
): string[] {
  const all = [...customTags, ...seasons, ...weather, ...occasions];
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
  weather: WeatherOption[];
  occasions: OccasionOption[];
} {
  const customTags: string[] = [];
  const seasons: SeasonOption[] = [];
  const weather: WeatherOption[] = [];
  const occasions: OccasionOption[] = [];

  for (const rawTag of tags) {
    const tag = normalizeTag(rawTag);
    if (seasonSet.has(tag)) {
      seasons.push(tag as SeasonOption);
      continue;
    }
    if (weatherSet.has(tag)) {
      weather.push(tag as WeatherOption);
      continue;
    }
    if (occasionSet.has(tag)) {
      occasions.push(tag as OccasionOption);
      continue;
    }
    customTags.push(tag);
  }

  return { customTags, seasons, weather, occasions };
}
