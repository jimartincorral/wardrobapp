package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reads over outfits that no other test covers, against real SQLite.
 *
 * [OutfitQueries.ratedCount] backs a row on Home's first-steps card, which is the
 * one place in the app where the difference between "none" and "some" is the
 * whole answer -- so it is run rather than reasoned about.
 */
class OutfitQueriesTest {

    private val now = "2026-05-04"

    private fun eachSchema(body: (schema: String, driver: JdbcSqlDriver) -> Unit) {
        for ((schema, openDatabase) in JdbcSqlDriver.bothSchemas()) {
            openDatabase().use { driver -> body(schema, driver) }
        }
    }

    private fun JdbcSqlDriver.addGarment(id: String) {
        GarmentWrites(this).insert(
            GarmentWrites.NewGarment(
                id = id,
                imageUri = "$id.jpg",
                category = "tops",
                colorPrimary = "#000000",
                now = now,
            )
        )
    }

    @Test
    fun `nothing rated is nothing counted`() {
        eachSchema { schema, driver ->
            driver.addGarment("a")
            driver.addGarment("b")
            OutfitWrites(driver).insert(
                id = "o1",
                name = "Fit",
                garmentIds = listOf("a", "b"),
                now = now,
            )

            // An outfit exists, and it has never been rated. The row on Home is
            // about the rating, not about the outfit.
            assertEquals(0L, OutfitQueries(driver).ratedCount(), schema)
        }
    }

    @Test
    fun `a rating is counted, and correcting it is still one`() {
        eachSchema { schema, driver ->
            driver.addGarment("a")
            driver.addGarment("b")
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "o1", name = "Fit", garmentIds = listOf("a", "b"), now = now)

            outfits.rate(ratingId = "r1", outfitId = "o1", rating = 5, now = now)
            assertEquals(1L, OutfitQueries(driver).ratedCount(), schema)

            // Re-rating replaces rather than adds, which the count has to agree
            // with: the row it backs asks whether anybody has rated anything.
            outfits.rate(ratingId = "r2", outfitId = "o1", rating = 2, now = "2026-05-05")
            assertEquals(1L, OutfitQueries(driver).ratedCount(), schema)
        }
    }

    @Test
    fun `an outfit rated and put away still counts`() {
        eachSchema { schema, driver ->
            driver.addGarment("a")
            driver.addGarment("b")
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "o1", name = "Fit", garmentIds = listOf("a", "b"), now = now)
            outfits.rate(ratingId = "r1", outfitId = "o1", rating = 1, now = now)
            outfits.setArchived("o1", isArchived = true)

            // Rating a suggestion you would not wear is how the app learns, and
            // archiving is what happens to it afterwards. Excluding those would
            // un-tick the row for the person who used it most honestly.
            assertEquals(1L, OutfitQueries(driver).ratedCount(), schema)
        }
    }
}
