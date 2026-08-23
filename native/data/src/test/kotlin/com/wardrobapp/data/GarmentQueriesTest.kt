package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read paths, against both schemas that exist in the wild.
 *
 * This is the claim that matters for the migration: an existing wardrobe opens
 * correctly under the native app. It cannot be tested against a database the
 * React Native app wrote -- that needs a device -- so it is tested against the
 * schema the app actually applies, emitted from src/db/schema.ts so it cannot
 * drift from the real thing.
 *
 * Every test runs twice, once per schema, because the two differ:
 * created_at/updated_at are NOT NULL on a fresh install and nullable on an
 * upgraded one.
 */
class GarmentQueriesTest {

    private val schemas = listOf("schema-fresh.sql", "schema-upgraded.sql")

    private val directory = "file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/"

    /** Insert a garment the way the TypeScript's INSERT does. */
    private fun JdbcSqlDriver.insertGarment(
        id: String,
        category: String = "tops",
        subcategory: String? = "T-Shirt",
        tags: String = "[]",
        brand: String? = null,
        colorPrimary: String = "#000000",
        size: String? = "M",
        isAvailable: Int = 1,
        createdAt: String = "2026-01-01T00:00:00.000Z",
        imageUri: String = "$id.jpg",
    ) {
        execute(
            """
            INSERT INTO garments (
                id, image_uri, image_uri_nobg, image_uris, image_uris_nobg, category,
                subcategory, subcategories, tags, brand, color_primary, color_secondary,
                color_palette, size, purchase_date, is_available, created_at, updated_at
            ) VALUES (?, ?, NULL, ?, '[]', ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?)
            """.trimIndent(),
            listOf(
                id, imageUri, """["$imageUri"]""", category,
                subcategory, if (subcategory != null) """["$subcategory"]""" else "[]",
                tags, brand, colorPrimary, """["$colorPrimary"]""", size, isAvailable,
                createdAt, createdAt,
            ),
        )
    }

    private fun eachSchema(body: (schema: String, driver: JdbcSqlDriver, queries: GarmentQueries) -> Unit) {
        for (schema in schemas) {
            JdbcSqlDriver.fromSchemaFixture(schema).use { driver ->
                body(schema, driver, GarmentQueries(driver, directory))
            }
        }
    }

    @Test
    fun `both schemas really are the two shapes in the wild`() {
        // If this stops holding, the pair of fixtures has stopped being worth
        // running twice -- and the reason the data layer avoids Room has gone.
        val notNull = mutableMapOf<String, Boolean>()

        eachSchema { schema, driver, _ ->
            val createdAt = driver.query("PRAGMA table_info(garments)")
                .single { it["name"] == "created_at" }
            notNull[schema] = (createdAt["notnull"] as Number).toInt() == 1

            // Either way, every column the reader touches has to be present.
            val columns = driver.query("PRAGMA table_info(garments)").map { it["name"] }
            assertTrue(columns.containsAll(listOf("tags", "color_palette", "image_uris", "subcategories")))
        }

        assertEquals(
            mapOf("schema-fresh.sql" to true, "schema-upgraded.sql" to false),
            notNull,
            "the fresh schema should have created_at NOT NULL and the upgraded one nullable"
        )
    }

    @Test
    fun `reads a garment back out of a real database`() {
        eachSchema { schema, driver, queries ->
            driver.insertGarment(id = "g1", tags = """["Cotton","Basic"]""", brand = "Uniqlo")

            val garment = queries.garment("g1")
            assertNotNull(garment, "$schema: expected to read g1 back")

            assertEquals("g1", garment.id)
            assertEquals("tops", garment.category)
            assertEquals("Uniqlo", garment.brand)
            // Tags come back lowercased, as the normalizer guarantees.
            assertEquals(listOf("cotton", "basic"), garment.tags)
            assertEquals("M", garment.size)
            assertTrue(garment.isAvailable)
            // The stored filename is re-based onto the current directory.
            assertEquals("${directory}g1.jpg", garment.imageUri)
        }
    }

    @Test
    fun `a missing garment reads as absent rather than as an empty one`() {
        eachSchema { schema, _, queries ->
            assertNull(queries.garment("nope"), "$schema: expected null for a missing id")
        }
    }

    @Test
    fun `hides unavailable garments unless asked for them`() {
        eachSchema { schema, driver, queries ->
            driver.insertGarment(id = "keep", isAvailable = 1)
            driver.insertGarment(id = "gone", isAvailable = 0)

            assertEquals(
                listOf("keep"),
                queries.allGarments().map { it.id },
                "$schema: the default should be available-only"
            )
            assertEquals(
                setOf("keep", "gone"),
                queries.allGarments(GarmentQueries.Filters(availableOnly = false)).map { it.id }.toSet(),
                "$schema: availableOnly=false should include both"
            )
            assertEquals(1L, queries.availableCount(), "$schema: available count")
            assertEquals(1L, queries.unavailableCount(), "$schema: unavailable count")
        }
    }

