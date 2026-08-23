/**
 * Garment form state, as pure transitions.
 *
 * Extracted from `useGarmentForm`, which held these as a dozen `useState` pairs
 * with the transitions written against them. Nothing here knows about React,
 * image pickers or permissions — those stay in the hook. What is left is the
 * part with rules in it: how the photo lists stay aligned, which selection
 * survives a removal, and when choosing a garment type may fill in seasons.
 *
 * Every function returns a new state rather than mutating, so a transition can
 * be tested by calling it.
 */
import type { SeasonOption } from '../constants/style-filters';

export interface GarmentFormState {
  imageUris: string[];
  /**
   * Background-removed photos, positionally aligned with `imageUris`: entry `i`
   * is the cut-out of photo `i`, or '' where there is none. The alignment is why
   * removals and reorders have to touch both lists together.
   */
  bgRemovedUris: string[];
  selectedImageIndex: number;
  category: string;
  subcategories: string[];
  tags: string[];
  seasons: SeasonOption[];
  brand: string;
  colorPalette: string[];
  size: string;
}

export const DEFAULT_COLOR = '#000000';

export const EMPTY_FORM: GarmentFormState = {
  imageUris: [],
  bgRemovedUris: [],
  selectedImageIndex: 0,
  category: 'tops',
  subcategories: [],
  tags: [],
  seasons: [],
  brand: '',
  colorPalette: [DEFAULT_COLOR],
  size: '',
};

/** Pad or trim the cut-out list so it lines up with the photos. */
function alignBgRemoved(imageUris: string[], bgRemovedUris: string[] = []): string[] {
  return imageUris.map((_, index) => bgRemovedUris[index] ?? '');
}

export function normalizeForm(data: Partial<GarmentFormState> = {}): GarmentFormState {
  const imageUris = data.imageUris ?? EMPTY_FORM.imageUris;

  return {
    ...EMPTY_FORM,
    ...data,
    imageUris,
    bgRemovedUris: alignBgRemoved(imageUris, data.bgRemovedUris),
    colorPalette: data.colorPalette?.length ? data.colorPalette : EMPTY_FORM.colorPalette,
    selectedImageIndex: data.selectedImageIndex ?? 0,
  };
}

/** Add a photo, or replace the one currently selected. */
export function withImage(
  state: GarmentFormState,
  uri: string,
  replaceCurrent = false
): GarmentFormState {
  if (replaceCurrent && state.imageUris[state.selectedImageIndex]) {
    const at = state.selectedImageIndex;
    return {
      ...state,
      imageUris: state.imageUris.map((item, i) => (i === at ? uri : item)),
      // The old cut-out belonged to the old photo.
      bgRemovedUris: state.bgRemovedUris.map((item, i) => (i === at ? '' : item)),
    };
  }

  return {
    ...state,
    imageUris: [...state.imageUris, uri],
    bgRemovedUris: [...state.bgRemovedUris, ''],
    selectedImageIndex: state.imageUris.length,
  };
}

/**
 * Remove a photo, keeping the selection pointing at something sensible.
 *
 * The original computed the new index inside a `setImageUris` updater, which
 * made that updater impure — React is free to run one more than once. Returning
 * both values from one pure call removes the possibility rather than reasoning
 * about it.
 */
export function withoutImageAt(state: GarmentFormState, index: number): GarmentFormState {
  const imageUris = state.imageUris.filter((_, i) => i !== index);
  const selected = state.selectedImageIndex;

  const selectedImageIndex = imageUris.length === 0
    ? 0
    : selected > index
      ? selected - 1
      : Math.min(selected, imageUris.length - 1);

  return {
    ...state,
    imageUris,
    bgRemovedUris: state.bgRemovedUris.filter((_, i) => i !== index),
    selectedImageIndex,
  };
}

/** Move a photo, carrying its cut-out with it, and follow it with the selection. */
export function withImagesReordered(
  state: GarmentFormState,
  fromIndex: number,
  toIndex: number
): GarmentFormState {
  if (fromIndex === toIndex) return state;
  if (fromIndex < 0 || fromIndex >= state.imageUris.length) return state;
  if (toIndex < 0 || toIndex >= state.imageUris.length) return state;

  const move = <T,>(items: T[]): T[] => {
    const next = [...items];
    const [moved] = next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    return next;
  };

  return {
    ...state,
    imageUris: move(state.imageUris),
    bgRemovedUris: move(state.bgRemovedUris),
    selectedImageIndex: toIndex,
  };
}

