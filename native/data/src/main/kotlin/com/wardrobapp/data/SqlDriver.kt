package com.wardrobapp.data

/**
 * The narrowest thing the data layer needs from SQLite.
 *
 * Declared here rather than depending on androidx.sqlite so this module stays
 * plain Kotlin/JVM: the Android implementation wraps a SupportSQLiteDatabase,
 * and the tests wrap JDBC against the real schema the app applies. Both run the
 * same SQL, which is the point -- the statements are what have to be right, and
 * they can be exercised without an emulator.
 */
interface SqlDriver {
    /** Run a query, returning each row as column name to value. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>

    /** Run a statement that changes rows. Returns the number affected. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /**
     * Run several statements as one unit, rolling back if the block throws.
     *
     * The TypeScript has no equivalent: deleting a garment issues four separate
     * statements in sequence, so a failure partway through leaves the wardrobe
     * inconsistent -- a garment row gone but its learned pair scores still
     * present, or the reverse. Multi-statement writes here are atomic.
     */
    fun <T> transaction(block: () -> T): T
}
