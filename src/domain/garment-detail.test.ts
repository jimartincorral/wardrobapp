import { describe, expect, it } from 'vitest';
import { garmentDetail } from './garment-detail';
import type { Garment } from '../types';

const garment = (overrides: Partial<Garment>): Garment => ({
  id: 'g',
  image_uri: 'front.jpg',
  image_uri_nobg: null,
  image_uris: ['front.jpg'],
  image_uris_nobg: [],
  category: 'tops',
  subcategory: 'T-Shirt',
  subcategories: ['T-Shirt'],
  tags: [],
  brand: null,
  color_primary: '#000000',
  color_secondary: null,
  color_palette: ['#000000'],
  size: 'M',
  purchase_date: null,
  is_available: true,
  unavailable_date: null,
  created_at: '2026-01-01T00:00:00.000Z',
  updated_at: '2026-01-01T00:00:00.000Z',
  ...overrides,
});

describe('which photo is shown', () => {
  it('prefers the cut-out for the selected photo', () => {
    const view = garmentDetail(
      garment({ image_uris: ['a.jpg', 'b.jpg'], image_uris_nobg: ['', 'b-cut.png'] }),
      1
    );

    expect(view.displayedImage).toBe('b-cut.png');
    expect(view.gallery.map(g => g.uri)).toEqual(['a.jpg', 'b-cut.png']);
  });

  it('falls back to the original when that photo has no cut-out', () => {
    const view = garmentDetail(
      garment({ image_uris: ['a.jpg', 'b.jpg'], image_uris_nobg: ['a-cut.png', ''] }),
      1
    );

    expect(view.displayedImage).toBe('b.jpg');
  });

  it('has nothing to show for a garment with no photos', () => {
    const view = garmentDetail(garment({ image_uri: '', image_uris: [], image_uris_nobg: [] }));

    expect(view.displayedImage).toBeNull();
    expect(view.gallery).toEqual([]);
    expect(view.showsGallery).toBe(false);
  });

  it('offers the strip only when there is a choice', () => {
    expect(garmentDetail(garment({ image_uris: ['a.jpg'] })).showsGallery).toBe(false);
    expect(garmentDetail(garment({ image_uris: ['a.jpg', 'b.jpg'] })).showsGallery).toBe(true);
  });

  it('clamps a selection past the end rather than showing nothing selected', () => {
    // A remembered index, or a garment whose photos were edited since. Reading
    // past the end used to fall through to the first photo while the strip
    // showed none of them selected, so the screen disagreed with itself.
    const view = garmentDetail(garment({ image_uris: ['a.jpg', 'b.jpg'] }), 7);

    expect(view.selectedIndex).toBe(0);
    expect(view.displayedImage).toBe('a.jpg');
    expect(view.gallery.map(g => g.selected)).toEqual([true, false]);
  });

  it('clamps a negative selection', () => {
    const view = garmentDetail(garment({ image_uris: ['a.jpg', 'b.jpg'] }), -1);

    expect(view.selectedIndex).toBe(0);
    expect(view.gallery.map(g => g.selected)).toEqual([true, false]);
  });

  it('marks which photos have a cut-out', () => {
    const view = garmentDetail(
      garment({ image_uris: ['a.jpg', 'b.jpg'], image_uris_nobg: ['a-cut.png', ''] })
    );

    expect(view.gallery.map(g => g.hasCutout)).toEqual([true, false]);
  });
});

describe('the background-removal button', () => {
  it('offers removal for a photo that has no cut-out', () => {
    expect(garmentDetail(garment({ image_uris: ['a.jpg'] })).backgroundAction).toBe('remove');
  });

  it('offers undo only while a separate original still exists', () => {
    const legacy = garment({ image_uris: ['a.jpg'], image_uris_nobg: ['a-cut.png'] });

    expect(garmentDetail(legacy).backgroundAction).toBe('undo');
  });

  it('offers nothing once the cut-out has replaced the original', () => {
    // Removal now overwrites the photo it came from, so there is nothing to
    // revert to -- and an undo button here would destroy the only copy.
    const replaced = garment({ image_uris: ['a-cut.png'], image_uris_nobg: ['a-cut.png'] });

    expect(garmentDetail(replaced).backgroundAction).toBeNull();
  });
});

