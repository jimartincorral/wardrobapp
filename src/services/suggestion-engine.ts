import { getDatabase } from '../db/client';
import { getAllGarments } from './garment-service';
import { colorHarmonyScore } from '../utils/color-distance';
import { getCurrentSeason } from '../utils/date-helpers';
import type { OccasionOption, SeasonOption, WeatherOption } from '../constants/style-filters';
import type { Garment } from '../types';
import { getGarmentPrimaryColor } from '../utils/garment-fields';

type OutfitSlot =
  | 'tops'
  | 'bottoms'
  | 'dresses'
  | 'outerwear'
  | 'shoes'
  | 'accessories'
  | 'activewear_sets'
  | 'loungewear_sets';

// Which category slots make a valid outfit
const OUTFIT_TEMPLATES: OutfitSlot[][] = [
  ['tops', 'bottoms'],
  ['tops', 'bottoms', 'outerwear'],
  ['tops', 'bottoms', 'accessories'],
  ['tops', 'bottoms', 'shoes'],
  ['tops', 'bottoms', 'shoes', 'outerwear'],
  ['dresses'],
  ['dresses', 'outerwear'],
  ['dresses', 'shoes'],
  ['dresses', 'shoes', 'outerwear'],
  ['tops', 'bottoms', 'shoes', 'accessories'],
  ['activewear_sets'],
  ['activewear_sets', 'outerwear'],
  ['activewear_sets', 'shoes'],
  ['loungewear_sets'],
  ['loungewear_sets', 'outerwear'],
];

interface ScoredOutfit {
  garments: Garment[];
  score: number;
  name: string;
}

function normalizeOutfitScore(rawScore: number): number {
  // Squash the open-ended ranking score into a stable 0..1 display value.
  return 1 / (1 + Math.exp(-rawScore / 2));
}

function getGarmentSlots(garment: Garment): OutfitSlot[] {
  switch (garment.category) {
    case 'tops':
    case 'bottoms':
    case 'dresses':
    case 'outerwear':
    case 'shoes':
    case 'accessories':
      return [garment.category];
    case 'midlayer':
      // Blazers/vests/ponchos layer over tops like light outerwear.
      return ['outerwear'];
    case 'activewear': {
      const sub = (garment.subcategory || '').toLowerCase();
      if (sub.includes('track suit')) return ['activewear_sets'];
      if (sub.includes('short') || sub.includes('pants')) return ['bottoms'];
      return ['tops'];
    }
    case 'underwear': {
      const sub = (garment.subcategory || '').toLowerCase();
      if (sub.includes('bodysuit') || sub.includes('thermal')) return ['tops'];
      if (sub.includes('tights')) return ['bottoms'];
      return [];
    }
    case 'loungewear': {
      const sub = (garment.subcategory || '').toLowerCase();
      if (sub.includes('set')) return ['loungewear_sets'];
      if (sub.includes('nightgown')) return ['dresses'];
      if (sub.includes('robe')) return ['outerwear'];
      if (sub.includes('bottom')) return ['bottoms'];
      return ['tops'];
    }
    default:
      return [];
  }
}

function getNormalizedTags(garment: Garment): string[] {
  return garment.tags.map(t => t.toLowerCase());
}

function isHotCompatibleOuterwear(garment: Garment): boolean {
  if (garment.category !== 'outerwear') return true;
  const tags = getNormalizedTags(garment);
  const sub = (garment.subcategory || '').toLowerCase();
  if (tags.includes('lightweight') || tags.includes('summer')) return true;
  return ['vest', 'windbreaker', 'cardigan', 'blazer'].includes(sub);
}

function isHeavyOuterwear(garment: Garment): boolean {
  if (garment.category !== 'outerwear') return false;
  const tags = getNormalizedTags(garment);
  const sub = (garment.subcategory || '').toLowerCase();
  if (tags.includes('heavy') || tags.includes('wool') || tags.includes('winter') || tags.includes('thermal')) {
    return true;
  }
  return ['parka', 'coat', 'jacket'].includes(sub);
}

export interface SuggestionPreferences {
  seasons?: SeasonOption[];
  weather?: WeatherOption;
  occasion?: OccasionOption;
}

export interface GenerateSuggestionsOptions {
  count?: number;
  preferences?: SuggestionPreferences;
  seedGarments?: Garment[];
}

/**
 * Get the pair score between two garments from the learning table.
 */
