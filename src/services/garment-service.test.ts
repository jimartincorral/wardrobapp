import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDatabaseMock = vi.fn();
const deleteImageMock = vi.fn(async (_uri: string) => {});
const removeGarmentFromOutfitsMock = vi.fn(async (_garmentId: string) => {});

vi.mock('../db/client', () => ({
  getDatabase: getDatabaseMock,
}));

vi.mock('./image-service', () => ({
  deleteImage: deleteImageMock,
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

    const deleted = deleteImageMock.mock.calls.map(call => call[0]).sort();
    expect(deleted).toEqual(['file://back.jpg', 'file://front.jpg', 'file://front_nobg.png']);
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

    expect(deleteImageMock.mock.calls.map(call => call[0])).toEqual(['file://front.jpg']);
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
