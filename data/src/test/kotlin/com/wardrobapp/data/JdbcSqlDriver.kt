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
class JdbcSqlDriver(private val connection: Connection) : CloseableSqlDriver {

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
         * A database with the schema a fresh install gets.
         *
         * [WardrobeSchema.applyTo] is what the app runs on every open, so the
         * tests run against the same statements rather than against a copy of
         * them that can drift.
         */
        fun fresh(): JdbcSqlDriver = built { WardrobeSchema.applyTo(it) }

        /**
         * A database with the schema an install from before the additive `ALTER`s
         * ends up with -- [LegacySchema] as it was created, then brought up to
         * date the way the app brings it up to date on every open.
         *
         * Not the same shape as [fresh]: SQLite cannot add a `NOT NULL` column
         * without a default, so `garments.created_at` is nullable here. Both
         * populations exist on real phones, which is why every read-path test
         * runs against both.
         */
        fun upgraded(): JdbcSqlDriver = built { driver ->
            for (statement in LegacySchema.CREATE_TABLES) driver.execute(statement)
            WardrobeSchema.applyTo(driver)
        }

        /** Both, by the name a failure should print. */
        fun bothSchemas(): List<Pair<String, () -> JdbcSqlDriver>> =
            listOf("fresh install" to ::fresh, "upgraded install" to ::upgraded)

        private fun built(apply: (SqlDriver) -> Unit): JdbcSqlDriver {
            val driver = JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))
            apply(driver)
            return driver
        }
    }
}