async function getPairScore(idA: string, idB: string): Promise<number> {
  const db = await getDatabase();
  const [a, b] = [idA, idB].sort();
  const result = await db.getFirstAsync<{ score: number }>(
    'SELECT score FROM garment_pair_scores WHERE garment_id_a = ? AND garment_id_b = ?',
    a, b
  );
  return result?.score ?? 0;
}

/**
 * Check if garment's tags match the current season.
 */
function matchesSeason(garment: Garment, seasons?: SeasonOption[]): boolean {
  const tags = garment.tags.map(t => t.toLowerCase());
  const selectedSeasons = (seasons ?? []).filter(season => season !== 'all-season');
  const activeSeasons = selectedSeasons.length > 0 ? selectedSeasons : [getCurrentSeason()];

  if (tags.includes('all-season')) return true;
  if (activeSeasons.some(season => tags.includes(season))) return true;

  const opposites: Record<string, string> = {
    summer: 'winter', winter: 'summer', spring: 'fall', fall: 'spring'
  };

  if (
    activeSeasons.length === 1 &&
    tags.includes(opposites[activeSeasons[0]])
  ) {
    return false;
  }

  return true; // No season tag = assume ok
}

function weatherScore(garment: Garment, weather?: WeatherOption): number {
  if (!weather) return 0;
  const tags = getNormalizedTags(garment);
  if (tags.includes(weather)) return 1;

  if (weather === 'rainy') {
    if (tags.includes('waterproof')) return 0.8;
    if (tags.includes('linen')) return -0.4;
  }
  if (weather === 'snowy') {
    if (tags.includes('heavy') || tags.includes('winter')) return 0.5;
    if (tags.includes('summer') || tags.includes('sleeveless')) return -0.6;
  }
  if (weather === 'hot') {
    if (garment.category === 'outerwear') {
      if (isHotCompatibleOuterwear(garment)) return -0.1;
      if (isHeavyOuterwear(garment)) return -1.2;
      return -0.8;
    }
    if (tags.includes('lightweight') || tags.includes('summer')) return 0.5;
    if (tags.includes('heavy') || tags.includes('wool')) return -0.5;
  }
  if (weather === 'cold') {
    if (garment.category === 'outerwear') return 0.5;
    if (tags.includes('heavy') || tags.includes('wool') || tags.includes('winter')) return 0.5;
    if (tags.includes('sleeveless') || tags.includes('linen')) return -0.5;
  }

  return 0;
}

function occasionScore(garment: Garment, occasion?: OccasionOption): number {
  if (!occasion) return 0;
  const tags = garment.tags.map(t => t.toLowerCase());
  if (tags.includes(occasion)) return 1;
  if (occasion === 'work' && tags.includes('business')) return 0.9;
  if (occasion === 'sport' && tags.includes('sporty')) return 0.9;
  return 0;
}

function contextScore(garment: Garment, preferences?: SuggestionPreferences): number {
  if (!preferences) return 0;
  let score = 0;

  if (preferences.seasons && preferences.seasons.length > 0) {
    score += matchesSeason(garment, preferences.seasons) ? 1 : -1;
  }
  score += weatherScore(garment, preferences.weather);
  score += occasionScore(garment, preferences.occasion);

  return score;
}

/**
 * Score a candidate outfit.
 */
async function scoreOutfit(garments: Garment[], preferences?: SuggestionPreferences): Promise<number> {
  let score = 0;

  // Pair scores from learning
  let pairTotal = 0;
  let pairCount = 0;
  for (let i = 0; i < garments.length; i++) {
    for (let j = i + 1; j < garments.length; j++) {
      pairTotal += await getPairScore(garments[i].id, garments[j].id);
      pairCount++;
    }
  }
  if (pairCount > 0) score += (pairTotal / pairCount) * 3; // Weight learned preferences heavily

  // Season match
  const seasonMatches = garments.filter(g => matchesSeason(g, preferences?.seasons)).length;
  score += (seasonMatches / garments.length) * 1.0;

  // Context preferences: season/weather/occasion
  if (preferences) {
    const contextTotal = garments.reduce((sum, g) => sum + contextScore(g, preferences), 0);
    score += (contextTotal / garments.length) * 1.2;
  }

  // Color harmony
  let harmonyTotal = 0;
  let harmonyCount = 0;
  for (let i = 0; i < garments.length; i++) {
    for (let j = i + 1; j < garments.length; j++) {
      harmonyTotal += colorHarmonyScore(getGarmentPrimaryColor(garments[i]), getGarmentPrimaryColor(garments[j]));
      harmonyCount++;
    }
  }
  if (harmonyCount > 0) score += (harmonyTotal / harmonyCount) * 1.5;

  return score;
}

