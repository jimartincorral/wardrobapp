package com.wardrobapp.data

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Kotlin schema against the TypeScript one.
 *
 * WardrobeSchema is a transcription of src/db/schema.ts, and a transcription can
 * drift. Rather than compare the SQL text -- which would fail on whitespace and
 * pass on a genuine difference expressed differently -- both are applied to an
 * empty database and the resulting sqlite_master is compared. That is the thing
 * that actually has to match.
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
                        // Normalized: the two sources format their DDL
                        // differently, and only the structure matters.
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

    /** Apply a schema fixture, ignoring the failures the app ignores. */
    private fun applyFixture(name: String): (SqlDriver) -> Unit = { driver ->
        val sql = javaClass.getResourceAsStream("/parity/$name")
            ?.bufferedReader()?.readText()
            ?: fail("Missing $name. Generate it with: npm run parity:dump")

        val statements = sql.lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')

        for (statement in statements) {
            val trimmed = statement.trim()
            if (trimmed.isEmpty()) continue
            try {
                driver.execute(trimmed)
            } catch (e: Exception) {
                if (e.message?.contains("duplicate column name") != true) throw e
            }
        }
    }

    @Test
    fun `produces the same schema as the TypeScript on a fresh database`() {
        val fromKotlin = schemaOf { WardrobeSchema.applyTo(it) }
        val fromTypeScript = schemaOf(applyFixture("schema-fresh.sql"))

        assertEquals(
            fromTypeScript,
            fromKotlin,
            "the Kotlin transcription has drifted from src/db/schema.ts"
        )
    }

    @Test
    fun `upgrades an old install the same way the TypeScript does`() {
        // The same old-install DDL the upgraded fixture starts from, loaded
        // rather than copied: a second copy is a second thing that can drift,
        // and the first version of this test compared two different starting
        // states without saying so.
        val fromKotlin = schemaOf { driver ->
            applyFixture("schema-old-install.sql")(driver)
            WardrobeSchema.applyTo(driver)
        }
        val fromTypeScript = schemaOf(applyFixture("schema-upgraded.sql"))

        assertEquals(fromTypeScript, fromKotlin, "the upgrade path has drifted")
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
            applyFixture("schema-old-install.sql")(driver)
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
}
