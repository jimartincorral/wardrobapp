import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDatabaseMock = vi.fn();

vi.mock('../db/client', () => ({
  getDatabase: getDatabaseMock,
}));

let uuidCounter = 0;
vi.mock('expo-crypto', () => ({
  randomUUID: () => `uuid-${++uuidCounter}`,
}));

type OutfitRow = {
  id: string;
  name: string;
  garment_ids: string;
  occasion: string | null;
  season: string | null;
  created_at: string;
  is_suggested: number;
  is_pinned: number;
};

type RatingRow = {
  id: string;
  outfit_id: string;
  rating: number;
  feedback: string | null;
  rated_at: string;
};

type PairScoreRow = {
  garment_id_a: string;
  garment_id_b: string;
  score: number;
  wear_count: number;
};

/**
 * Minimal stand-in for the database that actually stores rows, so tests can
 * assert on resulting state rather than on the SQL that was issued. Only the
 * statements outfit-service issues are handled — anything else throws, so a new
 * query can't silently no-op.
 */
function createFakeDb(seedOutfits: OutfitRow[] = []) {
  const outfits = [...seedOutfits];
  const ratings: RatingRow[] = [];
  const pairScores: PairScoreRow[] = [];

  const findPair = (a: string, b: string) =>
    pairScores.find(p => p.garment_id_a === a && p.garment_id_b === b);

  const db = {
    outfits,
    ratings,
    pairScores,
    execAsync: vi.fn(),
    closeAsync: vi.fn(),

    runAsync: vi.fn(async (sql: string, ...params: any[]) => {
      if (/^DELETE FROM outfit_ratings/.test(sql)) {
        const [outfitId] = params;
        for (let i = ratings.length - 1; i >= 0; i--) {
          if (ratings[i].outfit_id === outfitId) ratings.splice(i, 1);
        }
        return;
      }
      if (/^DELETE FROM outfits/.test(sql)) {
        const index = outfits.findIndex(o => o.id === params[0]);
        if (index >= 0) outfits.splice(index, 1);
        return;
      }
      if (/^INSERT INTO outfit_ratings/.test(sql)) {
        const [id, outfit_id, rating, feedback, rated_at] = params;
        ratings.push({ id, outfit_id, rating, feedback, rated_at });
        return;
      }
      if (/^INSERT INTO outfits/.test(sql)) {
        const [id, name, garment_ids, occasion, season, created_at, is_suggested, is_pinned] = params;
        outfits.push({ id, name, garment_ids, occasion, season, created_at, is_suggested, is_pinned });
        return;
      }
      if (/^UPDATE outfits SET garment_ids/.test(sql)) {
        const [garment_ids, id] = params;
        const outfit = outfits.find(o => o.id === id);
        if (outfit) outfit.garment_ids = garment_ids;
        return;
      }
      if (/^INSERT INTO garment_pair_scores/.test(sql)) {
        const [garment_id_a, garment_id_b, score] = params;
        pairScores.push({ garment_id_a, garment_id_b, score, wear_count: 1 });
        return;
      }
      if (/^UPDATE garment_pair_scores/.test(sql)) {
        const [score, wear_count, a, b] = params;
        const pair = findPair(a, b);
        if (pair) {
          pair.score = score;
          pair.wear_count = wear_count;
        }
        return;
      }
      throw new Error(`Unhandled runAsync: ${sql}`);
    }),

    getFirstAsync: vi.fn(async (sql: string, ...params: any[]) => {
      if (/SELECT rating FROM outfit_ratings/.test(sql)) {
        const newest = ratings
          .filter(r => r.outfit_id === params[0])
          .sort((a, b) => b.rated_at.localeCompare(a.rated_at))[0];
        return newest ? { rating: newest.rating } : null;
      }
      if (/FROM garment_pair_scores/.test(sql)) {
        return findPair(params[0], params[1]) ?? null;
      }
      if (/SELECT \* FROM outfits WHERE id/.test(sql)) {
        return outfits.find(o => o.id === params[0]) ?? null;
      }
      throw new Error(`Unhandled getFirstAsync: ${sql}`);
    }),

    getAllAsync: vi.fn(async (sql: string, ...params: any[]) => {
      if (/FROM outfit_ratings/.test(sql)) {
        return ratings
          .filter(r => r.outfit_id === params[0])
          .sort((a, b) => b.rated_at.localeCompare(a.rated_at));
      }
      if (/FROM outfits/.test(sql)) return [...outfits];
      throw new Error(`Unhandled getAllAsync: ${sql}`);
    }),
  };

  return db;
}

