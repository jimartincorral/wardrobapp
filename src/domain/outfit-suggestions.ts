/**
 * Outfit suggestion algorithm.
 *
 * Pure domain logic: no database, no filesystem, no clock, no platform. Every
 * input arrives through `SuggestionContext`, so a run is reproducible and this
 * module can be tested — and ported — on its own.
 */
import { colorHarmonyScore } from '../utils/color-distance';
import type { OccasionOption, SeasonOption } from '../constants/style-filters';
import { getGarmentOccasions } from '../utils/garment-occasions';
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

export interface ScoredOutfit {
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

export interface SuggestionPreferences {
  seasons?: SeasonOption[];
  occasion?: OccasionOption;
}

export interface GenerateSuggestionsOptions {
  count?: number;
  preferences?: SuggestionPreferences;
  seedGarments?: Garment[];
}

/** Looks up the learned score for a garment pair, in either order. */
export type PairScoreLookup = (idA: string, idB: string) => number;

/** Stable key for a garment pair, independent of the order given. */
export function pairKey(idA: string, idB: string): string {
  return idA < idB ? `${idA}|${idB}` : `${idB}|${idA}`;
}

/**
 * Check if garment's tags match the current season.
 */
function matchesSeason(
  garment: Garment,
  currentSeason: string,
  seasons?: SeasonOption[]
): boolean {
  const tags = garment.tags.map(t => t.toLowerCase());
  const selectedSeasons = (seasons ?? []).filter(season => season !== 'all-season');
  const activeSeasons = selectedSeasons.length > 0 ? selectedSeasons : [currentSeason];

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

function occasionFit(garment: Garment, preferences?: SuggestionPreferences): number {
  if (!preferences?.occasion) return 0;
  // Derived from the garment's type -- occasion is no longer a stored tag.
  return getGarmentOccasions(garment).includes(preferences.occasion) ? 1 : 0;
}

/**
 * Whether a garment suits the season in play: +1 for a fit, -1 against an
 * explicit selection it contradicts, 0 when there is nothing to say.
 */
function seasonFit(
  garment: Garment,
  currentSeason: string,
  preferences?: SuggestionPreferences
): number {
  const hasSelection = Boolean(preferences?.seasons?.length);
  const fits = matchesSeason(garment, currentSeason, preferences?.seasons);
  if (hasSelection) return fits ? 1 : -1;
  // With no selection, reward a seasonal fit but do not punish a silent garment.
  return fits ? 1 : 0;
}

/**
 * Per-garment steer used while *choosing* garments, as opposed to scoring a
 * finished outfit. Season and occasion both belong here.
 */
function contextScore(
  garment: Garment,
  currentSeason: string,
  preferences?: SuggestionPreferences
): number {
  return seasonFit(garment, currentSeason, preferences) + occasionFit(garment, preferences);
}

/**
 * Score a candidate outfit.
 */
function scoreOutfit(
  garments: Garment[],
  getPairScore: PairScoreLookup,
  currentSeason: string,
  preferences?: SuggestionPreferences
): number {
  let score = 0;

  // Pair scores from learning
  let pairTotal = 0;
  let pairCount = 0;
  for (let i = 0; i < garments.length; i++) {
    for (let j = i + 1; j < garments.length; j++) {
      pairTotal += getPairScore(garments[i].id, garments[j].id);
      pairCount++;
    }
  }
  if (pairCount > 0) score += (pairTotal / pairCount) * 3; // Weight learned preferences heavily

  // Season and occasion, each counted exactly once. Season used to be added
  // here at weight 1.0 and again inside contextScore at weight 1.2, giving it an
  // effective weight of 2.2 -- more than colour harmony, and more than intended.
  const seasonTotal = garments.reduce((sum, g) => sum + seasonFit(g, currentSeason, preferences), 0);
  score += (seasonTotal / garments.length) * 1.0;

  const occasionTotal = garments.reduce((sum, g) => sum + occasionFit(g, preferences), 0);
  score += (occasionTotal / garments.length) * 1.2;

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

/** Scores within this of the best count as tied rather than beaten. */
const SCORE_TIE_EPSILON = 1e-9;

/**
 * Pick whichever candidate fits the outfit so far best, choosing at random
 * between equals.
 *
 * Cold, everything ties: no pair has a learned score, and harmony is the same
 * bucket for most palette pairs. The comparison used to be a strict `>` against
 * `-Infinity`, which always kept the *first* candidate -- and candidates arrive
 * newest-first, so one garment took 722 of 900 slots in a 20-garment wardrobe.
 */
function pickBestFit(
  available: Garment[],
  selected: Garment[],
  getPairScore: PairScoreLookup,
  currentSeason: string,
  random: () => number,
  preferences?: SuggestionPreferences
): Garment {
  let bestScore = -Infinity;
  let tied: Garment[] = [];

  for (const candidate of available) {
    const pairScoreSum = selected.reduce(
      (sum, s) => sum + getPairScore(candidate.id, s.id), 0
    );
    const harmony = selected.reduce(
      (sum, s) => sum + colorHarmonyScore(
        getGarmentPrimaryColor(candidate), getGarmentPrimaryColor(s)
      ), 0
    );
    const total = pairScoreSum + harmony + contextScore(candidate, currentSeason, preferences) * 1.5;

    if (total > bestScore + SCORE_TIE_EPSILON) {
      bestScore = total;
      tied = [candidate];
    } else if (Math.abs(total - bestScore) <= SCORE_TIE_EPSILON) {
      tied.push(candidate);
    }
  }

  return tied[Math.floor(random() * tied.length)];
}

/**
 * Pick at random, in proportion to how well each candidate fits the context.
 *
 * This is the exploration half of epsilon-greedy, and it has to be able to
 * reach everything. It used to sort by weight and sample from the top 60%, so
 * with no preferences set every weight was equal, the sort was a no-op, and the
 * oldest 40% of every slot could never be picked at all -- never suggested, so
 * never rated, so never able to earn a score that would make them reachable. A
 * roulette-wheel draw keeps the bias towards good fits without excluding anyone.
 */
function pickWeightedAtRandom(
  available: Garment[],
  currentSeason: string,
  random: () => number,
  preferences?: SuggestionPreferences
): Garment {
  const weights = available.map(
    g => Math.max(0.1, 1 + contextScore(g, currentSeason, preferences))
  );
  const total = weights.reduce((sum, w) => sum + w, 0);

  let ticket = random() * total;
  for (let i = 0; i < available.length; i++) {
    ticket -= weights[i];
    if (ticket <= 0) return available[i];
  }
  // Only reachable through floating-point drift at the very end of the wheel.
  return available[available.length - 1];
}

/**
 * Everything the engine needs from outside itself.
 *
 * Passing these in rather than fetching them keeps `buildSuggestions` pure: the
 * same context and options always produce the same outfits, which is what makes
 * the behaviour testable and the algorithm portable.
 */
export interface SuggestionContext {
  /** Garments the suggestion may draw from. */
  garments: Garment[];
  /** Learned affinity for a garment pair, in either order. */
  getPairScore: PairScoreLookup;
  /** Season assumed when the user has not selected one. */
  currentSeason: string;
  /** Source of randomness, injected so a run can be reproduced. */
  random: () => number;
}

/**
 * Generate outfit suggestions from an explicit context.
 *
 * Uses epsilon-greedy: 80% best-scoring picks, 20% random for variety.
 *
 * @param options.count - Number of outfits to generate
 * @param options.preferences - Filter by season/occasion
 * @param options.seedGarments - Garments that MUST be included in the outfit
 */
export function buildSuggestions(
  context: SuggestionContext,
  options: GenerateSuggestionsOptions = {}
): ScoredOutfit[] {
  const { count = 3, preferences, seedGarments = [] } = options;
  const { garments, getPairScore, currentSeason, random } = context;

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

  // Keep heavy outerwear out of summer outfits. This used to key off a "hot"
  // weather filter; with weather gone it keys off an explicit summer selection,
  // which is the same intent expressed with the vocabulary that remains.
  const summerOnly =
    preferences?.seasons?.length === 1 && preferences.seasons[0] === 'summer';
  if (summerOnly && bySlot.outerwear) {
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
    const template = viableTemplates[Math.floor(random() * viableTemplates.length)];

    // For each outfit slot, pick a garment (epsilon-greedy)
    const selected: Garment[] = [...seedGarments];
    const usedGarmentIds = new Set(seedGarments.map(g => g.id));

    for (const slot of template) {
      // A seeded garment already fills its slot. Without this the loop filled it
      // again -- seeding a top produced outfits containing two tops, because
      // usedGarmentIds blocks repeating a garment but not repeating a slot.
      if (seedSlots.has(slot)) continue;

      const available = (bySlot[slot] || []).filter(g => !usedGarmentIds.has(g.id));
      if (available.length === 0) continue;

      const picked = random() < 0.8 && selected.length > 0
        ? pickBestFit(available, selected, getPairScore, currentSeason, random, preferences)
        : pickWeightedAtRandom(available, currentSeason, random, preferences);

      selected.push(picked);
      usedGarmentIds.add(picked.id);
    }

    if (selected.length === 0) continue;

    const score = scoreOutfit(selected, getPairScore, currentSeason, preferences);
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
