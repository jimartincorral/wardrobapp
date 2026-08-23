package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wardrobe breakdowns, against real SQLite and both schemas.
 *
 * The aggregation *is* the behaviour here, so it is run rather than asserted
 * about: a GROUP BY on the wrong expression is invisible to anything that does
 * not execute the query.
 */
class AnalyticsQueriesTest {

    private val schemas = listOf("schema-fresh.sql", "schema-upgraded.sql")

    private fun eachSchema(body: (schema: String, driver: JdbcSqlDriver, analytics: AnalyticsQueries) -> Unit) {
        for (schema in schemas) {
            JdbcSqlDriver.fromSchemaFixture(schema).use { driver ->
                body(schema, driver, AnalyticsQueries(driver))
            }
        }
    }

    private fun JdbcSqlDriver.addGarment(
        id: String,
        category: String = "tops",
        subcategory: String? = null,
        subcategories: String = "[]",
        brand: String? = null,
        colorPrimary: String = "#000000",
        isAvailable: Int = 1,
        purchaseDate: String? = null,
        unavailableDate: String? = null,
    ) {
        execute(
            """
            INSERT INTO garments (
                id, image_uri, category, subcategory, subcategories, tags, brand,
                color_primary, color_palette, is_available, purchase_date,
                unavailable_date, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, '[]', ?, ?, '[]', ?, ?, ?, '2026-01-01', '2026-01-01')
            """.trimIndent(),
            listOf(
                id, "$id.jpg", category, subcategory, subcategories, brand,
                colorPrimary, isAvailable, purchaseDate, unavailableDate,
            ),
        )
    }

    @Test
    fun `counts available garments per category, most first`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("1", category = "tops")
            driver.addGarment("2", category = "tops")
            driver.addGarment("3", category = "shoes")
            driver.addGarment("4", category = "shoes", isAvailable = 0)

            assertEquals(
                listOf(AnalyticsQueries.Count("tops", 2), AnalyticsQueries.Count("shoes", 1)),
                analytics.byCategory(),
                schema
            )
        }
    }

    @Test
    fun `treats a brand as one brand however it was typed`() {
        eachSchema { schema, driver, analytics ->
            // The brand picker already considers these one brand.
            driver.addGarment("1", brand = "Uniqlo")
            driver.addGarment("2", brand = " Uniqlo")
            driver.addGarment("3", brand = "Uniqlo ")
            driver.addGarment("4", brand = "Nike")

            assertEquals(
                listOf(AnalyticsQueries.Count("Uniqlo", 3), AnalyticsQueries.Count("Nike", 1)),
                analytics.byBrand(),
                schema
            )
        }
    }

    @Test
    fun `ignores blank and absent brands`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("1", brand = "Nike")
            driver.addGarment("2", brand = "   ")
            driver.addGarment("3", brand = null)

            assertEquals(listOf(AnalyticsQueries.Count("Nike", 1)), analytics.byBrand(), schema)
        }
    }

    @Test
    fun `treats a colour as one colour whatever its case`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("1", colorPrimary = "#ABCDEF")
            driver.addGarment("2", colorPrimary = "#abcdef")
            driver.addGarment("3", colorPrimary = "#000000")

            assertEquals(
                listOf(AnalyticsQueries.Count("#ABCDEF", 2), AnalyticsQueries.Count("#000000", 1)),
                analytics.byColor(),
                schema
            )
        }
    }

    @Test
    fun `groups subcategories under their category`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("1", category = "tops", subcategories = """["T-Shirt"]""")
            driver.addGarment("2", category = "tops", subcategories = """["T-Shirt"]""")
            driver.addGarment("3", category = "tops", subcategories = """["Hoodie"]""")
            driver.addGarment("4", category = "shoes", subcategories = """["Boots"]""")

            assertEquals(
                mapOf(
                    "tops" to listOf(
                        AnalyticsQueries.Count("T-Shirt", 2),
                        AnalyticsQueries.Count("Hoodie", 1),
                    ),
                    "shoes" to listOf(AnalyticsQueries.Count("Boots", 1)),
                ),
                analytics.bySubcategory(),
                schema
            )
        }
    }

    @Test
    fun `falls back to the singular column, then to a placeholder`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("1", category = "tops", subcategory = "Polo")
            driver.addGarment("2", category = "tops")

            val tops = analytics.bySubcategory()["tops"]!!
            assertTrue(tops.contains(AnalyticsQueries.Count("Polo", 1)), schema)
            assertTrue(
                tops.contains(AnalyticsQueries.Count(AnalyticsQueries.NO_SUBCATEGORY, 1)),
                "$schema: a garment with no subcategory should still be counted"
            )
        }
    }

    @Test
    fun `counts a garment once per subcategory it carries`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("1", category = "tops", subcategories = """["T-Shirt","Polo"]""")

            assertEquals(
                listOf(AnalyticsQueries.Count("T-Shirt", 1), AnalyticsQueries.Count("Polo", 1)),
                analytics.bySubcategory()["tops"],
                schema
            )
        }
    }

    @Test
    fun `reports days owned for retired garments, longest first`() {
        eachSchema { schema, driver, analytics ->
            driver.addGarment("short", isAvailable = 0, purchaseDate = "2026-01-01", unavailableDate = "2026-01-11")
            driver.addGarment("long", isAvailable = 0, purchaseDate = "2026-01-01", unavailableDate = "2026-03-02")
            // Still owned: no lifespan yet.
            driver.addGarment("current", purchaseDate = "2026-01-01")
            // Retired but never given a purchase date.
            driver.addGarment("unknown", isAvailable = 0, unavailableDate = "2026-02-01")

            val lifespans = analytics.lifespans("/photos/")

            assertEquals(listOf("long", "short"), lifespans.map { it.garment.id }, schema)
            assertEquals(60L, lifespans[0].days, schema)
            assertEquals(10L, lifespans[1].days, schema)
        }
    }

    @Test
    fun `an empty wardrobe reports nothing rather than failing`() {
        eachSchema { schema, _, analytics ->
            assertEquals(emptyList(), analytics.byCategory(), schema)
            assertEquals(emptyList(), analytics.byBrand(), schema)
            assertEquals(emptyList(), analytics.byColor(), schema)
            assertEquals(emptyMap(), analytics.bySubcategory(), schema)
            assertEquals(emptyList(), analytics.lifespans("/photos/"), schema)
        }
    }
}
