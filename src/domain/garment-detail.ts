/**
 * What a garment's detail screen shows.
 *
 * Pure: a garment and which photo is selected in, everything the screen renders
 * out. No formatting of dates and no translation of labels happen here -- those
 * belong to whichever platform is drawing, and a port that reproduced date-fns's
 * `MMM d, yyyy` in English would be worse on Android than using the device's
 * own locale. What is decided here is *which* things are shown and *what* they
 * refer to.
 */
import { GARMENT_COLORS } from '../constants/colors';
import { SEASON_OPTIONS } from '../constants/style-filters';
import type { OccasionOption, SeasonOption } from '../constants/style-filters';
import { getGarmentOccasions } from '../utils/garment-occasions';
import {
  getGarmentColorPalette,
  getGarmentImageUris,
  getGarmentNoBgImageUris,
} from '../utils/garment-fields';
import { splitStructuredTags } from '../utils/style-tags';
import type { Garment } from '../types';

/** One entry of the palette: the colour itself, and its name if it has one. */
export type PaletteEntry = {
  hex: string;
  /**
   * The key in GARMENT_COLORS, or null for a colour that was not picked from
   * the palette. Matched case-insensitively: the same hex is stored in both
   * cases across the wardrobe, and '#cc0000' is the same red as '#CC0000'.
   */
  colorKey: string | null;
};

/**
 * What the background-removal button offers for the selected photo.
 *
 * `undo` only when a separate original still exists to go back to. Removing a
 * background replaces the photo it came from, so for anything imported since
 * that change there is nothing to revert to and offering it would be a button
 * that destroys the only copy.
 */
export type BackgroundAction = 'remove' | 'undo' | null;

/** One photo in the thumbnail strip. */
export type GalleryEntry = {
  /** What the thumbnail shows: the cut-out for this slot if there is one. */
  uri: string;
  selected: boolean;
  /** True when this slot has a cut-out, which is what "undo" would discard. */
  hasCutout: boolean;
};

export type GarmentDetailView = {
  /** The large photo, or null for a garment with no usable photo at all. */
  displayedImage: string | null;
  gallery: GalleryEntry[];
  /** The strip is worth drawing only when there is a choice to make. */
  showsGallery: boolean;
  /** Which gallery entry is selected, after clamping the caller's index. */
  selectedIndex: number;
  category: string;
  subcategories: string[];
  brand: string | null;
  size: string | null;
  seasons: SeasonOption[];
  occasions: OccasionOption[];
  palette: PaletteEntry[];
  /** Tags the user typed, with the structured ones taken out. */
  tags: string[];
  backgroundAction: BackgroundAction;
  isAvailable: boolean;
  /** Raw date strings, for the caller to format. Null when not recorded. */
  unavailableDate: string | null;
  purchaseDate: string | null;
};

/** Blank is not a value: a field of spaces is not something to draw a row for. */
function textOrNull(value: string | null | undefined): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

/**
 * The palette entry a stored colour came from, or null if it was not picked from
 * the palette.
 *
 * Exported because two screens ask: a garment's detail, for its swatches, and the
 * statistics screen, to put a swatch beside a colour's bar. Matched
 * case-insensitively, which is the whole reason it is a function rather than a
 * lookup: the same hex is stored in both cases across the wardrobe.
 *
 * Returns the *entry*, not just the key, so a caller that draws the colour uses
 * the palette's own spelling rather than whatever case happened to be stored.
 */
export function paletteColorFor(hex: string): { key: string; hex: string } | null {
  const normalized = hex.trim().toUpperCase();
  return GARMENT_COLORS.find(c => c.hex.toUpperCase() === normalized) ?? null;
}

function colorKeyFor(hex: string): string | null {
  return paletteColorFor(hex)?.key ?? null;
}

/**
 * What the background-removal control should offer for one photo.
 *
 * Exported because two screens ask it: a garment's detail, and the add/edit form.
 * The form worked it out from two conditions side by side, which is one more place
 * for them to disagree.
 */
export function backgroundActionFor(
  original: string | undefined,
  cutout: string | undefined
): BackgroundAction {
  if (!cutout) return 'remove';
  if (original && original !== cutout) return 'undo';
  return null;
}

export function garmentDetail(garment: Garment, selectedIndex = 0): GarmentDetailView {
  const images = getGarmentImageUris(garment);
  const cutouts = getGarmentNoBgImageUris(garment);

  // An index from outside -- a remembered selection, a garment whose photos were
  // edited since -- is clamped rather than trusted. Reading past the end used to
  // fall through to the first photo while the thumbnail strip showed nothing
  // selected, so the screen disagreed with itself.
  const selected = selectedIndex >= 0 && selectedIndex < images.length ? selectedIndex : 0;

  const gallery: GalleryEntry[] = images.map((uri, index) => ({
    uri: cutouts[index] || uri,
    selected: index === selected,
    hasCutout: Boolean(cutouts[index]),
  }));

  const { customTags, seasons } = splitStructuredTags(garment.tags);

  return {
    // No `|| images[0]` fallback: the clamp above already guarantees the
    // selected slot exists whenever there is any photo at all, so a fallback
    // there would be a branch no test could ever reach.
    displayedImage: cutouts[selected] || images[selected] || null,
    gallery,
    showsGallery: gallery.length > 1,
    selectedIndex: selected,
    category: garment.category,
    // Not filtered for blanks: normalizeGarmentRow already trims them and drops
    // the empties, so a second guarantee here could never fire.
    subcategories: garment.subcategories,
    brand: textOrNull(garment.brand),
    size: textOrNull(garment.size),
    // In the app's own season order rather than the order they were typed, so
    // two garments tagged the same read the same. Deduplicates as a side effect.
    seasons: SEASON_OPTIONS.filter(season => seasons.includes(season)),
    occasions: getGarmentOccasions(garment),
    palette: getGarmentColorPalette(garment).map(hex => ({ hex, colorKey: colorKeyFor(hex) })),
    tags: customTags,
    backgroundAction: backgroundActionFor(images[selected], cutouts[selected]),
    isAvailable: garment.is_available,
    unavailableDate: garment.is_available ? null : textOrNull(garment.unavailable_date),
    purchaseDate: textOrNull(garment.purchase_date),
  };
}
