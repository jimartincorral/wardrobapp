package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write paths, against both schemas that exist in the wild.
 *
 * Every write is checked by reading it back through the real read paths rather
 * than by inspecting the SQL, so a write that stores something the reader cannot
 * interpret fails here -- which is the failure mode that matters, and the one an
 * assertion about the statement text would miss entirely.
 */
class WritePathsTest {

    private val schemas = listOf("schema-fresh.sql", "schema-upgraded.sql")
    private val directory = "/photos/"
    private val now = "2026-01-01T00:00:00.000Z"

    private fun eachSchema(
        body: (schema: String, driver: JdbcSqlDriver, reads: GarmentQueries, writes: GarmentWrites) -> Unit,
    ) {
        for (schema in schemas) {
            JdbcSqlDriver.fromSchemaFixture(schema).use { driver ->
                body(schema, driver, GarmentQueries(driver, directory), GarmentWrites(driver))
            }
        }
    }

    private fun newGarment(
        id: String,
        category: String = "tops",
        tags: List<String> = emptyList(),
        imageUri: String = "$id.jpg",
        colorPrimary: String = "#000000",
        size: String? = "M",
    ) = GarmentWrites.NewGarment(
        id = id,
        imageUri = imageUri,
        category = category,
        subcategories = listOf("T-Shirt"),
        tags = tags,
        colorPrimary = colorPrimary,
        colorPalette = listOf(colorPrimary),
        size = size,
        now = now,
    )

    @Test
    fun `a written garment reads back as itself`() {
        eachSchema { schema, _, reads, writes ->
            writes.insert(newGarment("g1", tags = listOf("cotton", "basic")))

            val garment = reads.garment("g1")
            assertNotNull(garment, schema)
            assertEquals("tops", garment.category, schema)
            assertEquals(listOf("cotton", "basic"), garment.tags, schema)
            assertEquals("M", garment.size, schema)
            assertEquals(listOf("T-Shirt"), garment.subcategories, schema)
            assertTrue(garment.isAvailable, schema)
            assertEquals("${directory}g1.jpg", garment.imageUri, schema)
            assertEquals(now, garment.createdAt, schema)
        }
    }

    @Test
    fun `a photo is stored without its directory`() {
        eachSchema { schema, driver, reads, writes ->
            // The bug this prevents: storing an absolute path meant a restored
            // wardrobe pointed at a directory that no longer existed.
            writes.insert(newGarment("g1", imageUri = "file:///old/install/garment-images/front.jpg"))

            val stored = driver.query("SELECT image_uri FROM garments WHERE id = 'g1'")
                .single()["image_uri"]
            assertEquals("front.jpg", stored, "$schema: the directory should not be stored")

            // And it comes back resolved against the current directory.
            assertEquals("${directory}front.jpg", reads.garment("g1")?.imageUri, schema)
        }
    }

    @Test
    fun `a portable reference is stored untouched`() {
        eachSchema { schema, driver, _, writes ->
            writes.insert(newGarment("saf", imageUri = "content://media/external/images/1"))

            assertEquals(
                "content://media/external/images/1",
                driver.query("SELECT image_uri FROM garments WHERE id = 'saf'").single()["image_uri"],
                "$schema: a SAF document must not be reduced to a filename"
            )
        }
    }