describe('the palette', () => {
  it('names the colours that came from the picker', () => {
    const view = garmentDetail(garment({ color_palette: ['#000000', '#0066CC'] }));

    expect(view.palette).toEqual([
      { hex: '#000000', colorKey: 'black' },
      { hex: '#0066CC', colorKey: 'blue' },
    ]);
  });

  it('names a colour whatever case it was stored in', () => {
    // The wardrobe holds both cases -- the analytics grouping had to be fixed
    // for the same reason -- and '#cc0000' is the same red as '#CC0000'.
    const view = garmentDetail(garment({ color_palette: ['#cc0000'] }));

    expect(view.palette[0].colorKey).toBe('red');
  });

  it('leaves a colour that was not picked from the palette unnamed', () => {
    const view = garmentDetail(garment({ color_palette: ['#123456'] }));

    expect(view.palette).toEqual([{ hex: '#123456', colorKey: null }]);
  });

  it('names the multi-colour sentinel', () => {
    expect(garmentDetail(garment({ color_palette: ['#RAINBOW'] })).palette[0].colorKey).toBe('multi');
  });

  it('falls back to the primary and secondary columns', () => {
    const view = garmentDetail(
      garment({ color_palette: [], color_primary: '#FFFFFF', color_secondary: '#000080' })
    );

    expect(view.palette.map(p => p.colorKey)).toEqual(['white', 'navy']);
  });
});

describe('tags', () => {
  it('takes the seasons out of the tags', () => {
    const view = garmentDetail(garment({ tags: ['cotton', 'summer', 'striped'] }));

    expect(view.tags).toEqual(['cotton', 'striped']);
    expect(view.seasons).toEqual(['summer']);
  });

  it('drops tags that used to be structured filters', () => {
    // Old rows still carry them, and a restored backup reintroduces them, so
    // they must not surface as if the user had typed them.
    const view = garmentDetail(garment({ tags: ['formal', 'rainy', 'linen'] }));

    expect(view.tags).toEqual(['linen']);
  });

  it('lists seasons in the app order rather than the order they were typed', () => {
    const view = garmentDetail(garment({ tags: ['winter', 'spring', 'winter'] }));

    expect(view.seasons).toEqual(['spring', 'winter']);
  });
});

describe('the fields with nothing in them', () => {
  it('treats a blank brand or size as absent', () => {
    const view = garmentDetail(garment({ brand: '   ', size: '' }));

    expect(view.brand).toBeNull();
    expect(view.size).toBeNull();
  });

  it('trims a brand that has something in it', () => {
    expect(garmentDetail(garment({ brand: ' Nike ' })).brand).toBe('Nike');
  });

  it('reports an unavailable date only for a garment that is unavailable', () => {
    const gone = garmentDetail(
      garment({ is_available: false, unavailable_date: '2026-03-04T00:00:00.000Z' })
    );
    // A row can keep the date from a previous spell of being unavailable.
    const back = garmentDetail(
      garment({ is_available: true, unavailable_date: '2026-03-04T00:00:00.000Z' })
    );

    expect(gone.unavailableDate).toBe('2026-03-04T00:00:00.000Z');
    expect(back.unavailableDate).toBeNull();
  });
});

describe('occasions', () => {
  it('derives them from the garment type', () => {
    expect(garmentDetail(garment({ subcategories: ['Blazer'] })).occasions)
      .toEqual(['work', 'formal']);
  });

  it('falls back for a type it does not recognise', () => {
    expect(garmentDetail(garment({ category: 'loungewear', subcategories: ['Unknown'] })).occasions)
      .toEqual(['lounge']);
  });
});
