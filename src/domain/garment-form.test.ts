import { describe, expect, it } from 'vitest';
import {
  EMPTY_FORM,
  brandSuggestions,
  displayedPreviewUri,
  galleryItems,
  normalizeForm,
  selectedHasOriginal,
  toggled,
  withBackgroundRemoved,
  withDetectedColor,
  withImage,
  withImagesReordered,
  withImportedPreview,
  withSubcategories,
  withoutImageAt,
  type GarmentFormState,
} from './garment-form';
import type { SeasonOption } from '../constants/style-filters';

const form = (overrides: Partial<GarmentFormState> = {}): GarmentFormState =>
  normalizeForm({ ...overrides });

// The invariant behind most of this: bgRemovedUris is positionally aligned with
// imageUris, so entry i is the cut-out of photo i.
const aligned = (state: GarmentFormState) =>
  state.bgRemovedUris.length === state.imageUris.length;

describe('normalizeForm', () => {
  it('aligns the cut-out list with the photos', () => {
    const state = normalizeForm({ imageUris: ['a', 'b', 'c'], bgRemovedUris: ['a-nobg'] });

    expect(state.bgRemovedUris).toEqual(['a-nobg', '', '']);
    expect(aligned(state)).toBe(true);
  });

  it('trims a cut-out list longer than the photos', () => {
    const state = normalizeForm({ imageUris: ['a'], bgRemovedUris: ['x', 'y', 'z'] });
    expect(state.bgRemovedUris).toEqual(['x']);
  });

  it('falls back to a default palette rather than an empty one', () => {
    expect(normalizeForm({ colorPalette: [] }).colorPalette).toEqual(['#000000']);
    expect(normalizeForm({ colorPalette: ['#FFFFFF'] }).colorPalette).toEqual(['#FFFFFF']);
  });
});

describe('withImage', () => {
  it('appends and selects the new photo', () => {
    const state = withImage(form({ imageUris: ['a'], bgRemovedUris: ['a-nobg'] }), 'b');

    expect(state.imageUris).toEqual(['a', 'b']);
    expect(state.bgRemovedUris).toEqual(['a-nobg', '']);
    expect(state.selectedImageIndex).toBe(1);
    expect(aligned(state)).toBe(true);
  });

  it('replacing the selected photo drops its cut-out', () => {
    // The old cut-out was of the old photo, so keeping it would show the wrong
    // garment.
    const before = form({ imageUris: ['a', 'b'], bgRemovedUris: ['a-nobg', 'b-nobg'], selectedImageIndex: 1 });
    const state = withImage(before, 'b2', true);

    expect(state.imageUris).toEqual(['a', 'b2']);
    expect(state.bgRemovedUris).toEqual(['a-nobg', '']);
    expect(state.selectedImageIndex).toBe(1);
  });

  it('appends when asked to replace but there is nothing selected', () => {
    const state = withImage(form(), 'a', true);
    expect(state.imageUris).toEqual(['a']);
  });
});

describe('withoutImageAt', () => {
  it('removes the photo and its cut-out together', () => {
    const state = withoutImageAt(
      form({ imageUris: ['a', 'b', 'c'], bgRemovedUris: ['a1', 'b1', 'c1'] }),
      1
    );

    expect(state.imageUris).toEqual(['a', 'c']);
    expect(state.bgRemovedUris).toEqual(['a1', 'c1']);
    expect(aligned(state)).toBe(true);
  });

  it('shifts the selection down when an earlier photo goes', () => {
    const state = withoutImageAt(
      form({ imageUris: ['a', 'b', 'c'], selectedImageIndex: 2 }),
      0
    );
    // Still pointing at 'c'.
    expect(state.imageUris[state.selectedImageIndex]).toBe('c');
  });

  it('keeps the selection in range when the last photo goes', () => {
    const state = withoutImageAt(
      form({ imageUris: ['a', 'b'], selectedImageIndex: 1 }),
      1
    );
    expect(state.selectedImageIndex).toBe(0);
    expect(state.imageUris[state.selectedImageIndex]).toBe('a');
  });

  it('resets the selection when nothing is left', () => {
    const state = withoutImageAt(form({ imageUris: ['a'], selectedImageIndex: 0 }), 0);
    expect(state.imageUris).toEqual([]);
    expect(state.selectedImageIndex).toBe(0);
  });

  it('never leaves the selection past the end', () => {
    // Whichever photo goes, the index must still address something.
    for (const removed of [0, 1, 2]) {
      for (const selected of [0, 1, 2]) {
        const state = withoutImageAt(
          form({ imageUris: ['a', 'b', 'c'], selectedImageIndex: selected }),
          removed
        );
        expect(state.selectedImageIndex).toBeLessThan(state.imageUris.length);
        expect(aligned(state)).toBe(true);
      }
    }
  });
});

describe('withImagesReordered', () => {
  it('moves the photo and its cut-out together, and follows it', () => {
    const state = withImagesReordered(
      form({ imageUris: ['a', 'b', 'c'], bgRemovedUris: ['a1', 'b1', 'c1'] }),
      0,
      2
    );

    expect(state.imageUris).toEqual(['b', 'c', 'a']);
    expect(state.bgRemovedUris).toEqual(['b1', 'c1', 'a1']);
    expect(state.selectedImageIndex).toBe(2);
  });

  it('does nothing for a no-op or an out-of-range move', () => {
    const before = form({ imageUris: ['a', 'b'] });

    expect(withImagesReordered(before, 1, 1)).toBe(before);
    expect(withImagesReordered(before, 0, 5)).toBe(before);
    expect(withImagesReordered(before, -1, 0)).toBe(before);
  });
});

