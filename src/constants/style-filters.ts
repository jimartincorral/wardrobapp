export const SEASON_OPTIONS = ['spring', 'summer', 'fall', 'winter', 'all-season'] as const;

/**
 * Occasions are derived from a garment's type rather than tagged by hand (see
 * utils/garment-occasions). The list is therefore limited to what a garment's
 * type can actually imply -- "party" and "travel" used to be options here, but
 * nothing about a garment tells you it is for travel, so filtering by them
 * could only ever return nothing. These five match the Occasion domain type.
 */
export const OCCASION_OPTIONS = ['casual', 'work', 'formal', 'sport', 'lounge'] as const;

export type SeasonOption = (typeof SEASON_OPTIONS)[number];
export type OccasionOption = (typeof OCCASION_OPTIONS)[number];

type StyleFilterOption = SeasonOption | OccasionOption;

export const STYLE_FILTER_EMOJIS: Record<StyleFilterOption, string> = {
  spring: '🌸',
  summer: '☀️',
  fall: '🍂',
  winter: '❄️',
  'all-season': '🧥',
  casual: '😌',
  work: '💼',
  formal: '🎩',
  sport: '🏃',
  lounge: '🛋️',
};

export const STYLE_FILTER_COLORS: Record<StyleFilterOption, string> = {
  spring: '#F472B6',
  summer: '#F59E0B',
  fall: '#FB7185',
  winter: '#60A5FA',
  'all-season': '#14B8A6',
  casual: '#10B981',
  work: '#6366F1',
  formal: '#334155',
  sport: '#F43F5E',
  lounge: '#A855F7',
};
