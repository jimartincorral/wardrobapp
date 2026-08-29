package com.wardrobapp.data

import com.wardrobapp.domain.DuplicateCandidate
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

    private val driver = JdbcSqlDriver.fresh()
    private val subject = Duplicates(GarmentQueries(driver, "file:///photos/"))

    @AfterTest
    fun close() = driver.close()

    private fun addGarment(
        id: String,
        category: String = "tops",
        subcategory: String = "T-Shirt",
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
                id, "$id.jpg", "[\"$id.jpg\"]", category, subcategory, jsonArray(listOf(subcategory)),
                jsonArray(tags), color, "[\"$color\"]", size,
                if (available) 1 else 0, "2026-01-01", "2026-01-01",
            ),
        )
    }

    private val candidate = DuplicateCandidate(
        category = "tops",
        // Matching what `addGarment` writes: a duplicate has to be the same kind
        // of thing, so a candidate with no type would match nothing at all.
        subcategories = listOf("T-Shirt"),
        colorPrimary = "#000000",
        colorPalette = listOf("#000000"),
    )

    @Test
    fun `finds a garment that is already there`() {
        addGarment("twin")

        val matches = subject.matching(candidate)

        assertEquals(listOf("twin"), matches.map { it.garment.id })
    }

    @Test
    fun `hands back the photos a warning needs`() {
        // The rule works on the domain type, which has no photo. A warning
        // showing "you may already own this" with a blank tile is no warning.
        addGarment("twin")

        val match = subject.matching(candidate).single()

        assertEquals("file:///photos/twin.jpg", match.garment.displayImage)
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
        // Only the same category is loaded, so black trousers filed as a
        // "T-Shirt" by some import are not a duplicate of a black t-shirt.
        addGarment("trousers", category = "bottoms")

        assertEquals(emptyList(), subject.matching(candidate))
    }

    @Test
    fun `ignores a garment that shares nothing but its category`() {
        addGarment("unrelated", subcategory = "Jumper", color = "#FFFFFF")

        assertEquals(emptyList(), subject.matching(candidate))
    }

    @Test
    fun `every match comes back, in the order the wardrobe holds them`() {
        // Nothing to rank by: each of these satisfies the same rule to the same
        // degree, and an order would imply one is more of a duplicate than another.
        addGarment("first")
        addGarment("second")

        assertEquals(listOf("first", "second"), subject.matching(candidate).map { it.garment.id })
    }

    @Test
    fun `finds nothing in an empty wardrobe`() {
        assertEquals(emptyList(), subject.matching(candidate))
    }
    @Test
    fun `the sweep gathers garments already saved`() {
        // Nothing else ever asks: the warning fires when a garment is added, so
        // three shirts added on three different days have never been compared.
        addGarment("g1")
        addGarment("g2")
        addGarment("g3")

        val groups = subject.groups()

        assertEquals(1, groups.size)
        assertEquals(listOf("g1", "g2", "g3"), groups.single().garments.map { it.id }.sorted())
    }

    @Test
    fun `a retired garment is not something you own twice`() {
        // The rule `matching` already keeps, for the same reason: a garment
        // deliberately put away is not a garment sitting in the wardrobe twice.
        addGarment("kept1")
        addGarment("kept2")
        addGarment("retired", available = false)

        val groups = subject.groups()

        assertEquals(listOf("kept1", "kept2"), groups.single().garments.map { it.id }.sorted())
    }

    @Test
    fun `the sweep hands back the photos a list has to show`() {
        // The reason this maps ids back to records at all: the domain works in
        // garments that have no idea where their pictures live.
        addGarment("g1")
        addGarment("g2")

        val shown = subject.groups().single().garments

        assertTrue(shown.all { it.imageUri.isNotBlank() }, "a group came back with nothing to draw")
    }

    @Test
    fun `an empty wardrobe sweeps to nothing rather than failing`() {
        assertEquals(emptyList(), subject.groups())
    }

    @Test
    fun `two categories that look alike stay apart`() {
        // The scoring never looks at the category, so without bucketing these
        // four would arrive as one group saying socks are shirts.
        addGarment("shirt1", category = "tops")
        addGarment("shirt2", category = "tops")
        addGarment("sock1", category = "socks")
        addGarment("sock2", category = "socks")

        val groups = subject.groups()

        assertEquals(2, groups.size)
        assertTrue(
            groups.all { it.garments.map { g -> g.category }.distinct().size == 1 },
            "a group spanned two categories",
        )
    }

    @Test
    fun `a shirt and a jumper are not each other`() {
        // The comparison the sweep was making before there was a gate: same
        // category, same colour, same tags, same size.
        addGarment("shirt1", subcategory = "T-Shirt")
        addGarment("shirt2", subcategory = "T-Shirt")
        addGarment("jumper", subcategory = "Jumper")

        val groups = subject.groups()

        assertEquals(listOf("shirt1", "shirt2"), groups.single().garments.map { it.id }.sorted())
    }

    @Test
    fun `the same shirt in two colours is two shirts`() {
        addGarment("navy", color = "#1F3A93")
        addGarment("red", color = "#B22222")

        assertEquals(emptyList(), subject.groups())
    }

}
