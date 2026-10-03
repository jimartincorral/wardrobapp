package com.wardrobapp.data

import java.sql.DriverManager

/*
 * The tests' databases, as factories on JdbcSqlDriver's companion so a test still
 * says `JdbcSqlDriver.fresh()`. The driver itself is production code now -- the
 * server runs on it -- and these stayed behind because only tests want an
 * in-memory database, or a deliberately old one.
 */

/**
 * A database with the schema a fresh install gets.
 *
 * [WardrobeSchema.applyTo] is what the app runs on every open, so the tests run
 * against the same statements rather than against a copy of them that can drift.
 */
fun JdbcSqlDriver.Companion.fresh(): JdbcSqlDriver = built { WardrobeSchema.applyTo(it) }

/**
 * A database with the schema an install from before the additive `ALTER`s ends
 * up with -- [LegacySchema] as it was created, then brought up to date the way
 * the app brings it up to date on every open.
 *
 * Not the same shape as [fresh]: SQLite cannot add a `NOT NULL` column without a
 * default, so `garments.created_at` is nullable here. Both populations exist on
 * real phones, which is why every read-path test runs against both.
 */
fun JdbcSqlDriver.Companion.upgraded(): JdbcSqlDriver = built { driver ->
    for (statement in LegacySchema.CREATE_TABLES) driver.execute(statement)
    WardrobeSchema.applyTo(driver)
}

/** Both, by the name a failure should print. */
fun JdbcSqlDriver.Companion.bothSchemas(): List<Pair<String, () -> JdbcSqlDriver>> =
    listOf("fresh install" to { fresh() }, "upgraded install" to { upgraded() })

private fun built(apply: (SqlDriver) -> Unit): JdbcSqlDriver {
    val driver = JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))
    apply(driver)
    return driver
}
