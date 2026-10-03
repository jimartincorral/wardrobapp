package com.wardrobapp.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [SqlDriver] over JDBC: the Home Assistant server's, and the tests'.
 *
 * The phone's driver wraps SupportSQLiteDatabase instead; this one runs the same
 * SQL against the same schema through `java.sql`, which is all it compiles
 * against. The SQLite JDBC driver itself is a runtime dependency of whatever
 * opens a database with this -- the server and the tests -- and of nothing
 * else, so it never reaches the phone. That is why this can live in :data's
 * JVM code, where :app can see it, rather than in a module of its own.
 *
 * It used to be test code. It moved here when the server needed one, so the
 * driver the server runs on is the one every read and write path in this module
 * has been tested through, not a second copy written for production.
 *
 * One connection, used by one thread at a time. The phone gets that for free --
 * SupportSQLiteDatabase serialises access, and each screen asks one question at
 * a time anyway -- but a server answers requests concurrently, and a JDBC
 * connection shared between threads interleaves their statements: one request's
 * write lands inside another's transaction, and is committed or rolled back
 * with it. So every call holds [lock] for its duration, and a transaction holds
 * it from begin to commit. Reentrant, because transactions nest -- deleting a
 * garment calls into the outfit writes, which open their own -- and a nested
 * call comes from the thread already holding it.
 *
 * Serialising a household's wardrobe costs nothing anyone could notice. A pool
 * of connections would buy parallel reads, which WAL allows, at the price of
 * each connection needing its own pragmas and the restore path needing to close
 * all of them; not worth it for a few people and a few hundred garments.
 */
class JdbcSqlDriver(private val connection: Connection) : CloseableSqlDriver {

    private val lock = ReentrantLock()

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = lock.withLock {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }

            statement.executeQuery().use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                val rows = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    rows.add(columns.associateWith { rs.getObject(it) })
                }
                rows
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int = lock.withLock {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeUpdate()
        }
    }

    override fun <T> transaction(block: () -> T): T = lock.withLock {
        // Nested calls join the outer transaction rather than starting a second
        // one, which SQLite would reject. Only the thread holding the lock can
        // get here while a transaction is open, so an open one is always its own.
        if (!connection.autoCommit) return block()

        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = lock.withLock { connection.close() }

    companion object {
        /**
         * Open the wardrobe database at [file], creating it and its directory if
         * they are not there yet.
         *
         * With the pragmas the phone sets, so a database copied between the two
         * -- a backup made on one and restored on the other -- behaves the same
         * on both: foreign keys on, because the rating cascade depends on them,
         * and WAL. Set here, per connection, because SQLite forgets
         * `foreign_keys` when a connection closes; the journal mode is stored in
         * the file, but asking again is harmless.
         *
         * The busy timeout is for anything else that opens the file while the
         * server holds it -- the sqlite3 shell, a backup tool -- which would
         * otherwise make a write fail at once instead of waiting a moment.
         *
         * The schema is not applied here. That is the caller's, on every open,
         * as on the phone: see [WardrobeSchema.applyTo].
         */
        fun open(file: File): JdbcSqlDriver {
            file.absoluteFile.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON;")
                statement.execute("PRAGMA journal_mode = WAL;")
                statement.execute("PRAGMA busy_timeout = 5000;")
            }
            return JdbcSqlDriver(connection)
        }
    }
}
