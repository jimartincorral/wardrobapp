package com.wardrobapp.domain

import com.wardrobapp.parity.Parity
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The categories and sizes the form offers, against the TypeScript's list.
 *
 * Compared rather than trusted to a careful transcription, because the strings
 * do more than fill a picker: a subcategory is stored verbatim and looked up by
 * name when a garment's occasions are derived. A typo would not fail -- it would
 * quietly give the garment its category's fallback occasions instead of its
 * type's, which is a wrong answer that looks like a right one.
 */
class GarmentCatalogueParityTest {

    @Test
    fun `offers exactly the categories the TypeScript does`() {
        val expected = Parity.load("garment-catalogue.jsonl")

        assertEquals(
            expected.map { it.string("id") },
            GARMENT_CATEGORIES.map { it.id },
            "the categories, or their order, differ",
        )

        for ((entry, category) in expected.zip(GARMENT_CATEGORIES)) {
            assertEquals(entry.string("label"), category.label, "label for ${category.id}")
            assertEquals(
                entry.strings("subcategories"),
                category.subcategories,
                "subcategories for ${category.id}",
            )
        }
    }

    @Test
    fun `offers exactly the sizes the TypeScript does`() {
        val expected = Parity.load("garment-catalogue.jsonl")
            .first { it["sizes"] != null }
            .strings("sizes")

        assertEquals(expected, COMMON_SIZES)
        assertTrue(COMMON_SIZES.size > SIZE_CHIPS, "every size fits in the chip row, so nothing is typed")
    }

    @Test
    fun `every type it offers is one the occasion table was asked about`() {
        // The two lists are transcribed separately. If they drift, picking a type
        // silently falls back to its category's occasions -- so this closes the
        // loop: the occasions corpus enumerates every subcategory the TypeScript
        // offers, and every type offered here has to be in it.
        val asked = Parity.load("occasions.jsonl")
            .flatMap { it.strings("subcategories") }
            .toSet()

        val offered = GARMENT_CATEGORIES.flatMap { it.subcategories }.toSet()
        val unknown = offered - asked

        assertEquals(emptySet(), unknown, "types offered that the occasion corpus never covers")
    }

    @Test
    fun `names no type twice within a category`() {
        for (category in GARMENT_CATEGORIES) {
            assertEquals(
                category.subcategories.size,
                category.subcategories.toSet().size,
                "${category.id} lists a type twice",
            )
        }
    }

    @Test
    fun `starts a new garment in a category it offers`() {
        assertTrue(garmentCategory(DEFAULT_CATEGORY) != null, "the default category is not offered")
    }

    @Test
    fun `finds nothing for a category it does not have`() {
        assertEquals(null, garmentCategory("spacesuits"))
    }
}
