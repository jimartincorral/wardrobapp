import { beforeEach, describe, expect, it, vi } from 'vitest';

const IMAGE_DIR = 'file:///data/user/0/app/files/garment-images/';

const getDatabaseMock = vi.fn();
const deleteImageMock = vi.fn(async (_uri: string) => {});
const removeGarmentFromOutfitsMock = vi.fn(async (_garmentId: string) => {});

vi.mock('../db/client', () => ({
  getDatabase: getDatabaseMock,
}));

vi.mock('./image-service', () => ({
  deleteImage: deleteImageMock,
  // The read path resolves stored filenames against this; a fixed value keeps
  // the assertions below independent of any real documents directory.
  getGarmentImageDirectory: () => IMAGE_DIR,
}));

vi.mock('./outfit-service', () => ({
  removeGarmentFromOutfits: removeGarmentFromOutfitsMock,
}));

vi.mock('expo-crypto', () => ({
  randomUUID: () => 'uuid',
}));

function createDb(row: Record<string, unknown> | null) {
  return {
    execAsync: vi.fn(),
    closeAsync: vi.fn(),
    runAsync: vi.fn(async (_sql: string, ..._params: any[]) => {}),
    getAllAsync: vi.fn(async (_sql: string, ..._params: any[]) => []),
    getFirstAsync: vi.fn(async (_sql: string, ..._params: any[]) => row),
  };
}

const garmentRow = {
  id: 'garment-1',
  image_uri: 'file://front.jpg',
  image_uri_nobg: 'file://front_nobg.png',
  image_uris: JSON.stringify(['file://front.jpg', 'file://back.jpg']),
  image_uris_nobg: JSON.stringify(['file://front_nobg.png', '']),
  category: 'tops',
  subcategory: 'T-Shirt',
  subcategories: JSON.stringify(['T-Shirt']),
  tags: JSON.stringify([]),
  brand: null,
  color_primary: '#000000',
  color_secondary: null,
  color_palette: JSON.stringify(['#000000']),
  size: null,
  purchase_date: null,
  is_available: 1,
  unavailable_date: null,
  created_at: '2026-04-11T00:00:00.000Z',
  updated_at: '2026-04-11T00:00:00.000Z',
};

describe('getAllGarments', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('searches size alongside brand, tags and subcategories', async () => {
    const db = createDb(null);
    getDatabaseMock.mockResolvedValue(db);

    const { getAllGarments } = await import('./garment-service');
    await getAllGarments({ search: 'XL' });

    const [sql, ...params] = db.getAllAsync.mock.calls[0];
    expect(sql).toContain('size LIKE ?');
    // One bound term per searched column — a mismatch here shifts every
    // placeholder and silently corrupts the filter.
    expect(params).toEqual(['%XL%', '%XL%', '%XL%', '%XL%', '%XL%']);
  });
});

describe('deleteGarment', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('deletes the row, its pair scores, its outfit references and its image files', async () => {
    const db = createDb(garmentRow);
    getDatabaseMock.mockResolvedValue(db);

    const { deleteGarment } = await import('./garment-service');
    await deleteGarment('garment-1');

    const statements = db.runAsync.mock.calls.map(call => call[0] as string);
    expect(statements.some(sql => /DELETE FROM garments WHERE id = \?/.test(sql))).toBe(true);

    const pairScoreDelete = db.runAsync.mock.calls.find(call =>
      /DELETE FROM garment_pair_scores/.test(call[0] as string)
    );
    expect(pairScoreDelete).toBeDefined();
    // Matched against both columns, since the pair key is stored sorted.
    expect(pairScoreDelete!.slice(1)).toEqual(['garment-1', 'garment-1']);

    expect(removeGarmentFromOutfitsMock).toHaveBeenCalledWith('garment-1');

    // Resolved against the image directory, so the paths handed to deleteImage
    // are ones the filesystem can actually find.
    const deleted = deleteImageMock.mock.calls.map(call => call[0]).sort();
    expect(deleted).toEqual([
      `${IMAGE_DIR}back.jpg`,
      `${IMAGE_DIR}front.jpg`,
      `${IMAGE_DIR}front_nobg.png`,
    ]);
  });

  it('deletes each file once when the cover duplicates the first image', async () => {
    const db = createDb({
      ...garmentRow,
      image_uris: JSON.stringify(['file://front.jpg']),
      image_uris_nobg: JSON.stringify(['']),
      image_uri_nobg: null,
    });
    getDatabaseMock.mockResolvedValue(db);

    const { deleteGarment } = await import('./garment-service');
    await deleteGarment('garment-1');

    expect(deleteImageMock.mock.calls.map(call => call[0])).toEqual([`${IMAGE_DIR}front.jpg`]);
  });

  it('still clears the row when the garment is already gone', async () => {
    const db = createDb(null);
    getDatabaseMock.mockResolvedValue(db);

    const { deleteGarment } = await import('./garment-service');
    await expect(deleteGarment('missing')).resolves.toBeUndefined();

    expect(deleteImageMock).not.toHaveBeenCalled();
    expect(removeGarmentFromOutfitsMock).toHaveBeenCalledWith('missing');
  });
});

