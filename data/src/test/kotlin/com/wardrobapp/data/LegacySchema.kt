package com.wardrobapp.data

/**
 * The schema an install predating every additive `ALTER` was created with.
 *
 * Two shapes of database exist on real phones. A fresh install runs
 * [WardrobeSchema.CREATE_TABLES] and gets every column at once. An install old
 * enough to predate them ran a much smaller `CREATE TABLE` and has been picking
 * up columns through [WardrobeSchema.ALTER_STATEMENTS] ever since -- and SQLite
 * cannot add a `NOT NULL` column without a default, so that database's
 * `created_at` is nullable where a fresh one's is not.
 *
 * This is the starting point of that second shape, and it is here so the tests
 * can build it. It was emitted from the app that created those databases, back
 * when this repository still held one; it is frozen because what it describes is
 * frozen -- nobody can go back and change what a phone did in 2025. Nothing new
 * belongs in it. New columns go in [WardrobeSchema.ALTER_STATEMENTS], which is
 * what carries both populations forward.
 */
object LegacySchema {

    val CREATE_TABLES: List<String> = listOf(
        """
        CREATE TABLE garments (
          id TEXT PRIMARY KEY,
          image_uri TEXT NOT NULL,
          category TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE outfits (
          id TEXT PRIMARY KEY,
          name TEXT NOT NULL,
          garment_ids TEXT NOT NULL DEFAULT '[]',
          occasion TEXT,
          season TEXT,
          created_at TEXT NOT NULL,
          is_suggested INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
    )
}
