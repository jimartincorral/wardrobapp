package com.wardrobapp.data

/** A [SqlDriver] that holds a resource and can give it up. */
interface CloseableSqlDriver : SqlDriver, AutoCloseable

/**
 * One connection to the wardrobe, which can be put down and picked up again.
 *
 * A restore replaces the database file underneath the app, so the connection to
 * the old file has to be closed first. SQLite would otherwise go on writing to
 * the file it opened -- now unlinked, or worse, the restored one through a stale
 * page cache, which is how a restore ends up half-applied. The React Native app
 * needed the same thing and called it a maintenance lock.
 *
 * Opening is lazy in both directions: nothing is opened until something asks a
 * question, and nothing needs to be told when the restore finished. Queries
 * *during* the window fail rather than quietly reopening the file being
 * replaced -- a caller that gets an answer from a database about to be thrown
 * away is worse off than one that gets an error.
 */
class ReopeningDriver(private val openDatabase: () -> CloseableSqlDriver) : SqlDriver {

    private val lock = Any()
    private var current: CloseableSqlDriver? = null
    private var closedForMaintenance = false

    private fun driver(): SqlDriver = synchronized(lock) {
        check(!closedForMaintenance) { "the wardrobe is being replaced right now" }
        current ?: openDatabase().also { current = it }
    }

    override fun query(sql: String, args: List<Any?>) = driver().query(sql, args)

    override fun execute(sql: String, args: List<Any?>) = driver().execute(sql, args)

    override fun <T> transaction(block: () -> T): T = driver().transaction(block)

    /**
     * Run something with the database closed, reopening on the next query.
     *
     * Reopening goes through the same factory as the first open, so whatever it
     * does on the way in -- applying the schema, setting pragmas -- happens
     * again. That matters after a restore: the archive may have been written by
     * an older build, and this app has always brought a database forward by
     * applying the schema to whatever it finds.
     */
    fun <T> whileClosed(block: () -> T): T {
        synchronized(lock) {
            closedForMaintenance = true
            current?.close()
            current = null
        }
        try {
            return block()
        } finally {
            synchronized(lock) { closedForMaintenance = false }
        }
    }
}
