package com.wardrobapp.data

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.fail

/**
 * A [SqlDriver] over real SQLite, for the tests.
 *
 * The production driver on Android wraps SupportSQLiteDatabase; this wraps JDBC.
 * Both run the same SQL against the same schema, which is what makes the read
 * paths testable without an emulator.
 */
class JdbcSqlDriver(private val connection: Connection) : SqlDriver, AutoCloseable {

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }

            statement.executeQuery().use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                val rows = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    rows.add(columns.associateWith { rs.getObject(it) })
                }
                return rows
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            return statement.executeUpdate()
        }
    }

    override fun <T> transaction(block: () -> T): T {
        // Nested calls join the outer transaction rather than starting a second
        // one, which SQLite would reject.
        if (!connection.autoCommit) return block()

        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    companion object {
        /**
         * Build an in-memory database from one of the schema fixtures.
         *
         * The fixtures are emitted from src/db/schema.ts, so this is the schema
         * the app actually applies -- not a copy of it that can drift.
         */
        fun fromSchemaFixture(name: String): JdbcSqlDriver {
            val sql = JdbcSqlDriver::class.java.getResourceAsStream("/parity/$name")
                ?.bufferedReader()?.readText()
                ?: fail("Missing schema fixture '$name'. Generate it with: npm run parity:dump")

            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            var applied = 0
            var ignored = 0

            // Comments are stripped before splitting, not after: a `--` comment
            // may itself contain a semicolon, and splitting first chops it into a
            // fragment that then fails as a statement. (No statement here has a
            // semicolon inside a string literal, which this would also split.)
            val statements = sql.lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(';')

            for (statement in statements) {
                val trimmed = statement.trim()
                if (trimmed.isEmpty()) continue

                try {
                    connection.createStatement().use { it.execute(trimmed) }
                    applied++
                } catch (e: Exception) {
                    // The app ignores "duplicate column name" the same way, which
                    // is the whole mechanism by which an upgraded install ends up
                    // with a different schema from a fresh one.
                    if (e.message?.contains("duplicate column name") == true) {
                        ignored++
                    } else {
                        fail("Schema fixture '$name' failed on:\n$trimmed\n\n${e.message}")
                    }
                }
            }

            if (applied == 0) fail("Schema fixture '$name' applied no statements")
            return JdbcSqlDriver(connection)
        }
    }
}