    @Test
    fun `returns newest first`() {
        eachSchema { schema, driver, queries ->
            driver.insertGarment(id = "old", createdAt = "2026-01-01T00:00:00.000Z")
            driver.insertGarment(id = "new", createdAt = "2026-06-01T00:00:00.000Z")
            driver.insertGarment(id = "mid", createdAt = "2026-03-01T00:00:00.000Z")

            // created_at DESC is what the suggestion engine's candidate lists
            // inherit, so the order is behaviour, not presentation.
            assertEquals(listOf("new", "mid", "old"), queries.allGarments().map { it.id }, schema)
        }
    }

    @Test
    fun `filters by category and by search term`() {
        eachSchema { schema, driver, queries ->
            driver.insertGarment(id = "top", category = "tops", brand = "Uniqlo")
            driver.insertGarment(id = "shoe", category = "shoes", brand = "Nike")

            assertEquals(
                listOf("top"),
                queries.allGarments(GarmentQueries.Filters(category = "tops")).map { it.id },
                schema
            )
            assertEquals(
                listOf("shoe"),
                queries.allGarments(GarmentQueries.Filters(search = "Nike")).map { it.id },
                schema
            )
            assertEquals(
                emptyList(),
                queries.allGarments(GarmentQueries.Filters(search = "Adidas")).map { it.id },
                schema
            )
        }
    }

    @Test
    fun `lists brands without blanks and without duplicates`() {
        eachSchema { schema, driver, queries ->
            driver.insertGarment(id = "a", brand = "Uniqlo")
            driver.insertGarment(id = "b", brand = "  Uniqlo  ")
            driver.insertGarment(id = "c", brand = "   ")
            driver.insertGarment(id = "d", brand = null)
            driver.insertGarment(id = "e", brand = "Adidas")

            assertEquals(listOf("Adidas", "Uniqlo"), queries.brands(), schema)
        }
    }

    @Test
    fun `lists tags across the whole wardrobe, including unavailable garments`() {
        eachSchema { schema, driver, queries ->
            driver.insertGarment(id = "a", tags = """["Cotton","summer"]""")
            driver.insertGarment(id = "b", tags = """["cotton","Wool"]""", isAvailable = 0)

            // Case-insensitively unique, and an unavailable garment's tags still
            // count -- they are still tags the user has used.
            assertEquals(listOf("cotton", "summer", "wool"), queries.tags(), schema)
        }
    }

    @Test
    fun `reads a row written by the oldest build, with comma-separated lists`() {
        eachSchema { schema, driver, queries ->
            // Not something the current app writes, but rows like this exist and
            // the reader has to cope.
            driver.execute(
                """
                INSERT INTO garments (id, image_uri, category, tags, subcategories,
                                      color_palette, color_primary, is_available,
                                      created_at, updated_at)
                VALUES ('old', 'a.jpg', 'tops', 'winter, leather', 'Boots, Sneakers',
                        '#8B4513, #000000', '#8B4513', 1, '2026-01-01', '2026-01-01')
                    """.trimIndent()
            )

            val garment = queries.garment("old")
            assertNotNull(garment, schema)
            assertEquals(listOf("winter", "leather"), garment.tags, schema)
            assertEquals(listOf("Boots", "Sneakers"), garment.subcategories, schema)
            assertEquals(listOf("#8B4513", "#000000"), garment.colorPalette, schema)
        }
    }

    @Test
    fun `an upgraded install with no timestamps still reads`() {
        // Only the upgraded schema permits this, and it is the case Room's schema
        // validation would have rejected outright.
        JdbcSqlDriver.fromSchemaFixture("schema-upgraded.sql").use { driver ->
            driver.execute(
                """
                INSERT INTO garments (id, image_uri, category, color_primary, is_available)
                VALUES ('no-dates', 'a.jpg', 'tops', '#000000', 1)
                    """.trimIndent()
            )

            val garment = driver.let { GarmentQueries(it, directory).garment("no-dates") }
            assertNotNull(garment)
            assertNull(garment.createdAt)
            assertNull(garment.updatedAt)
            assertTrue(garment.isAvailable)
        }
    }

    @Test
    fun `an empty wardrobe reads as empty rather than failing`() {
        eachSchema { schema, _, queries ->
            assertEquals(emptyList(), queries.allGarments(), schema)
            assertEquals(0L, queries.availableCount(), schema)
            assertEquals(emptyList(), queries.brands(), schema)
            assertEquals(emptyList(), queries.tags(), schema)
        }
    }
}
