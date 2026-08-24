package com.wardrobapp.domain

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
class GarmentCatalogueTest {

    @Test
    fun `every garment type on offer has a translation key`() {
        // The map and the catalogue are two lists that have to stay in step, so
        // this is the invariant a type would have carried if a subcategory were a
        // label and a key together rather than a bare string. Without it, adding a
        // type here and forgetting the map shows it untranslated -- which reads as
        // a translation gap rather than a missing entry.
        val offered = GARMENT_CATEGORIES.flatMap { it.subcategories }.toSet()

        assertEquals(emptySet(), offered - SUBCATEGORY_KEYS.keys, "no translation key for")
        assertEquals(emptySet(), SUBCATEGORY_KEYS.keys - offered, "keyed but not offered")
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