describe('withBackgroundRemoved', () => {
  it('records the cut-out against the selected photo only', () => {
    const state = withBackgroundRemoved(
      form({ imageUris: ['a', 'b'], selectedImageIndex: 1 }),
      'b-nobg'
    );
    expect(state.bgRemovedUris).toEqual(['', 'b-nobg']);
  });
});

describe('withSubcategories', () => {
  const seasonsFor = (): SeasonOption[] => ['winter'];

  it('fills in seasons when the user has chosen none', () => {
    const state = withSubcategories(form(), ['Parka'], seasonsFor);
    expect(state.seasons).toEqual(['winter']);
  });

  it('never overwrites seasons the user chose', () => {
    const state = withSubcategories(form({ seasons: ['summer'] }), ['Parka'], seasonsFor);
    expect(state.seasons).toEqual(['summer']);
  });
});

describe('withDetectedColor', () => {
  it('puts the detection first and keeps the rest', () => {
    // A detection is a suggestion, not a correction: a deliberate choice stays.
    const state = withDetectedColor(form({ colorPalette: ['#FFFFFF', '#CC0000'] }), '#000080');
    expect(state.colorPalette).toEqual(['#000080', '#FFFFFF', '#CC0000']);
  });

  it('does not duplicate a colour already in the palette', () => {
    const state = withDetectedColor(form({ colorPalette: ['#FFFFFF', '#CC0000'] }), '#CC0000');
    expect(state.colorPalette).toEqual(['#CC0000', '#FFFFFF']);
  });
});

describe('withImportedPreview', () => {
  it('keeps a brand the user has already typed', () => {
    const state = withImportedPreview(
      form({ brand: 'Mine' }),
      { downloadedImageUris: ['a'], brand: 'Theirs' }
    );
    expect(state.brand).toBe('Mine');
  });

  it('takes the imported brand when the field is blank', () => {
    const state = withImportedPreview(
      form({ brand: '   ' }),
      { downloadedImageUris: ['a', 'b'], brand: 'Theirs' }
    );
    expect(state.brand).toBe('Theirs');
    expect(state.bgRemovedUris).toEqual(['', '']);
    expect(state.selectedImageIndex).toBe(0);
  });
});

describe('brandSuggestions', () => {
  const known = ['Uniqlo', 'Nike', 'New Balance', 'Adidas'];

  it('offers everything when nothing has been typed', () => {
    expect(brandSuggestions(known, '')).toEqual(known);
    expect(brandSuggestions(known, '   ')).toEqual(known);
  });

  it('matches case-insensitively anywhere in the name, not just the start', () => {
    // 'ni' is inside U-ni-qlo as well as starting Nike, and both are offered:
    // the match is a substring, not a prefix.
    expect(brandSuggestions(known, 'ni')).toEqual(['Uniqlo', 'Nike']);
    expect(brandSuggestions(known, 'BALANCE')).toEqual(['New Balance']);
    expect(brandSuggestions(known, 'qlo')).toEqual(['Uniqlo']);
  });

  it('drops an exact match, which the user has already typed', () => {
    expect(brandSuggestions(known, 'Nike')).toEqual([]);
    expect(brandSuggestions(known, '  nike  ')).toEqual([]);
  });

  it('caps the list', () => {
    const many = Array.from({ length: 20 }, (_, i) => `Brand${i}`);
    expect(brandSuggestions(many, '')).toHaveLength(8);
  });
});

describe('derived view values', () => {
  it('shows the cut-out where there is one, else the photo', () => {
    const state = form({ imageUris: ['a', 'b'], bgRemovedUris: ['a-nobg', ''] });

    expect(galleryItems(state)).toEqual([
      { uri: 'a-nobg', original: 'a' },
      { uri: 'b', original: 'b' },
    ]);
    expect(displayedPreviewUri(state)).toBe('a-nobg');
    expect(displayedPreviewUri({ ...state, selectedImageIndex: 1 })).toBe('b');
  });

  it('reports no preview for an empty form', () => {
    expect(displayedPreviewUri(EMPTY_FORM)).toBeNull();
    expect(galleryItems(EMPTY_FORM)).toEqual([]);
  });

  it('knows a cut-out-only garment has nothing to undo to', () => {
    // Both slots hold the same path, so there is no with-background original.
    const cutoutOnly = form({ imageUris: ['only.png'], bgRemovedUris: ['only.png'] });
    expect(selectedHasOriginal(cutoutOnly)).toBe(false);

    const hasBoth = form({ imageUris: ['a.jpg'], bgRemovedUris: ['a-nobg.png'] });
    expect(selectedHasOriginal(hasBoth)).toBe(true);
  });
});

describe('toggled', () => {
  it('adds what is absent and removes what is present', () => {
    expect(toggled(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggled(['a', 'b'], 'a')).toEqual(['b']);
  });
});