describe('portable image references', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const newGarment = {
    image_uri: 'file:///data/user/0/app/files/garment-images/front.jpg',
    image_uri_nobg: 'file:///data/user/0/app/files/garment-images/front_nobg.png',
    image_uris: [
      'file:///data/user/0/app/files/garment-images/front.jpg',
      'file:///data/user/0/app/files/garment-images/back.jpg',
    ],
    image_uris_nobg: ['file:///data/user/0/app/files/garment-images/front_nobg.png'],
    category: 'tops',
    subcategory: 'T-Shirt',
    subcategories: ['T-Shirt'],
    tags: [],
    brand: null,
    color_primary: '#000000',
    color_secondary: null,
    color_palette: ['#000000'],
    size: null,
    purchase_date: null,
  };

  it('stores photo filenames without their directory on create', async () => {
    // The documents directory is not stable across installs, so persisting the
    // absolute path is what breaks every photo after a restore.
    const db = createDb(null);
    getDatabaseMock.mockResolvedValue(db);

    const { createGarment } = await import('./garment-service');
    await createGarment(newGarment as any);

    const [, , imageUri, imageUriNobg, imageUris, imageUrisNobg] = db.runAsync.mock.calls[0];
    expect(imageUri).toBe('front.jpg');
    expect(imageUriNobg).toBe('front_nobg.png');
    expect(JSON.parse(imageUris as string)).toEqual(['front.jpg', 'back.jpg']);
    expect(JSON.parse(imageUrisNobg as string)).toEqual(['front_nobg.png']);
  });

  it('stores photo filenames without their directory on update', async () => {
    // Reads hand back resolved absolute URIs, and the garment screen writes
    // those straight back, so the reduction has to happen here too.
    const db = createDb(null);
    getDatabaseMock.mockResolvedValue(db);

    const { updateGarment } = await import('./garment-service');
    await updateGarment('garment-1', {
      image_uri: 'file:///data/user/0/app/files/garment-images/front.jpg',
      image_uris: ['file:///data/user/0/app/files/garment-images/front.jpg'],
    });

    const [sql, ...params] = db.runAsync.mock.calls[0];
    expect(sql).toMatch(/UPDATE garments SET/);
    expect(params).toContain('front.jpg');
    expect(params.some(p => typeof p === 'string' && p.includes('garment-images/'))).toBe(false);
  });

  it('keeps inline data references intact on create', async () => {
    const dataUri = 'data:image/jpeg;base64,AAA/BBB';
    const db = createDb(null);
    getDatabaseMock.mockResolvedValue(db);

    const { createGarment } = await import('./garment-service');
    await createGarment({
      ...newGarment,
      image_uri: dataUri,
      image_uri_nobg: null,
      image_uris: [dataUri],
      image_uris_nobg: [],
    } as any);

    const [, , imageUri] = db.runAsync.mock.calls[0];
    expect(imageUri).toBe(dataUri);
  });
});
