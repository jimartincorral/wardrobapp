package com.wardrobapp.data

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema, and the two shapes it produces.
 *
 * This used to compare [WardrobeSchema] against a schema dumped from the
 * TypeScript it was transcribed from. That app is gone, so the comparison has
 * gone with it -- but the thing it was protecting has not: a database created
 * years ago by the small `CREATE TABLE` in [LegacySchema] and carried forward by
 * additive `ALTER`s has to end up holding the same columns as one created fresh
 * today, or a query written against one will fail against the other. That is now
 * asserted directly, which is a better test than the one it replaces: it says
 * what has to be true rather than that two implementations agree.
 *
 * Compared through `sqlite_master` and `PRAGMA table_info` rather than by reading
 * the SQL text, which would fail on whitespace and pass on a real difference
 * expressed differently.
 */
class WardrobeSchemaTest {

    private fun schemaOf(apply: (SqlDriver) -> Unit): List<String> {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            // Not wrapped in `use`: closing the driver closes this connection,
            // and sqlite_master still has to be read from it.
            apply(JdbcSqlDriver(connection))

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT type, name, sql FROM sqlite_master ORDER BY type, name"
                ).use { rs ->
                    val objects = mutableListOf<String>()
                    while (rs.next()) {
                        // Normalized: the fresh and upgraded paths format their
                        // DDL differently, and only the structure matters.
                        val sql = (rs.getString("sql") ?: "")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        objects.add("${rs.getString("type")} ${rs.getString("name")}: $sql")
                    }
                    return objects
                }
            }
        }
    }

    /** The old install, as it was created. */
    private val applyLegacyTables: (SqlDriver) -> Unit = { driver ->
        for (statement in LegacySchema.CREATE_TABLES) driver.execute(statement)
    }

    /** Every table's columns, as name to whether the column is `NOT NULL`. */
    private fun columnsOf(driver: SqlDriver): Map<String, Map<String, Boolean>> {
        val tables = driver
            .query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .map { it["name"] as String }
            .filterNot { it.startsWith("sqlite_") }

        return tables.associateWith { table ->
            driver.query("PRAGMA table_info($table)").associate {
                (it["name"] as String) to ((it["notnull"] as Number).toInt() == 1)
            }
        }
    }

    private fun <T> withDatabase(apply: (SqlDriver) -> Unit, read: (SqlDriver) -> T): T {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val driver = JdbcSqlDriver(connection)
            apply(driver)
            return read(driver)
        }
    }

    @Test
    fun `an upgraded install ends up holding the same columns as a fresh one`() {
        val fresh = withDatabase({ WardrobeSchema.applyTo(it) }, ::columnsOf)
        val upgraded = withDatabase(
            { driver ->
                applyLegacyTables(driver)
                WardrobeSchema.applyTo(driver)
            },
            ::columnsOf,
        )

        assertEquals(
            fresh.mapValues { (_, columns) -> columns.keys },
            upgraded.mapValues { (_, columns) -> columns.keys },
            "an ALTER is missing: a query written for one install would fail on the other",
        )
    }

    @Test
    fun `and differs from it in exactly the two columns SQLite will not add`() {
        // SQLite cannot add a NOT NULL column without a default, so the two
        // timestamps are nullable on any install old enough to have been ALTERed
        // into its current shape. Both populations are out there, which is the
        // reason this layer reads SQL by hand instead of using Room -- Room's
        // schema validation would reject one of them.
        //
        // Pinned rather than described: if a third column ever joins them, that
        // is a fact about the app's data worth noticing on purpose.
        val fresh = withDatabase({ WardrobeSchema.applyTo(it) }, ::columnsOf)
        val upgraded = withDatabase(
            { driver ->
                applyLegacyTables(driver)
                WardrobeSchema.applyTo(driver)
            },
            ::columnsOf,
        )

        val relaxed = fresh.flatMap { (table, columns) ->
            columns.filter { (column, notNull) ->
                notNull && upgraded.getValue(table)[column] == false
            }.keys.map { "$table.$it" }
        }

        assertEquals(listOf("garments.created_at", "garments.updated_at"), relaxed.sorted())
    }

    @Test
    fun `is idempotent`() {
        val once = schemaOf { WardrobeSchema.applyTo(it) }
        val twice = schemaOf { driver ->
            WardrobeSchema.applyTo(driver)
            WardrobeSchema.applyTo(driver)
        }

        // It runs on every open, so running twice must be indistinguishable.
        assertEquals(once, twice)
    }

    @Test
    fun `keeps the rows an old install already had`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val driver = JdbcSqlDriver(connection)
            applyLegacyTables(driver)
            driver.execute("INSERT INTO garments VALUES ('old', 'a.jpg', 'tops')")

            WardrobeSchema.applyTo(driver)

            // An upgrade that loses the wardrobe is not an upgrade.
            val row = driver.query("SELECT id, image_uri, category FROM garments").single()
            assertEquals("old", row["id"])
            assertEquals("a.jpg", row["image_uri"])
            assertEquals("tops", row["category"])
            assertTrue(driver.query("PRAGMA table_info(garments)").any { it["name"] == "is_available" })
        }
    }

    @Test
    fun `the comparison would notice a difference`() {
        // The normalization above collapses whitespace, so it is worth proving it
        // does not also collapse something that matters.
        val withExtraTable = schemaOf { driver ->
            WardrobeSchema.applyTo(driver)
            driver.execute("CREATE TABLE extra (id TEXT)")
        }
        val plain = schemaOf { WardrobeSchema.applyTo(it) }

        assertTrue(withExtraTable != plain, "the schema comparison is not comparing anything")
    }

    @Test
    fun `the column comparison would notice a missing ALTER`() {
        // Same reasoning one level down: the two tests above pass if columnsOf
        // returns the same thing for every input, which is also what it would do
        // if it were broken.
        val fresh = withDatabase({ WardrobeSchema.applyTo(it) }, ::columnsOf)
        val legacyOnly = withDatabase(applyLegacyTables, ::columnsOf)

        assertTrue(
            fresh.getValue("garments").keys != legacyOnly.getValue("garments").keys,
            "columnsOf cannot tell an old install from an upgraded one",
        )
    }
}
