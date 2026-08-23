package com.wardrobapp.data

import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.DuplicateReason
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which garments a new one is compared against.
 *
 * The scoring is checked against the TypeScript elsewhere, over 60 scenarios.
 * What is checked here is the part only this class decides: the candidate set.
 * Getting that wrong is invisible -- the warning simply does not appear, or
 * appears about the wrong garment.
 */
class DuplicatesTest {

    private val driver = JdbcSqlDriver.fromSchemaFixture("schema-fresh.sql")
    private val subject = Duplicates(GarmentQueries(driver, "file:///photos/"))

    @AfterTest
    fun close() = driver.close()

    private fun addGarment(
        id: String,
        category: String = "tops",
        tags: List<String> = listOf("cotton", "basic"),
        color: String = "#000000",
        size: String? = "M",
        available: Boolean = true,
    ) {
        driver.execute(
            "INSERT INTO garments (id, image_uri, image_uris, category, subcategory, " +
                "subcategories, tags, color_primary, color_palette, size, is_available, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                id, "$id.jpg", "[\"$id.jpg\"]", category, "T-Shirt", "[\"T-Shirt\"]",
                jsonArray(tags), color, "[\"$color\"]", size,
                if (available) 1 else 0, "2026-01-01", "2026-01-01",
            ),
        )
    }

    private val candidate = DuplicateCandidate(
        category = "tops",
        tags = listOf("cotton", "basic"),
        colorPrimary = "#000000",
        colorPalette = listOf("#000000"),
        size = "M",
    )

    @Test
    fun `finds a garment that is already there`() {
        addGarment("twin")

        val matches = subject.matching(candidate)

        assertEquals(listOf("twin"), matches.map { it.garment.id })
        assertTrue(matches.single().score > 0.99, "an exact duplicate scored ${matches.single().score}")
    }

    @Test
    fun `hands back the photos a warning needs`() {
        // The scoring works on the domain type, which has no photo. A warning
        // showing "you may already own this" with a blank tile is no warning.
        addGarment("twin")

        val match = subject.matching(candidate).single()

        assertEquals("file:///photos/twin.jpg", match.garment.displayImage)
    }

    @Test
    fun `says why`() {
        addGarment("twin")

        val reasons = subject.matching(candidate).single().reasons

        assertTrue(DuplicateReason.SIMILAR_TAGS in reasons, "reasons were $reasons")
        assertTrue(DuplicateReason.SAME_SIZE in reasons, "reasons were $reasons")
    }

    @Test
    fun `never warns about a garment that is no longer in use`() {
        // A retired garment is not something you already own, and warning about
        // one sends someone looking for a garment they deliberately put away.
        addGarment("retired", available = false)

        assertEquals(emptyList(), subject.matching(candidate))
    }

    @Test
    fun `does not compare across categories`() {
        // Only the same category is loaded, so a pair of black trousers tagged
        // like a black t-shirt is not a duplicate of it.
        addGarment("trousers", category = "bottoms")

        assertEquals(emptyList(), subject.matching(candidate))
    }

    @Test
    fun `ignores a garment that shares nothing but its category`() {
        addGarment("unrelated", tags = listOf("wool", "formal"), color = "#FFFFFF", size = "XL")

        assertEquals(emptyList(), subject.matching(candidate))
    }

    @Test
    fun `reports the closest first`() {
        addGarment("partial", tags = listOf("cotton"))
        addGarment("exact", tags = listOf("cotton", "basic"))

        // Threshold lowered so both are reported and the order is what is tested.
        val matches = subject.matching(candidate, threshold = -1.0)

        assertEquals(listOf("exact", "partial"), matches.map { it.garment.id })
        assertTrue(matches[0].score > matches[1].score)
    }

    @Test
    fun `finds nothing in an empty wardrobe`() {
        assertEquals(emptyList(), subject.matching(candidate))
    }
}
