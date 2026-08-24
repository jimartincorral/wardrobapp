package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turning a database row into a garment.
 *
 * Every garment read passes through here, and every rule exists because some real
 * row needed it: list columns hold a JSON array in rows written by current
 * builds, a bare comma-separated string in rows written by much older ones, and
 * sometimes nothing at all. A column declared TEXT can hold a number. The single
 * and list forms of the colour and photo columns can each be the populated one.
 *
 * This is the most compatibility-critical code in the app -- it is what decides
 * whether a wardrobe from 2025 still reads -- so the shapes below are the shapes
 * that exist rather than a sample of them. They replace 93 recorded rows, whose
 * answers came from the app that wrote them.
 */
class GarmentRowsTest {

    private val directory = "file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/"

    private fun read(vararg columns: Pair<String, Any?>) =
        normalizeGarmentRow(mapOf("id" to "g1", "category" to "tops", *columns), directory)

    @Test
    fun `a row from a current build reads as itself`() {
        val record = read(
            "image_uri" to "front.jpg",
            "image_uris" to """["front.jpg","back.jpg"]""",
            "subcategories" to """["T-Shirt"]""",
            "tags" to """["summer","favourite"]""",
            "color_palette" to """["#1F3A93","#FFFFFF"]""",
            "color_primary" to "#1F3A93",
            "brand" to "Uniqlo",
            "size" to "M",
            "is_available" to 1,
            "created_at" to "2026-01-01T00:00:00.000Z",
        )

        assertEquals(listOf("${directory}front.jpg", "${directory}back.jpg"), record.imageUris)
        assertEquals(listOf("T-Shirt"), record.subcategories)
        assertEquals(listOf("summer", "favourite"), record.tags)
        assertEquals(listOf("#1F3A93", "#FFFFFF"), record.colorPalette)
        assertEquals("#1F3A93", record.colorPrimary)
        assertEquals("Uniqlo", record.brand)
        assertTrue(record.isAvailable)
    }

    @Test
    fun `a comma-separated list from the oldest builds still reads`() {
        // Not JSON at all: the first version of the app stored these as text.
        val record = read(
            "image_uri" to "front.jpg",
            "subcategories" to "T-Shirt, Polo",
            "tags" to "summer, favourite",
            "color_palette" to "#1F3A93, #FFFFFF",
        )

        assertEquals(listOf("T-Shirt", "Polo"), record.subcategories)
        assertEquals(listOf("summer", "favourite"), record.tags)
        assertEquals(listOf("#1F3A93", "#FFFFFF"), record.colorPalette)
    }

    @Test
    fun `an empty or absent list column reads as no entries`() {
        val record = read("image_uri" to "front.jpg", "tags" to "", "subcategories" to null)

        assertEquals(emptyList(), record.tags)
        assertEquals(emptyList(), record.subcategories)
        assertEquals(null, record.subcategory)
    }

    @Test
    fun `a list column holding something that is not a list reads as no entries`() {
        // JSON, but not an array: a bare number or string. Falling through to the
        // comma split here would turn "42" into a tag.
        assertEquals(emptyList(), read("image_uri" to "a.jpg", "tags" to "42").tags)
        assertEquals(emptyList(), read("image_uri" to "a.jpg", "tags" to "\"summer\"").tags)
    }

    @Test
    fun `the single-value column and the list one are folded together`() {
        // Either can be the populated one, depending on which build wrote the row.
        val fromSingle = read("image_uri" to "front.jpg", "subcategory" to "Shirt")
        assertEquals(listOf("${directory}front.jpg"), fromSingle.imageUris)
        assertEquals(listOf("Shirt"), fromSingle.subcategories)

        val fromBoth = read(
            "image_uri" to "front.jpg",
            "image_uris" to """["back.jpg"]""",
            "subcategory" to "Shirt",
            "subcategories" to """["T-Shirt"]""",
        )
        assertEquals(
            listOf("${directory}back.jpg", "${directory}front.jpg"),
            fromBoth.imageUris,
        )
        assertEquals(listOf("T-Shirt", "Shirt"), fromBoth.subcategories)
        // The list's first entry wins as the single value, so the two agree.
        assertEquals("T-Shirt", fromBoth.subcategory)
    }

    @Test
    fun `the same value written twice appears once`() {
        // Two spellings of one photo, or a colour listed in both columns.
        val record = read(
            "image_uri" to "Front.JPG",
            "image_uris" to """["front.jpg","front.jpg"]""",
            "color_primary" to "#1f3a93",
            "color_palette" to """["#1F3A93"]""",
        )

        assertEquals(1, record.imageUris.size)
        assertEquals(listOf("#1F3A93"), record.colorPalette)
    }

    @Test
    fun `a cut-out list keeps its gaps`() {
        // The indices line up with the photo list, so a garment whose second
        // photo has no cut-out must keep the hole rather than shift the rest.
        val record = read(
            "image_uris" to """["a.jpg","b.jpg","c.jpg"]""",
            "image_uris_nobg" to """["a-cut.png","","c-cut.png"]""",
        )

        assertEquals(3, record.imageUrisNoBg.size)
        assertEquals("", record.imageUrisNoBg[1])
    }

    @Test
    fun `a number in a text column becomes text rather than throwing`() {
        // A column declared TEXT can hold a number, and a numeric size used to
        // reach duplicate detection and throw there: optional chaining guards
        // null but not a missing method.
        val record = read("image_uri" to "a.jpg", "size" to 42, "brand" to 7)

        assertEquals("42", record.size)
        assertEquals("7", record.brand)
    }

    @Test
    fun `availability is read the way SQLite writes it`() {
        // The column is an INTEGER, and older rows also hold nothing at all.
        assertTrue(read("image_uri" to "a.jpg", "is_available" to 1).isAvailable)
        assertTrue(!read("image_uri" to "a.jpg", "is_available" to 0).isAvailable)
        assertTrue(!read("image_uri" to "a.jpg", "is_available" to null).isAvailable)
    }

    @Test
    fun `a garment with no timestamps reads as having none`() {
        // The upgraded-install shape: SQLite cannot add a NOT NULL column
        // without a default, so these really are absent on real phones -- and
        // the list ordering depends on being able to tell.
        val record = read("image_uri" to "a.jpg")

        assertEquals(null, record.createdAt)
        assertEquals(null, record.updatedAt)
    }

    @Test
    fun `a garment always has a colour to draw`() {
        val record = read("image_uri" to "a.jpg", "color_primary" to "#000000")

        assertEquals("#000000", record.colorPrimary)
        assertEquals(listOf("#000000"), record.colorPalette)
    }

    @Test
    fun `a portable reference is not reduced to a filename on the way in`() {
        val record = read(
            "image_uris" to """["content://media/external/images/1000","https://cdn.example.com/a.jpg"]"""
        )

        assertEquals(
            listOf("content://media/external/images/1000", "https://cdn.example.com/a.jpg"),
            record.imageUris,
        )
    }
}
