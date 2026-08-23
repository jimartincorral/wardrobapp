import { describe, expect, it } from 'vitest';
import { getGarmentDisplayImage, normalizeGarmentRow } from './garment-fields';

const DIR = 'file:///var/mobile/Containers/Data/Application/NEW/Documents/garment-images/';

const row = {
  id: 'g1',
  image_uri: 'front.jpg',
  image_uri_nobg: 'front_nobg.png',
  image_uris: JSON.stringify(['front.jpg', 'back.jpg']),
  image_uris_nobg: JSON.stringify(['front_nobg.png']),
  category: 'tops',
  subcategory: 'T-Shirt',
  subcategories: JSON.stringify(['T-Shirt']),
  tags: JSON.stringify([]),
  color_primary: '#000000',
  color_palette: JSON.stringify(['#000000']),
  is_available: 1,
  created_at: '2026-04-11T00:00:00.000Z',
  updated_at: '2026-04-11T00:00:00.000Z',
};

describe('normalizeGarmentRow image resolution', () => {
  it('attaches the current directory to stored filenames', async () => {
    const garment = normalizeGarmentRow(row, DIR);

    expect(garment.image_uri).toBe(`${DIR}front.jpg`);
    expect(garment.image_uri_nobg).toBe(`${DIR}front_nobg.png`);
    expect(garment.image_uris).toEqual([`${DIR}front.jpg`, `${DIR}back.jpg`]);
    expect(garment.image_uris_nobg).toEqual([`${DIR}front_nobg.png`]);
  });

  it('re-bases rows written by older builds onto the current directory', async () => {
    // These rows hold a container path that no longer exists, so reads have to
    // repair them whether or not the migration has run yet.
    const legacy = {
      ...row,
      image_uri: 'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/front.jpg',
      image_uris: JSON.stringify([
        'file:///var/mobile/Containers/Data/Application/OLD/Documents/garment-images/front.jpg',
      ]),
      image_uri_nobg: null,
      image_uris_nobg: JSON.stringify([]),
    };

    const garment = normalizeGarmentRow(legacy, DIR);

    expect(garment.image_uri).toBe(`${DIR}front.jpg`);
    expect(garment.image_uris).toEqual([`${DIR}front.jpg`]);
  });

  it('leaves inline data references untouched', async () => {
    const dataUri = 'data:image/jpeg;base64,AAA/BBB';
    const garment = normalizeGarmentRow(
      { ...row, image_uri: dataUri, image_uris: JSON.stringify([dataUri]) },
      ''
    );

    expect(garment.image_uri).toBe(dataUri);
    expect(garment.image_uris).toEqual([dataUri]);
  });

  it('keeps a null nobg reference null rather than resolving it to the directory', async () => {
    const garment = normalizeGarmentRow(
      { ...row, image_uri_nobg: null, image_uris_nobg: JSON.stringify([]) },
      DIR
    );

    expect(garment.image_uri_nobg).toBeNull();
    expect(garment.image_uris_nobg).toEqual([]);
  });

  it('prefers a resolved background-removed image for display', async () => {
    expect(getGarmentDisplayImage(normalizeGarmentRow(row, DIR))).toBe(`${DIR}front_nobg.png`);
  });

  it('coerces a non-string scalar column rather than passing it through', () => {
    // The row is `any`, so a number can reach a field typed `string | null`.
    // duplicate-detection calls `size?.trim()`, and optional chaining guards
    // null but not a missing method, so a numeric size used to throw.
    const garment = normalizeGarmentRow(
      { id: 7, category: 'tops', image_uri: 'a.jpg', size: 10, brand: 99 },
      ''
    );

    expect(garment.id).toBe('7');
    expect(garment.size).toBe('10');
    expect(garment.brand).toBe('99');
    expect(() => garment.size?.trim()).not.toThrow();
  });

  it('keeps an absent scalar column absent', () => {
    const garment = normalizeGarmentRow(
      { id: 'g', category: 'tops', image_uri: 'a.jpg', size: null },
      ''
    );

    expect(garment.size).toBeNull();
    expect(garment.brand).toBeNull();
  });
});
