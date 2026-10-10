package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attributes as tags, and what a garment has when nobody set any.
 */
class GarmentAttributesTest {

    @Test
    fun `attributes travel as prefixed tags and come back the same`() {
        val set = GarmentAttributes(
            formality = Formality.SMART,
            pattern = Pattern.PRINT,
            fit = Fit.RELAXED,
            weight = Weight.LIGHT,
            statement = true,
        )

        assertEquals(
            listOf("formality:smart", "pattern:print", "fit:relaxed", "weight:light", "statement:yes"),
            set.toTags(),
        )
        assertEquals(set, GarmentAttributes.fromTags(set.toTags()))
    }

    @Test
    fun `an attribute nobody set is unset, and a value this build does not know is too`() {
        val read = GarmentAttributes.fromTags(listOf("linen", "formality:black-tie", "pattern:checks"))

        assertNull(read.formality, "an unknown level was read as something")
        assertEquals(Pattern.CHECKS, read.pattern)
        assertNull(read.fit)
    }

    @Test
    fun `the split keeps attributes apart from what was typed, and the merge puts them back`() {
        val (customTags, seasons, attributes) = splitStructuredTags(
            listOf("cotton", "summer", "formality:casual", "statement:no"),
        )

        assertEquals(listOf("cotton"), customTags)
        assertEquals(listOf(Season.SUMMER), seasons)
        assertEquals(GarmentAttributes(formality = Formality.CASUAL, statement = false), attributes)

        val merged = mergeStructuredTags(customTags, seasons, attributes)
        assertEquals(listOf("cotton", "summer", "formality:casual", "statement:no"), merged)
    }

    @Test
    fun `clearing an attribute in the form clears its tag`() {
        // The typed tags handed back from the split never carry an attribute,
        // but a caller that passed the raw tags would: the attributes given are
        // the whole truth, and the old tag must not survive beside them.
        val merged = mergeStructuredTags(listOf("wool", "formality:formal"), emptyList(), GarmentAttributes.NONE)

        assertEquals(listOf("wool"), merged)
    }

    @Test
    fun `a garment's type says what it is like until somebody says otherwise`() {
        val hoodie = Garment(id = "h", category = "tops", subcategory = "Hoodie", colorPrimary = "#000000")

        assertEquals(Formality.CASUAL, hoodie.attributes.formality)
        assertEquals(Weight.HEAVY, hoodie.attributes.weight)
        assertEquals(Fit.RELAXED, hoodie.attributes.fit)
        // What the type does not imply, the defaults fill, so nothing is null.
        assertEquals(Pattern.SOLID, hoodie.attributes.pattern)
        assertEquals(false, hoodie.attributes.statement)
        assertTrue(hoodie.explicitAttributes.isEmpty)
    }

    @Test
    fun `what was set wins over what the type implies`() {
        val dressyHoodie = Garment(
            id = "h", category = "tops", subcategory = "Hoodie", colorPrimary = "#000000",
            tags = listOf("formality:smart"),
        )

        assertEquals(Formality.SMART, dressyHoodie.attributes.formality)
        assertEquals(Weight.HEAVY, dressyHoodie.attributes.weight)
    }

    @Test
    fun `every type the catalogue knows has a formality and a weight of its own or its category's`() {
        for (category in GARMENT_CATEGORIES) {
            for (type in category.subcategories) {
                val attributes = defaultAttributesFor(category.id, listOf(type))
                assertTrue(attributes.formality != null && attributes.weight != null, "$type has no default")
            }
        }
    }
}
