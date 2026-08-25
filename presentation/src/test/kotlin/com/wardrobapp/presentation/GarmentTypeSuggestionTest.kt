package com.wardrobapp.presentation

import com.wardrobapp.domain.GARMENT_CATEGORIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a photo's labels come to in this app's vocabulary.
 *
 * The model itself cannot be tested here and largely should not be -- what it sees
 * is its business. What is testable is everything around it, and that is where the
 * mistakes would be: reading a label the app has no meaning for, filling in detail
 * the model did not report, letting a low-confidence guess decide, or naming a type
 * that is not in the category it was filed under.
 */
class GarmentTypeSuggestionTest {

    @Test
    fun `the most confident label the app understands decides`() {
        val suggestion = suggestGarmentType(
            listOf(
                ImageLabel("Jeans", 0.91f),
                ImageLabel("Shirt", 0.62f),
            ),
        )

        assertEquals(GarmentTypeSuggestion("bottoms", "Jeans"), suggestion)
    }

    @Test
    fun `labels the app has no meaning for are skipped rather than stopping the search`() {
        // Every photograph of a garment scores these, and they are the first things
        // the model says. Returning nothing because the top label was "Person"
        // would make the button useless on exactly the photos it is for.
        val suggestion = suggestGarmentType(
            listOf(
                ImageLabel("Person", 0.95f),
                ImageLabel("Clothing", 0.93f),
                ImageLabel("Sleeve", 0.88f),
                ImageLabel("Blazer", 0.71f),
            ),
        )

        assertEquals(GarmentTypeSuggestion("midlayer", "Blazer"), suggestion)
    }

    @Test
    fun `a photo of nothing this app files gives no suggestion`() {
        val suggestion = suggestGarmentType(
            listOf(ImageLabel("Room", 0.9f), ImageLabel("Furniture", 0.8f)),
        )

        assertNull(suggestion)
        assertNull(suggestGarmentType(emptyList()))
    }

    @Test
    fun `a label the model is unsure of is not read at all`() {
        // 0.5 is the floor, so this is the pair that has to behave differently: the
        // same label, once under and once at, is nothing and then an answer.
        assertNull(suggestGarmentType(listOf(ImageLabel("Jeans", 0.49f))))
        assertEquals(
            GarmentTypeSuggestion("bottoms", "Jeans"),
            suggestGarmentType(listOf(ImageLabel("Jeans", 0.5f))),
        )
    }

    @Test
    fun `a general label is narrowed by a less confident specific one`() {
        // The common real shape: the model is surer that it is footwear than that it
        // is a sneaker, and both are true.
        val suggestion = suggestGarmentType(
            listOf(
                ImageLabel("Footwear", 0.94f),
                ImageLabel("Sneakers", 0.63f),
            ),
        )

        assertEquals(GarmentTypeSuggestion("shoes", "Sneakers"), suggestion)
    }

    @Test
    fun `narrowing never crosses into another category`() {
        // "Footwear" and "Jeans" in one photo is a photo of an outfit, or a mistake.
        // Either way the shoes were the confident part, and answering "shoes, and
        // the type is Jeans" would be neither label.
        val suggestion = suggestGarmentType(
            listOf(
                ImageLabel("Footwear", 0.94f),
                ImageLabel("Jeans", 0.71f),
            ),
        )

        assertEquals(GarmentTypeSuggestion("shoes", null), suggestion)
    }

    @Test
    fun `a category with no type is an answer, not a failure`() {
        assertEquals(GarmentTypeSuggestion("dresses", null), suggestGarmentType(listOf(ImageLabel("Dress", 0.8f))))
    }

    @Test
    fun `punctuation and case in a label are not part of it`() {
        val expected = GarmentTypeSuggestion("tops", "T-Shirt")

        assertEquals(expected, suggestGarmentType(listOf(ImageLabel("T-Shirt", 0.8f))))
        assertEquals(expected, suggestGarmentType(listOf(ImageLabel("t shirt", 0.8f))))
        assertEquals(expected, suggestGarmentType(listOf(ImageLabel("TSHIRT", 0.8f))))
    }

    @Test
    fun `equally confident labels keep the order they arrived in`() {
        // Two labels at the same confidence is not rare, and the answer must not
        // depend on how the sort happens to break the tie.
        val labels = listOf(ImageLabel("Skirt", 0.8f), ImageLabel("Coat", 0.8f))

        assertEquals(GarmentTypeSuggestion("bottoms", "Skirt"), suggestGarmentType(labels))
        assertEquals(GarmentTypeSuggestion("outerwear", "Coat"), suggestGarmentType(labels.reversed()))
    }

    @Test
    fun `every entry in the table names a category and type this app actually has`() {
        // The reason this test exists rather than being assumed: a subcategory is
        // stored verbatim and looked up by name, so a typo here would not fail. It
        // would file a garment under a type that does not exist, and the form would
        // show no type selected at all.
        for ((label, suggestion) in LABEL_VOCABULARY) {
            val category = GARMENT_CATEGORIES.firstOrNull { it.id == suggestion.category }

            assertTrue(category != null, "\"$label\" names category \"${suggestion.category}\", which does not exist")
            if (suggestion.subcategory != null) {
                assertTrue(
                    suggestion.subcategory in category.subcategories,
                    "\"$label\" names type \"${suggestion.subcategory}\", " +
                        "which is not in ${category.id}: ${category.subcategories}",
                )
            }
        }
    }

    @Test
    fun `the table is keyed on normalized labels, so a lookup can never miss on spacing`() {
        // The keys are built by the same normalization a lookup uses. If a key ever
        // arrives with a capital or a hyphen in it, that entry is unreachable and
        // silently dead -- which is exactly the kind of thing nobody notices.
        for (key in LABEL_VOCABULARY.keys) {
            assertTrue(
                key.isNotEmpty() && key.all { it.isLetterOrDigit() && !it.isUpperCase() },
                "\"$key\" is not a normalized label, so nothing will ever match it",
            )
        }
    }
}
