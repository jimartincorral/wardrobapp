export interface Garment {
  id: string;
  image_uri: string;
  image_uri_nobg: string | null;
  image_uris: string[];
  image_uris_nobg: string[];
  category: string;
  subcategory: string | null;
  subcategories: string[];
  tags: string[];
  brand: string | null;
  color_primary: string;
  color_secondary: string | null;
  color_palette: string[];
  size: string | null;
  purchase_date: string | null;
  is_available: boolean;
  unavailable_date: string | null;
  created_at: string;
  updated_at: string;
}

export interface Outfit {
  id: string;
  name: string;
  garment_ids: string[];
  occasion: string | null;
  season: string | null;
  created_at: string;
  is_suggested: boolean;
  is_pinned: boolean;
}

export interface OutfitRating {
  id: string;
  outfit_id: string;
  rating: number;
  feedback: string | null;
  rated_at: string;
}

export interface GarmentPairScore {
  garment_id_a: string;
  garment_id_b: string;
  score: number;
  wear_count: number;
}

export interface UserPreference {
  key: string;
  value: string;
}

export type GarmentCategory =
  | 'tops'
  | 'bottoms'
  | 'dresses'
  | 'midlayer'
  | 'outerwear'
  | 'shoes'
  | 'accessories'
  | 'activewear'
  | 'underwear'
  | 'loungewear';

export type Season = 'spring' | 'summer' | 'fall' | 'winter' | 'all';
export type Occasion = 'casual' | 'work' | 'formal' | 'sport' | 'lounge';
