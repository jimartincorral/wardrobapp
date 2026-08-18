import { OCCASION_OPTIONS } from '../constants/style-filters';
import type { OccasionOption } from '../constants/style-filters';
import type { Garment } from '../types';

/**
 * Occasions a garment type is suitable for.
 *
 * Occasion used to be a set of chips the user ticked per garment, which meant
 * it only worked for people willing to tag their whole wardrobe -- so in
 * practice it stayed empty and the filter did nothing. Deriving it from the
 * garment's type instead makes the filter work for everyone with no data entry,
 * at the cost of not being able to express "this specific shirt is my work
 * shirt". That trade is worth it while nothing is being tagged at all.
 *
 * A garment can suit several occasions; order here does not matter, results are
 * returned in OCCASION_OPTIONS order.
 */
const SUBCATEGORY_OCCASIONS: Record<string, OccasionOption[]> = {
  // Tops
  'T-Shirt': ['casual'],
  Blouse: ['work', 'formal'],
  Shirt: ['work', 'casual'],
  'Tank Top': ['casual'],
  Sweater: ['casual', 'work'],
  Hoodie: ['casual', 'lounge'],
  'Crop Top': ['casual'],
  Polo: ['casual', 'work'],

  // Bottoms
  Jeans: ['casual'],
  Pants: ['work', 'casual'],
  Shorts: ['casual'],
  Skirt: ['work', 'casual'],
  Leggings: ['sport', 'casual'],
  Sweatpants: ['lounge', 'casual'],
  Chinos: ['work', 'casual'],

  // Dresses
  Mini: ['casual'],
  Midi: ['work', 'casual'],
  Maxi: ['formal', 'casual'],
  Cocktail: ['formal'],
  Sundress: ['casual'],
  Jumpsuit: ['casual', 'work'],
  Romper: ['casual'],

  // Mid-layer
  Blazer: ['work', 'formal'],
  Overshirt: ['casual'],
  Vest: ['work', 'casual'],
  Poncho: ['casual'],
  Cape: ['formal'],

  // Outerwear
  Jacket: ['casual'],
  Coat: ['work', 'casual'],
  Cardigan: ['casual', 'work'],
  Windbreaker: ['sport', 'casual'],
  Parka: ['casual'],

  // Shoes
  Sneakers: ['casual', 'sport'],
  Boots: ['casual'],
  Sandals: ['casual'],
  Heels: ['formal', 'work'],
  Flats: ['work', 'casual'],
  Loafers: ['work', 'casual'],
  Athletic: ['sport'],

  // Accessories
  Hat: ['casual'],
  Scarf: ['casual'],
  Foulard: ['work'],
  Belt: ['work', 'casual'],
  Bag: ['casual', 'work'],
  Wallet: ['casual'],
  Gloves: ['casual'],
  Jewelry: ['formal'],
  Watch: ['work', 'casual'],
  Sunglasses: ['casual'],
  Tie: ['work', 'formal'],

  // Activewear
  'Sports Bra': ['sport'],
  'Workout Top': ['sport'],
  'Workout Shorts': ['sport'],
  'Yoga Pants': ['sport'],
  'Track Suit': ['sport', 'lounge'],

  // Loungewear
  'Pajama Set': ['lounge'],
  'Pajama Top': ['lounge'],
  'Pajama Bottoms': ['lounge'],
  Nightgown: ['lounge'],
  Robe: ['lounge'],
  'Lounge Set': ['lounge'],
};

/** Fallback when a garment has no subcategory, or an unrecognised one. */
const CATEGORY_OCCASIONS: Record<string, OccasionOption[]> = {
  activewear: ['sport'],
  loungewear: ['lounge'],
  // Underwear is not an outfit-occasion concept; it deliberately maps to none.
  underwear: [],
};

const DEFAULT_OCCASIONS: OccasionOption[] = ['casual'];

export function getOccasionsFor(
  category: string,
  subcategories: string[] | null | undefined
): OccasionOption[] {
  const matched = new Set<OccasionOption>();

  for (const subcategory of subcategories ?? []) {
    for (const occasion of SUBCATEGORY_OCCASIONS[subcategory] ?? []) {
      matched.add(occasion);
    }
  }

  if (matched.size === 0) {
    const fallback = CATEGORY_OCCASIONS[category] ?? DEFAULT_OCCASIONS;
    for (const occasion of fallback) matched.add(occasion);
  }

  return OCCASION_OPTIONS.filter(option => matched.has(option));
}

export function getGarmentOccasions(garment: Garment): OccasionOption[] {
  const subcategories = garment.subcategories.length > 0
    ? garment.subcategories
    : (garment.subcategory ? [garment.subcategory] : []);

  return getOccasionsFor(garment.category, subcategories);
}
