package com.wardrobapp.data

/**
 * The narrowest thing the read paths need from SQLite.
 *
 * Declared here rather than depending on androidx.sqlite so this module stays
 * plain Kotlin/JVM: the Android implementation wraps a SupportSQLiteDatabase,
 * and the tests wrap JDBC against the real schema the app applies. Both run the
 * same SQL, which is the point -- the queries are what has to be right, and they
 * can be exercised without an emulator.
 *
 * Read-only on purpose, for now. Nothing here can damage a wardrobe.
 */
interface SqlDriver {
    /** Run a query, returning each row as column name to value. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>>
}
