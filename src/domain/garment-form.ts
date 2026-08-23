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
/** Which file a photo slot contributes, and what it is. */
export interface SlotSource {
  source: string;
  /**
   * True when the source is a cut-out, which is what makes it belong in *both*
   * columns rather than only the background-removed one.
   */
  isCutout: boolean;
}

/**
 * What each photo slot contributes to the stored garment.
 *
 * The one rule the apps share: a slot whose background was removed contributes
 * its cut-out and nothing else. Saving space is the whole point of removing a
 * background, so keeping the original alongside would make every removal cost
 * more storage rather than less.
 *
 * Only the decision is here. What the two apps then do with it differs -- React
 * Native persists the file at save time, the port persisted it when it was picked
 * -- which is exactly why the decision is worth having in one place and the
 * mechanics are not.
 */
/** The photo lists, which is all either of these functions reads. */
type PhotoSlots = Pick<GarmentFormState, 'imageUris' | 'bgRemovedUris'>;

export function slotSources(state: PhotoSlots): SlotSource[] {
  return state.imageUris.map((original, index) => {
    const cutout = state.bgRemovedUris[index] ?? '';

    return cutout ? { source: cutout, isCutout: true } : { source: original, isCutout: false };
  });
}

/** What a garment's photo columns should hold, and what is left over. */
export interface ImagesToStore {
  imageUris: string[];
  bgRemovedUris: string[];
  /**
   * Originals that nothing points at any more, safe to delete.
   *
   * Returned rather than deleted here, because deciding and doing are different
   * jobs and only the deciding is testable without a filesystem.
   */
  discardable: string[];
}

/**
 * Collapse the form's photos into what gets stored.
 *
 * A slot whose background was removed stores the cut-out in *both* columns and
 * lets the original go: saving space is the whole point of removing it, and
 * keeping both would mean every removal costing more storage rather than less.
 *
 * Both mistakes here are quiet ones. Discard a file something still points at
 * and the garment shows a gap where a photo was; miss one and it sits on the
 * phone forever with nothing referring to it. So this decides, and returns the
 * list to act on, rather than doing it.
 *
 * Idempotent on purpose: editing a garment whose photo was already collapsed
 * runs it again, and the second run must find nothing to discard.
 */
export function imagesToStore(state: PhotoSlots): ImagesToStore {
  const imageUris: string[] = [];
  const bgRemovedUris: string[] = [];
  const discardable: string[] = [];

  slotSources(state).forEach(({ source, isCutout }, index) => {
    imageUris.push(source);
    bgRemovedUris.push(isCutout ? source : '');

    // Only when the original is genuinely a different file: after a previous
    // collapse the two are the same path, and discarding it would delete the
    // photo the garment is showing.
    const original = state.imageUris[index];
    if (isCutout && original && original !== source) discardable.push(original);
  });

  return { imageUris, bgRemovedUris, discardable };
}

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