function outfitRow(id: string, garmentIds: string[]): OutfitRow {
  return {
    id,
    name: id,
    garment_ids: JSON.stringify(garmentIds),
    occasion: null,
    season: null,
    created_at: '2026-04-11T00:00:00.000Z',
    is_suggested: 0,
    is_pinned: 0,
  };
}

describe('rateOutfit', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces the previous rating instead of appending a second one', async () => {
    const db = createFakeDb([outfitRow('outfit-1', ['a', 'b'])]);
    getDatabaseMock.mockResolvedValue(db);

    const { rateOutfit } = await import('./outfit-service');
    await rateOutfit('outfit-1', 3);
    await rateOutfit('outfit-1', 5);

    expect(db.ratings).toHaveLength(1);
    expect(db.ratings[0].rating).toBe(5);
  });

  it('corrects pair scores rather than training on both ratings', async () => {
    const corrected = createFakeDb([outfitRow('outfit-1', ['a', 'b'])]);
    getDatabaseMock.mockResolvedValue(corrected);

    const { rateOutfit } = await import('./outfit-service');
    await rateOutfit('outfit-1', 5);
    await rateOutfit('outfit-1', 1);

    // Rating 5 then correcting to 1 must land exactly where rating 1 alone would.
    const direct = createFakeDb([outfitRow('outfit-2', ['a', 'b'])]);
    getDatabaseMock.mockResolvedValue(direct);
    await rateOutfit('outfit-2', 1);

    expect(corrected.pairScores).toHaveLength(1);
    expect(corrected.pairScores[0].score).toBeCloseTo(direct.pairScores[0].score, 10);
  });

  it('counts a wear for a new rating but not for a correction', async () => {
    const db = createFakeDb([outfitRow('outfit-1', ['a', 'b'])]);
    getDatabaseMock.mockResolvedValue(db);

    const { rateOutfit } = await import('./outfit-service');
    await rateOutfit('outfit-1', 5);
    expect(db.pairScores[0].wear_count).toBe(1);

    await rateOutfit('outfit-1', 2);
    expect(db.pairScores[0].wear_count).toBe(1);
  });
});

describe('removeGarmentFromOutfits', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('strips the garment from outfits that survive and deletes ones left empty', async () => {
    const db = createFakeDb([
      outfitRow('keeps-going', ['gone', 'stays']),
      outfitRow('only-garment', ['gone']),
      outfitRow('unrelated', ['other']),
    ]);
    getDatabaseMock.mockResolvedValue(db);

    const { removeGarmentFromOutfits } = await import('./outfit-service');
    await removeGarmentFromOutfits('gone');

    expect(db.outfits.map(o => o.id)).toEqual(['keeps-going', 'unrelated']);
    expect(JSON.parse(db.outfits[0].garment_ids)).toEqual(['stays']);
    expect(JSON.parse(db.outfits[1].garment_ids)).toEqual(['other']);
  });

  it('removes the ratings of outfits it deletes', async () => {
    const db = createFakeDb([outfitRow('only-garment', ['gone'])]);
    getDatabaseMock.mockResolvedValue(db);

    const { rateOutfit, removeGarmentFromOutfits } = await import('./outfit-service');
    await rateOutfit('only-garment', 4);
    expect(db.ratings).toHaveLength(1);

    await removeGarmentFromOutfits('gone');
    expect(db.outfits).toHaveLength(0);
    expect(db.ratings).toHaveLength(0);
  });
});