    @Test
    fun `a tag containing a quote survives the round trip`() {
        eachSchema { schema, _, reads, writes ->
            // Encoding the list by hand would break here.
            writes.insert(newGarment("g1", tags = listOf("""say "hi"""", "back\\slash")))

            assertEquals(
                listOf("""say "hi"""", "back\\slash"),
                reads.garment("g1")?.tags,
                schema
            )
        }
    }

    @Test
    fun `an edit changes only what it names`() {
        eachSchema { schema, _, reads, writes ->
            writes.insert(newGarment("g1", tags = listOf("cotton")))

            val changed = writes.update("g1", GarmentWrites.GarmentEdit(brand = "Uniqlo"), "2026-02-02")
            assertTrue(changed, schema)

            val garment = reads.garment("g1")
            assertNotNull(garment, schema)
            assertEquals("Uniqlo", garment.brand, schema)
            assertEquals(listOf("cotton"), garment.tags, "$schema: tags should be untouched")
            assertEquals("2026-02-02", garment.updatedAt, schema)
            assertEquals(now, garment.createdAt, "$schema: created_at should be untouched")
        }
    }

    @Test
    fun `an empty edit writes nothing`() {
        eachSchema { schema, _, reads, writes ->
            writes.insert(newGarment("g1"))

            // Without this, an empty edit would still bump updated_at -- and
            // would produce `UPDATE garments SET WHERE id = ?`, which is a syntax
            // error rather than a no-op.
            assertEquals(false, writes.update("g1", GarmentWrites.GarmentEdit(), "2026-02-02"), schema)
            assertEquals(now, reads.garment("g1")?.updatedAt, "$schema: updated_at should be untouched")
        }
    }

    @Test
    fun `editing subcategories keeps the single-value column in step`() {
        eachSchema { schema, driver, reads, writes ->
            writes.insert(newGarment("g1"))
            writes.update("g1", GarmentWrites.GarmentEdit(subcategories = listOf("Hoodie", "Sweater")), now)

            // Both columns are read, and older builds only wrote the singular one.
            assertEquals(
                "Hoodie",
                driver.query("SELECT subcategory FROM garments WHERE id = 'g1'").single()["subcategory"],
                schema
            )
            assertEquals(listOf("Hoodie", "Sweater"), reads.garment("g1")?.subcategories, schema)
        }
    }

    @Test
    fun `marking unavailable and available again`() {
        eachSchema { schema, _, reads, writes ->
            writes.insert(newGarment("g1"))

            writes.markUnavailable("g1", "2026-03-03")
            assertEquals(false, reads.garment("g1")?.isAvailable, schema)
            assertEquals("2026-03-03", reads.garment("g1")?.unavailableDate, schema)
            assertEquals(0L, reads.availableCount(), schema)

            writes.markAvailable("g1", "2026-04-04")
            assertEquals(true, reads.garment("g1")?.isAvailable, schema)
            assertNull(reads.garment("g1")?.unavailableDate, "$schema: the date should be cleared")
            assertEquals(1L, reads.availableCount(), schema)
        }
    }

    @Test
    fun `deleting a garment takes its pair scores and outfit slots with it`() {
        eachSchema { schema, driver, reads, writes ->
            writes.insert(newGarment("keep"))
            writes.insert(newGarment("gone"))
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "pair", name = "Both", garmentIds = listOf("keep", "gone"), now = now)
            outfits.insert(id = "solo", name = "Only", garmentIds = listOf("gone"), now = now)
            outfits.rate(ratingId = "r1", outfitId = "pair", rating = 5, now = now)

            assertEquals(
                1,
                driver.query("SELECT * FROM garment_pair_scores").size,
                "$schema: rating should have taught one pair"
            )

            val photos = writes.delete("gone")

            assertEquals(listOf("gone.jpg"), photos, "$schema: the caller needs the files to delete")
            assertNull(reads.garment("gone"), schema)
            assertNotNull(reads.garment("keep"), "$schema: the other garment survives")
            assertEquals(
                emptyList(),
                driver.query("SELECT * FROM garment_pair_scores"),
                "$schema: learned scores referencing it should be gone"
            )

            val remaining = OutfitQueries(driver).all()
            assertEquals(listOf("pair"), remaining.map { it.id }, "$schema: the emptied outfit should be deleted")
            assertEquals(listOf("keep"), remaining.single().garmentIds, schema)
        }
    }

    @Test
    fun `a failed multi-statement write leaves nothing behind`() {
        eachSchema { schema, driver, reads, _ ->
            GarmentWrites(driver).insert(newGarment("g1"))

            // The TypeScript issues these as separate statements, so a failure
            // partway through leaves the wardrobe half-changed.
            assertFailsWith<IllegalStateException> {
                driver.transaction {
                    driver.execute("DELETE FROM garments WHERE id = ?", listOf("g1"))
                    error("something went wrong after the first statement")
                }
            }

            assertNotNull(reads.garment("g1"), "$schema: the delete should have rolled back")
        }
    }

    @Test
    fun `rating an outfit teaches its pairs, and correcting it does not double-count`() {
        eachSchema { schema, driver, _, writes ->
            writes.insert(newGarment("a"))
            writes.insert(newGarment("b"))
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "o1", name = "Fit", garmentIds = listOf("a", "b"), now = now)

            outfits.rate(ratingId = "r1", outfitId = "o1", rating = 5, now = now)
            val afterFive = driver.query("SELECT score, wear_count FROM garment_pair_scores").single()
            assertEquals(1, (afterFive["wear_count"] as Number).toInt(), schema)

            // A correction, not a second opinion: one wear, and the score lands
            // where rating 1 alone would have put it.
            outfits.rate(ratingId = "r2", outfitId = "o1", rating = 1, now = "2026-05-05")
            val afterCorrection = driver.query("SELECT score, wear_count FROM garment_pair_scores").single()
            assertEquals(1, (afterCorrection["wear_count"] as Number).toInt(), "$schema: still one wear")

            val expected = com.wardrobapp.domain.foldRatingIntoPair(null, 1).score
            assertTrue(
                kotlin.math.abs((afterCorrection["score"] as Number).toDouble() - expected) < 1e-9,
                "$schema: expected $expected, got ${afterCorrection["score"]}"
            )

            // And exactly one rating row survives.
            assertEquals(1, driver.query("SELECT * FROM outfit_ratings").size, schema)
            assertEquals(1, OutfitQueries(driver).rating("o1")?.rating, schema)
        }
    }

    @Test
    fun `pair scores load as a lookup that ignores id order`() {
        eachSchema { schema, driver, _, writes ->
            writes.insert(newGarment("a"))
            writes.insert(newGarment("b"))
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "o1", name = "Fit", garmentIds = listOf("b", "a"), now = now)
            outfits.rate(ratingId = "r1", outfitId = "o1", rating = 5, now = now)

            val lookup = OutfitQueries(driver).pairScores()
            assertEquals(
                lookup.score("a", "b"),
                lookup.score("b", "a"),
                "$schema: a pair must score the same either way round"
            )
            assertTrue(lookup.score("a", "b") > 0, schema)
            assertEquals(0.0, lookup.score("a", "unknown"), "$schema: an untaught pair scores zero")
        }
    }

    @Test
    fun `outfits come back pinned first, then newest`() {
        eachSchema { schema, driver, _, _ ->
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "old", name = "Old", garmentIds = listOf("a"), now = "2026-01-01")
            outfits.insert(id = "new", name = "New", garmentIds = listOf("a"), now = "2026-06-01")
            outfits.insert(id = "pinned", name = "Pinned", garmentIds = listOf("a"), now = "2026-02-01")
            outfits.setPinned("pinned", true)

            assertEquals(listOf("pinned", "new", "old"), OutfitQueries(driver).all().map { it.id }, schema)
        }
    }

