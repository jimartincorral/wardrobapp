package com.wardrobapp.data

/**
 * The wardrobe schema, applied on every open.
 *
 * Transcribed from `src/db/schema.ts` in the app this replaced, which is why it
 * is shaped the way it is: there is no `PRAGMA user_version`, so the DDL *is*
 * the schema, and every database out there was created by one of these
 * statements. WardrobeSchemaTest holds the two populations that produces to each
 * other -- a fresh install and one carried forward by the ALTERs below -- so a
 * missing ALTER fails there rather than as a column absent on somebody's
 * phone.
 *
 * Order matters: columns are added before the indexes over them. The other way
 * round throws on an install old enough to predate `is_available`, and index
 * creation is deliberately not swallowed the way the ALTERs are.
 */
object WardrobeSchema {

    /** Creates whatever is missing. Safe to run against a populated database. */
    val CREATE_TABLES: String = """
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
      is_pinned INTEGER NOT NULL DEFAULT 0,
      is_archived INTEGER NOT NULL DEFAULT 0
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
    );"""

    /**
     * Columns added to installs that predate them. Each is attempted and its
     * failure ignored, because "the column already exists" is the normal outcome.
     */
    val ALTER_STATEMENTS: List<String> = listOf(
        "ALTER TABLE outfits ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE outfits ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE garments ADD COLUMN image_uri_nobg TEXT",
        "ALTER TABLE garments ADD COLUMN image_uris TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE garments ADD COLUMN image_uris_nobg TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE garments ADD COLUMN subcategory TEXT",
        "ALTER TABLE garments ADD COLUMN subcategories TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE garments ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE garments ADD COLUMN brand TEXT",
        "ALTER TABLE garments ADD COLUMN color_primary TEXT NOT NULL DEFAULT '#000000'",
        "ALTER TABLE garments ADD COLUMN color_secondary TEXT",
        "ALTER TABLE garments ADD COLUMN color_palette TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE garments ADD COLUMN size TEXT",
        "ALTER TABLE garments ADD COLUMN purchase_date TEXT",
        "ALTER TABLE garments ADD COLUMN is_available INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE garments ADD COLUMN unavailable_date TEXT",
        "ALTER TABLE garments ADD COLUMN created_at TEXT",
        "ALTER TABLE garments ADD COLUMN updated_at TEXT",
    )

    /** One statement at a time, so a failure names the index. */
    val INDEX_STATEMENTS: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS idx_garments_category ON garments(category)",
        "CREATE INDEX IF NOT EXISTS idx_garments_available ON garments(is_available)",
        "CREATE INDEX IF NOT EXISTS idx_outfit_ratings_outfit ON outfit_ratings(outfit_id)",
        "CREATE INDEX IF NOT EXISTS idx_pair_scores_score ON garment_pair_scores(score DESC)",
    )

    /**
     * Bring a database up to date, idempotently.
     *
     * Mirrors initializeDatabase in src/db/client.ts, including which failures
     * are ignored: an ALTER that hits an existing column is expected, an index
     * that fails is not.
     */
    fun applyTo(driver: SqlDriver) {
        for (statement in CREATE_TABLES.split(";")) {
            val trimmed = statement.trim()
            if (trimmed.isNotEmpty()) driver.execute(trimmed)
        }

        for (statement in ALTER_STATEMENTS) {
            try {
                driver.execute(statement)
            } catch (_: Exception) {
                // The column already exists, which is the usual case.
            }
        }

        for (statement in INDEX_STATEMENTS) {
            driver.execute(statement)
        }
    }
}
