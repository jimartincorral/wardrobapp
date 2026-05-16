export const SEASON_OPTIONS = ['spring', 'summer', 'fall', 'winter', 'all-season'] as const;
export const WEATHER_OPTIONS = ['hot', 'warm', 'cool', 'cold', 'rainy', 'snowy', 'windy'] as const;
export const OCCASION_OPTIONS = ['casual', 'work', 'formal', 'sport', 'lounge', 'party', 'travel'] as const;

export type SeasonOption = (typeof SEASON_OPTIONS)[number];
export type WeatherOption = (typeof WEATHER_OPTIONS)[number];
export type OccasionOption = (typeof OCCASION_OPTIONS)[number];

type StyleFilterOption = SeasonOption | WeatherOption | OccasionOption;

export const STYLE_FILTER_EMOJIS: Record<StyleFilterOption, string> = {
  spring: '🌸',
  summer: '☀️',
  fall: '🍂',
  winter: '❄️',
  'all-season': '🧥',
  hot: '🔥',
  warm: '🌤️',
  cool: '🍃',
  cold: '🧊',
  rainy: '🌧️',
  snowy: '🌨️',
  windy: '💨',
  casual: '😌',
  work: '💼',
  formal: '🎩',
  sport: '🏃',
  lounge: '🛋️',
  party: '🎉',
  travel: '✈️',
};

export const STYLE_FILTER_COLORS: Record<StyleFilterOption, string> = {
  spring: '#F472B6',
  summer: '#F59E0B',
  fall: '#FB7185',
  winter: '#60A5FA',
  'all-season': '#14B8A6',
  hot: '#EF4444',
  warm: '#F97316',
  cool: '#22C55E',
  cold: '#3B82F6',
  rainy: '#0EA5E9',
  snowy: '#94A3B8',
  windy: '#06B6D4',
  casual: '#10B981',
  work: '#6366F1',
  formal: '#334155',
  sport: '#F43F5E',
  lounge: '#A855F7',
  party: '#EC4899',
  travel: '#14B8A6',
};
