-- A fresh install: CREATE TABLE, then the indexes.

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

CREATE INDEX IF NOT EXISTS idx_garments_category ON garments(category);

CREATE INDEX IF NOT EXISTS idx_garments_available ON garments(is_available);

CREATE INDEX IF NOT EXISTS idx_outfit_ratings_outfit ON outfit_ratings(outfit_id);

CREATE INDEX IF NOT EXISTS idx_pair_scores_score ON garment_pair_scores(score DESC);