/** Record a cut-out for the selected photo, or clear it. */
export function withBackgroundRemoved(
  state: GarmentFormState,
  uri: string
): GarmentFormState {
  return {
    ...state,
    bgRemovedUris: state.bgRemovedUris.map((item, i) =>
      i === state.selectedImageIndex ? uri : item
    ),
  };
}

/**
 * Choosing a garment type implies seasons (a blazer is not summerwear), so they
 * are filled in automatically — but only while the user has not chosen seasons
 * themselves, so an explicit choice is never overwritten.
 *
 * `seasonsFor` is supplied by the caller, keeping the lookup table out of here.
 */
export function withSubcategories(
  state: GarmentFormState,
  subcategories: string[],
  seasonsFor: (subcategories: string[]) => SeasonOption[]
): GarmentFormState {
  return {
    ...state,
    subcategories,
    seasons: state.seasons.length > 0 ? state.seasons : seasonsFor(subcategories),
  };
}

/**
 * Put a detected colour first, keeping whatever the user already picked.
 *
 * Replacing the palette would discard a deliberate choice; the detection is a
 * suggestion, not a correction.
 */
/**
 * Toggle a colour in the palette.
 *
 * A garment always has at least one colour, so removing the last one puts the
 * default back rather than leaving the palette empty. The screen used to do this
 * inline, which meant the rule lived next to the picker rather than with the rest
 * of the form -- and the port would have needed its own copy of it.
 */
export function withColorToggled(state: GarmentFormState, color: string): GarmentFormState {
  const palette = toggled(state.colorPalette, color);

  return { ...state, colorPalette: palette.length > 0 ? palette : [DEFAULT_COLOR] };
}

export function withDetectedColor(state: GarmentFormState, color: string): GarmentFormState {
  return {
    ...state,
    colorPalette: [color, ...state.colorPalette.filter(existing => existing !== color)],
  };
}

/**
 * Apply an imported preview, keeping a brand the user has already typed.
 *
 * An import is a starting point, so it must not overwrite work in progress.
 */
export function withImportedPreview(
  state: GarmentFormState,
  preview: { downloadedImageUris: string[]; brand: string | null }
): GarmentFormState {
  return {
    ...state,
    imageUris: preview.downloadedImageUris,
    bgRemovedUris: preview.downloadedImageUris.map(() => ''),
    selectedImageIndex: 0,
    brand: state.brand.trim() ? state.brand : (preview.brand ?? ''),
  };
}

/** Add a value if absent, remove it if present. */
export function toggled<T extends string>(values: T[], value: T): T[] {
  return values.includes(value) ? values.filter(item => item !== value) : [...values, value];
}

/**
 * Brand suggestions for what has been typed so far.
 *
 * An empty field offers everything — the list doubles as a picker. An exact
 * match is dropped: the user has already typed it, so suggesting it is noise.
 */
export const BRAND_SUGGESTION_LIMIT = 8;

export function brandSuggestions(
  known: string[],
  typed: string,
  limit = BRAND_SUGGESTION_LIMIT
): string[] {
  const needle = typed.trim().toLowerCase();

  return known
    .filter(brand => {
      const candidate = brand.toLowerCase();
      if (candidate === needle) return false;
      return needle ? candidate.includes(needle) : true;
    })
    .slice(0, limit);
}

/** One entry per photo: what to show, and the original behind it. */
export interface GalleryItem {
  /** The cut-out where one exists, else the photo itself. */
  uri: string;
  original: string;
}

export function galleryItems(state: GarmentFormState): GalleryItem[] {
  return state.imageUris.map((uri, index) => ({
    uri: state.bgRemovedUris[index] || uri,
    original: uri,
  }));
}

/** What the preview shows for the selected photo, if anything. */
export function displayedPreviewUri(state: GarmentFormState): string | null {
  const at = state.selectedImageIndex;
  return state.bgRemovedUris[at] || state.imageUris[at] || null;
}

/**
 * Whether the selected photo has a with-background original distinct from its
 * cut-out.
 *
 * A garment imported as a cut-out only has the same path in both slots, so there
 * is nothing to undo to and nothing to re-run removal against.
 */
export function selectedHasOriginal(state: GarmentFormState): boolean {
  const at = state.selectedImageIndex;
  return Boolean(state.imageUris[at]) && state.imageUris[at] !== state.bgRemovedUris[at];
}
