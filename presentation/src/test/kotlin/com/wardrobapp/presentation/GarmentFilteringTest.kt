package com.wardrobapp.presentation

import com.wardrobapp.data.normalizeGarmentRow
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Narrowing and ordering the wardrobe list.
 *
 * Filters combine, so the interesting failures are the ones where a filter is
 * ignored rather than the ones where it is wrong: a screen showing more than it
 * says it is showing. The ordering has a nastier failure behind it -- an install
 * upgraded through the ALTER path can have no `created_at` at all, and the
 * TypeScript this was ported from dereferenced it and threw, which the list hook
 * swallowed into an *empty wardrobe*.
 */
class GarmentFilteringTest {

    private fun garment(
        id: String,
        subcategories: List<String> = listOf("T-Shirt"),
        tags: List<String> = emptyList(),
        brand: String? = null,
        size: String? = null,
        palette: List<String> = listOf("#1F3A93"),
        createdAt: String? = "2026-01-01T00:00:00.000Z",
        category: String = "tops",
    ) = normalizeGarmentRow(
        mapOf(
            "id" to id,
            "image_uri" to "$id.jpg",
            "category" to category,
            "subcategories" to subcategories.joinToString(",", "[\"", "\"]"),
            "tags" to (if (tags.isEmpty()) "[]" else tags.joinToString("\",\"", "[\"", "\"]")),
            "brand" to brand,
            "size" to size,
            "color_primary" to palette.first(),
            "color_palette" to palette.joinToString("\",\"", "[\"", "\"]"),
            "created_at" to createdAt,
        ),
        "",
    )

    private val wardrobe = listOf(
        garment("shirt", listOf("Shirt"), tags = listOf("winter"), brand = "Uniqlo", size = "M"),
        garment("tee", listOf("T-Shirt"), tags = listOf("summer"), brand = "Arket", size = "L"),
        garment("blouse", listOf("Blouse"), brand = "arket", size = "m", palette = listOf("#C0392B")),
    )

    @Test
    fun `no filter is not a filter`() {
        assertEquals(wardrobe.map { it.id }, wardrobe.filterBy(GarmentFilter()).map { it.id })
    }

    @Test
    fun `each filter narrows by the thing it names`() {
        assertEquals(listOf("tee"), wardrobe.filterBy(GarmentFilter(subcategory = "T-Shirt")).map { it.id })
        assertEquals(listOf("shirt"), wardrobe.filterBy(GarmentFilter(season = Season.WINTER)).map { it.id })
        assertEquals(listOf("blouse"), wardrobe.filterBy(GarmentFilter(color = "#C0392B")).map { it.id })
    }

    @Test
    fun `an occasion is what the garment is, not what it was tagged`() {
        // Nothing here carries an occasion tag; a blouse is formal because of what
        // it is.
        assertEquals(listOf("blouse"), wardrobe.filterBy(GarmentFilter(occasion = Occasion.FORMAL)).map { it.id })
    }

    @Test
    fun `brand and size match loosely, because they are typed by hand`() {
        // Case and partial words, so "ark" finds Arket however it was capitalised.
        assertEquals(listOf("tee", "blouse"), wardrobe.filterBy(GarmentFilter(brand = "ark")).map { it.id })
        assertEquals(listOf("shirt", "blouse"), wardrobe.filterBy(GarmentFilter(size = "M")).map { it.id })
        // A blank needle is not a filter at all rather than matching nothing.
        assertEquals(3, wardrobe.filterBy(GarmentFilter(brand = "  ")).size)
    }

    @Test
    fun `filters combine`() {
        assertEquals(
            listOf("blouse"),
            wardrobe.filterBy(GarmentFilter(brand = "arket", size = "m")).map { it.id },
        )
        // And a combination nothing satisfies returns nothing rather than the
        // last filter's answer.
        assertEquals(
            emptyList(),
            wardrobe.filterBy(GarmentFilter(brand = "uniqlo", size = "L")).map { it.id },
        )
    }

    @Test
    fun `newest first, oldest last, and back again`() {
        val list = listOf(
            garment("old", createdAt = "2025-01-01T00:00:00.000Z"),
            garment("new", createdAt = "2026-06-01T00:00:00.000Z"),
            garment("middle", createdAt = "2025-09-01T00:00:00.000Z"),
        )

        assertEquals(listOf("new", "middle", "old"), list.orderedBy(GarmentSort.NEWEST).map { it.id })
        assertEquals(listOf("old", "middle", "new"), list.orderedBy(GarmentSort.OLDEST).map { it.id })
        // Newest is the default, because that is what the screen opens on.
        assertEquals(list.orderedBy(GarmentSort.NEWEST).map { it.id }, list.orderedBy().map { it.id })
    }

    @Test
    fun `a garment with no timestamp sorts last rather than disappearing`() {
        // The failure this replaces was not a wrong order: it was an exception
        // that emptied the whole screen.
        val list = listOf(
            garment("dated", createdAt = "2026-01-01T00:00:00.000Z"),
            garment("undated", createdAt = null),
        )

        assertEquals(listOf("dated", "undated"), list.orderedBy(GarmentSort.NEWEST).map { it.id })
        assertEquals(listOf("undated", "dated"), list.orderedBy(GarmentSort.OLDEST).map { it.id })
    }
}
