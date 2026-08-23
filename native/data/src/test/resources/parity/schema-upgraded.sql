-- An install old enough to predate every additive ALTER.

CREATE TABLE garments (
      id TEXT PRIMARY KEY,
      image_uri TEXT NOT NULL,
      category TEXT NOT NULL
    );

CREATE TABLE outfits (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      garment_ids TEXT NOT NULL DEFAULT '[]',
      occasion TEXT,
      season TEXT,
      created_at TEXT NOT NULL,
      is_suggested INTEGER NOT NULL DEFAULT 0
    );

-- Then the schema as applied on every start. Statements failing because

-- the column exists are expected and ignored, exactly as the app does.

-- Columns are added before the indexes over them, which is the order the

-- app applies -- the other way round throws on an install this old.

CREATE TABLE IF NOT EXISTS garments (
      id TEXT PRIMARY KEY,
      image_uri TEXT NOT NULL,
      image_uri_nobg TEXT,
      image_uris TEXT NOT NULL DEFAULT '[]',
      image_uris_nobg TEXT NOT NULL DEFAULT '[]',
      category TEXT NOT NULL,
      subcategory TEXT,
      subcategories TEXT NOT NULL DEFAULT '[]',
      tags TEXT NOT NULL DEFAULT '[]',
      brand TEXT,
      color_primary TEXT NOT NULL DEFAULT '#000000',
      color_secondary TEXT,
      color_palette TEXT NOT NULL DEFAULT '[]',
      size TEXT,
      purchase_date TEXT,
      is_available INTEGER NOT NULL DEFAULT 1,
      unavailable_date TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS outfits (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      garment_ids TEXT NOT NULL DEFAULT '[]',
      occasion TEXT,
      season TEXT,
      created_at TEXT NOT NULL,
      is_suggested INTEGER NOT NULL DEFAULT 0,
      is_pinned INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS outfit_ratings (
      id TEXT PRIMARY KEY,
      outfit_id TEXT NOT NULL,
      rating INTEGER NOT NULL,
      feedback TEXT,
      rated_at TEXT NOT NULL,
      FOREIGN KEY (outfit_id) REFERENCES outfits(id) ON DELETE CASCADE
    );

    CREATE TABLE IF NOT EXISTS garment_pair_scores (
      garment_id_a TEXT NOT NULL,
      garment_id_b TEXT NOT NULL,
      score REAL NOT NULL DEFAULT 0,
      wear_count INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (garment_id_a, garment_id_b)
    );

    CREATE TABLE IF NOT EXISTS user_preferences (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );

ALTER TABLE outfits ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0;

ALTER TABLE garments ADD COLUMN image_uri_nobg TEXT;

ALTER TABLE garments ADD COLUMN image_uris TEXT NOT NULL DEFAULT '[]';

ALTER TABLE garments ADD COLUMN image_uris_nobg TEXT NOT NULL DEFAULT '[]';

ALTER TABLE garments ADD COLUMN subcategory TEXT;

ALTER TABLE garments ADD COLUMN subcategories TEXT NOT NULL DEFAULT '[]';

ALTER TABLE garments ADD COLUMN tags TEXT NOT NULL DEFAULT '[]';

ALTER TABLE garments ADD COLUMN brand TEXT;

ALTER TABLE garments ADD COLUMN color_primary TEXT NOT NULL DEFAULT '#000000';

ALTER TABLE garments ADD COLUMN color_secondary TEXT;

ALTER TABLE garments ADD COLUMN color_palette TEXT NOT NULL DEFAULT '[]';

ALTER TABLE garments ADD COLUMN size TEXT;

ALTER TABLE garments ADD COLUMN purchase_date TEXT;

ALTER TABLE garments ADD COLUMN is_available INTEGER NOT NULL DEFAULT 1;

ALTER TABLE garments ADD COLUMN unavailable_date TEXT;

ALTER TABLE garments ADD COLUMN created_at TEXT;

ALTER TABLE garments ADD COLUMN updated_at TEXT;

CREATE INDEX IF NOT EXISTS idx_garments_category ON garments(category);

CREATE INDEX IF NOT EXISTS idx_garments_available ON garments(is_available);

CREATE INDEX IF NOT EXISTS idx_outfit_ratings_outfit ON outfit_ratings(outfit_id);

CREATE INDEX IF NOT EXISTS idx_pair_scores_score ON garment_pair_scores(score DESC);