    @Test
    fun `saving the same suggestion twice saves it once`() {
        // How a suggestion is saved: its id is minted when the batch is
        // produced, so tapping "save" and then rating it -- which has to save it
        // first -- is the same request twice.
        eachSchema { schema, driver, _, _ ->
            val outfits = OutfitWrites(driver)

            val written = outfits.insertIfAbsent(
                id = "s1", name = "Suggested fit", garmentIds = listOf("a", "b"),
                isSuggested = true, now = now,
            )
            val again = outfits.insertIfAbsent(
                id = "s1", name = "Suggested fit", garmentIds = listOf("a", "b"),
                isSuggested = true, now = now,
            )

            assertEquals(true, written, schema)
            assertEquals(false, again, "the second save reported writing a row: $schema")
            assertEquals(1, OutfitQueries(driver).all().size, schema)
        }
    }

    @Test
    fun `a second save does not overwrite what the first wrote`() {
        // Nor quietly rewrite it: an outfit already saved and since renamed or
        // pinned must not be reset by a stray second tap.
        eachSchema { schema, driver, _, _ ->
            val outfits = OutfitWrites(driver)
            outfits.insertIfAbsent(id = "s1", name = "As saved", garmentIds = listOf("a"), now = now)
            outfits.setPinned("s1", true)

            outfits.insertIfAbsent(
                id = "s1", name = "Different name", garmentIds = listOf("z"),
                now = "2030-01-01T00:00:00.000Z",
            )

            val stored = OutfitQueries(driver).outfit("s1")!!
            assertEquals("As saved", stored.name, schema)
            assertEquals(listOf("a"), stored.garmentIds, schema)
            assertEquals(true, stored.isPinned, schema)
        }
    }

    @Test
    fun `rating a suggestion that was already saved keeps one outfit and one rating`() {
        // The whole path the outfits screen takes: save-if-absent, then rate.
        eachSchema { schema, driver, _, _ ->
            val outfits = OutfitWrites(driver)

            repeat(3) {
                outfits.insertIfAbsent(
                    id = "s1", name = "Suggested fit", garmentIds = listOf("a", "b"),
                    isSuggested = true, now = now,
                )
                outfits.rate(ratingId = "r$it", outfitId = "s1", rating = it + 2, now = now)
            }

            assertEquals(1, OutfitQueries(driver).all().size, schema)
            assertEquals(4, OutfitQueries(driver).rating("s1")?.rating, schema)
            assertEquals(1, driver.query("SELECT * FROM outfit_ratings").size, schema)
        }
    }

    @Test
    fun `deleting an outfit takes its rating with it`() {
        eachSchema { schema, driver, _, _ ->
            val outfits = OutfitWrites(driver)
            outfits.insert(id = "o1", name = "Fit", garmentIds = listOf("a", "b"), now = now)
            outfits.rate(ratingId = "r1", outfitId = "o1", rating = 4, now = now)

            outfits.delete("o1")

            assertNull(OutfitQueries(driver).outfit("o1"), schema)
            assertEquals(emptyList(), driver.query("SELECT * FROM outfit_ratings"), schema)
        }
    }
}