/**
 * Generate outfit suggestions.
 * Uses epsilon-greedy: 80% best-scoring picks, 20% random for variety.
 * 
 * @param options.count - Number of outfits to generate
 * @param options.preferences - Filter by season/weather/occasion
 * @param options.seedGarments - Garments that MUST be included in the outfit
 */
export async function generateSuggestions(options: GenerateSuggestionsOptions = {}): Promise<ScoredOutfit[]> {
  const { count = 3, preferences, seedGarments = [] } = options;
  const garments = await getAllGarments({ available_only: true });

  if (garments.length === 0) return [];

  const seedSlots = new Set(seedGarments.flatMap(getGarmentSlots));

  // Group by outfit slot so we can support categories that behave like tops/bottoms.
  const bySlot: Partial<Record<OutfitSlot, Garment[]>> = {};
  for (const g of garments) {
    for (const slot of getGarmentSlots(g)) {
      if (!bySlot[slot]) bySlot[slot] = [];
      bySlot[slot].push(g);
    }
  }

  if (preferences?.weather === 'hot' && bySlot.outerwear) {
    bySlot.outerwear = bySlot.outerwear.filter(isHotCompatibleOuterwear);
  }

  // Find viable templates (ones we have garments for)
  // If seed garments provided, filter to templates that include all seed slots.
  let viableTemplates = OUTFIT_TEMPLATES.filter(template =>
    template.every(slot => bySlot[slot] && bySlot[slot].length > 0)
  );

  if (seedSlots.size > 0) {
    viableTemplates = viableTemplates.filter(template =>
      [...seedSlots].every(slot => template.includes(slot))
    );
  }

  if (viableTemplates.length === 0) return [];

  const candidates: ScoredOutfit[] = [];
  const attempts = Math.min(count * 5, 20);

  for (let i = 0; i < attempts; i++) {
    // Pick a random template
    const template = viableTemplates[Math.floor(Math.random() * viableTemplates.length)];

    // For each outfit slot, pick a garment (epsilon-greedy)
    const selected: Garment[] = [...seedGarments];
    const usedGarmentIds = new Set(seedGarments.map(g => g.id));

    for (const slot of template) {
      const available = (bySlot[slot] || []).filter(g => !usedGarmentIds.has(g.id));
      if (available.length === 0) continue;

      if (Math.random() < 0.8 && selected.length > 0) {
        // Pick best-scoring with existing selections
        let bestGarment = available[0];
        let bestScore = -Infinity;
        for (const g of available) {
          let pairScoreSum = 0;
          for (const s of selected) {
            pairScoreSum += await getPairScore(g.id, s.id);
          }
          const harmony = selected.reduce(
            (sum, s) => sum + colorHarmonyScore(getGarmentPrimaryColor(g), getGarmentPrimaryColor(s)), 0
          );
          const totalScore = pairScoreSum + harmony + contextScore(g, preferences) * 1.5;
          if (totalScore > bestScore) {
            bestScore = totalScore;
            bestGarment = g;
          }
        }
        selected.push(bestGarment);
        usedGarmentIds.add(bestGarment.id);
      } else {
        // Random pick with bias toward context matches
        const weighted = available
          .map(g => ({ garment: g, weight: Math.max(0.1, 1 + contextScore(g, preferences)) }))
          .sort((a, b) => b.weight - a.weight);
        const topSlice = weighted.slice(0, Math.max(1, Math.ceil(weighted.length * 0.6)));
        const picked = topSlice[Math.floor(Math.random() * topSlice.length)].garment;
        selected.push(picked);
        usedGarmentIds.add(picked.id);
      }
    }

    if (selected.length === 0) continue;

    const score = await scoreOutfit(selected, preferences);
    const categoryNames = selected.map(g => g.subcategory || g.category).join(' + ');
    candidates.push({
      garments: selected,
      score,
      name: categoryNames,
    });
  }

  // Sort by score and return top N, avoiding duplicate combinations
  candidates.sort((a, b) => b.score - a.score);

  const seen = new Set<string>();
  const results: ScoredOutfit[] = [];
  for (const c of candidates) {
    const key = c.garments.map(g => g.id).sort().join(',');
    if (!seen.has(key)) {
      seen.add(key);
      results.push({
        ...c,
        score: normalizeOutfitScore(c.score),
      });
      if (results.length >= count) break;
    }
  }

  return results;
}
