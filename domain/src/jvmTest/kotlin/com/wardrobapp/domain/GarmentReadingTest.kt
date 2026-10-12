package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading what a model said about a garment: lenient about how it was said,
 * strict about what -- every value is held to the app's own vocabulary, and
 * anything outside it is dropped rather than stored.
 */
class GarmentReadingTest {

    @Test
    fun `a type is found however it is spelled, and only if it is in the catalogue`() {
        assertEquals(ReadType("tops", "T-Shirt"), garmentTypeNamed("t-shirt"))
        assertEquals(ReadType("tops", "T-Shirt"), garmentTypeNamed("T shirt."))
        assertEquals(ReadType("tops", "Tank Top"), garmentTypeNamed("TANK TOP"))
        assertEquals(ReadType("shoes", "Sneakers"), garmentTypeNamed("Sneaker"), "a singular for a plural label")
        assertEquals(ReadType("dresses", "Midi"), garmentTypeNamed("Midi Dress"), "the name the prompt uses")
        assertEquals(ReadType("dresses", "Midi"), garmentTypeNamed("midi"))
        assertNull(garmentTypeNamed("kilt"))
        assertNull(garmentTypeNamed(""))
    }

    @Test
    fun `a category on its own is a category with no type`() {
        assertEquals(ReadType("shoes", null), garmentTypeNamed("Shoes"))
        assertEquals(ReadType("dresses", null), garmentTypeNamed("dress"))
        assertEquals(ReadType("loungewear", null), garmentTypeNamed("pajamas"), "either half of a split label")
    }

    @Test
    fun `every type in the prompt is read back as itself`() {
        for (category in GARMENT_CATEGORIES) {
            for (label in category.subcategories) {
                val asked = garmentTypePromptName(category.id, label)
                assertEquals(ReadType(category.id, label), garmentTypeNamed(asked), "$asked did not come back as $label")
            }
        }
    }

    @Test
    fun `an answer in the asked-for shape is read line by line`() {
        val reading = parseGarmentReading(
            """
            type: Chinos
            pattern: solid
            formality: smart-casual
            fit: regular
            weight: mid
            material: cotton
            brand: Arket
            """.trimIndent(),
        )

        assertEquals(ReadType("bottoms", "Chinos"), reading.type)
        assertEquals(
            GarmentAttributes(formality = Formality.SMART_CASUAL, pattern = Pattern.SOLID, fit = Fit.REGULAR, weight = Weight.MID),
            reading.attributes,
        )
        assertEquals("cotton", reading.material)
        assertEquals("Arket", reading.brand)
    }

    @Test
    fun `what a model adds unasked does not change what it meant`() {
        val reading = parseGarmentReading(
            """
            Here is the description:
            - **Type:** Hoodie
            * Pattern: Print.
            FIT: Oversized
            material: 100% organic cotton
            """.trimIndent(),
        )

        assertEquals(ReadType("tops", "Hoodie"), reading.type)
        assertEquals(Pattern.PRINT, reading.attributes.pattern)
        assertEquals(Fit.OVERSIZED, reading.attributes.fit)
        assertEquals("cotton", reading.material)
    }

    @Test
    fun `values outside the vocabulary, and answers that say nothing, are dropped`() {
        val reading = parseGarmentReading(
            """
            type: kilt
            pattern: paisley
            formality: black tie
            material: cotton and polyester blend
            brand: none
            """.trimIndent(),
        )

        assertNull(reading.type)
        assertTrue(reading.attributes.isEmpty)
        assertNull(reading.material, "a blend of two is no one material")
        assertNull(reading.brand)
        assertTrue(parseGarmentReading("I cannot see any clothing in this image.").isEmpty)
    }

    @Test
    fun `a brand has to look like one`() {
        assertEquals("COS", brandNamed("\"COS\""))
        assertNull(brandNamed("Unknown"))
        assertNull(brandNamed("not visible"))
        assertNull(brandNamed("The label appears to say something illegible"))
        assertNull(brandNamed("X"))
    }

    @Test
    fun `one reading fills what another left open, and nothing it set`() {
        val page = GarmentReading(type = ReadType("bottoms", "Chinos"), material = "linen")
        val photo = GarmentReading(
            type = ReadType("bottoms", "Pants"),
            attributes = GarmentAttributes(pattern = Pattern.SOLID),
            material = "cotton",
            brand = "Arket",
        )

        val both = page.orElse(photo)

        assertEquals(ReadType("bottoms", "Chinos"), both.type)
        assertEquals("linen", both.material)
        assertEquals(Pattern.SOLID, both.attributes.pattern)
        assertEquals("Arket", both.brand)
    }

    @Test
    fun `the prompt offers exactly the vocabulary the answer is held to`() {
        for (name in GARMENT_TYPE_PROMPT_NAMES) assertTrue(name in GARMENT_READING_PROMPT, "$name is not offered")
        for (material in MATERIALS) assertTrue(material in GARMENT_READING_PROMPT, "$material is not offered")
        for (fit in Fit.entries) assertTrue(fit.tag in GARMENT_READING_PROMPT)
    }

    @Test
    fun `a page's title names its type, the longest name winning`() {
        assertEquals(ReadType("bottoms", "Chinos"), readPageText("Slim Fit Chinos", null).type)
        assertEquals(ReadType("tops", "Shirt"), readPageText("Linen Shirt", null).type)
        assertEquals(ReadType("tops", "Polo"), readPageText("Classic Polo Shirt", null).type)
        assertEquals(ReadType("tops", "T-Shirt"), readPageText("Organic tshirts, 3-pack", null).type)
        assertEquals(ReadType("bottoms", "Pants"), readPageText("Pleated wool trousers", null).type)
        assertEquals(ReadType("shoes", "Boots"), readPageText("Chelsea Boot", null).type)
        assertEquals(ReadType("dresses", "Midi"), readPageText("Satin Midi Dress", null).type)
        assertEquals(ReadType("dresses", null), readPageText("The Weekend Dress", null).type)
    }

    @Test
    fun `words that are not types are not read as them`() {
        assertEquals(ReadType("bottoms", "Chinos"), readPageText("Athletic Fit Chinos", null).type)
        assertEquals(ReadType("bottoms", "Skirt"), readPageText("Maxi skirt", null).type)
        assertEquals(ReadType("tops", "Shirt"), readPageText("Short-sleeved shirt", null).type)
        assertEquals(ReadType("midlayer", "Overshirt"), readPageText("Corduroy overshirt", null).type)
        assertNull(readPageText("Gift card", null).type)
    }

    @Test
    fun `the title is read first, then the description, for the type and the material`() {
        val reading = readPageText("The Everyday", "A relaxed hoodie in brushed cotton fleece.")
        assertEquals(ReadType("tops", "Hoodie"), reading.type)
        assertEquals("cotton", reading.material, "the first material named")
        assertEquals("linen", readPageText("Linen Shirt", "Made with cotton thread").material)
        assertTrue(readPageText(null, null).isEmpty)
    }

    @Test
    fun `the page prompt carries the page and asks for the same lines, bar the brand`() {
        val prompt = garmentPagePrompt("Linen Shirt", "Relaxed fit.")
        assertTrue("Title: Linen Shirt" in prompt)
        assertTrue("Description: Relaxed fit." in prompt)
        assertTrue("type: one of" in prompt && "material: one of" in prompt)
        assertTrue("brand:" !in prompt)
    }
}
