package com.wardrobapp.presentation

import com.wardrobapp.data.normalizeGarmentRow
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a garment's detail screen shows.
 *
 * A view function over a database row, and most of its decisions are about
 * disagreeing with itself: a photo strip whose selection is not the photo on
 * screen, a colour swatch with no name under it, a cut-out shown where the
 * original was expected.
 */
class GarmentDetailTest {

    private fun record(
        images: String = """["front.jpg"]""",
        cutouts: String = "[]",
        category: String = "tops",
        subcategories: String = """["T-Shirt"]""",
        tags: String = "[]",
        brand: String? = null,
        size: String? = null,
        palette: String = """["#1F3A93"]""",
        isAvailable: Int = 1,
        unavailableDate: String? = null,
        purchaseDate: String? = null,
    ) = normalizeGarmentRow(
        mapOf(
            "id" to "g1",
            // normalizeGarmentRow folds the single-value columns into the list
            // ones, so these are the first entry rather than another photo.
            "image_uri" to (Regex("\"([^\"]+)\"").find(images)?.groupValues?.get(1) ?: ""),
            "image_uris" to images,
            "image_uris_nobg" to cutouts,
            "category" to category,
            "subcategories" to subcategories,
            "tags" to tags,
            "brand" to brand,
            "size" to size,
            "color_primary" to (Regex("\"([^\"]+)\"").find(palette)?.groupValues?.get(1) ?: ""),
            "color_palette" to palette,
            "is_available" to isAvailable,
            "unavailable_date" to unavailableDate,
            "purchase_date" to purchaseDate,
        ),
        "",
    )

    @Test
    fun `one photo is shown without a strip to choose from`() {
        val view = garmentDetail(record())

        assertEquals("front.jpg", view.displayedImage)
        assertEquals(1, view.gallery.size)
        assertTrue(!view.showsGallery, "a strip was offered for a single photo")
        assertEquals(0, view.selectedIndex)
    }

    @Test
    fun `the photo on screen is the one the strip says is selected`() {
        val view = garmentDetail(
            record(images = """["a.jpg","b.jpg","c.jpg"]"""),
            selectedIndex = 1,
        )

        assertEquals("b.jpg", view.displayedImage)
        assertEquals(1, view.selectedIndex)
        assertEquals(listOf(false, true, false), view.gallery.map { it.selected })
        assertTrue(view.showsGallery)
    }

    @Test
    fun `a selection from outside the strip falls back to the first photo, visibly`() {
        // A remembered index, or a garment whose photos were edited since. Reading
        // past the end used to show the first photo while the strip showed nothing
        // selected, so the screen disagreed with itself.
        for (index in listOf(-1, 3, 99)) {
            val view = garmentDetail(record(images = """["a.jpg","b.jpg"]"""), selectedIndex = index)

            assertEquals("a.jpg", view.displayedImage, "index $index")
            assertEquals(0, view.selectedIndex, "index $index")
            assertEquals(listOf(true, false), view.gallery.map { it.selected }, "index $index")
        }
    }

    @Test
    fun `a cut-out is shown in place of the photo it was cut from`() {
        val view = garmentDetail(
            record(images = """["a.jpg","b.jpg"]""", cutouts = """["a-cut.png",""]"""),
        )

        assertEquals("a-cut.png", view.displayedImage)
        assertEquals(listOf("a-cut.png", "b.jpg"), view.gallery.map { it.uri })
        assertEquals(listOf(true, false), view.gallery.map { it.hasCutout })
    }

    @Test
    fun `a garment with no photos at all does not crash`() {
        val view = garmentDetail(record(images = "[]"))

        assertEquals(null, view.displayedImage)
        assertEquals(emptyList(), view.gallery)
        assertTrue(!view.showsGallery)
    }

    @Test
    fun `seasons read in the app's own order, and the rest stay as tags`() {
        // Season tags and free tags share one column, so splitting them is what
        // stops "winter" showing up as something the user typed.
        val view = garmentDetail(record(tags = """["winter","favourite","spring"]"""))

        assertEquals(listOf(Season.SPRING, Season.WINTER), view.seasons)
        assertEquals(listOf("favourite"), view.tags)
    }

    @Test
    fun `occasions come from what the garment is, not from what it was tagged`() {
        val view = garmentDetail(record(category = "tops", subcategories = """["Blouse"]"""))

        assertEquals(listOf(Occasion.WORK, Occasion.FORMAL), view.occasions)
    }

    @Test
    fun `a blank field is absent rather than an empty line`() {
        val view = garmentDetail(record(brand = "   ", size = "", purchaseDate = null))

        assertEquals(null, view.brand)
        assertEquals(null, view.size)
        assertEquals(null, view.purchaseDate)
    }

    @Test
    fun `a retired garment says when, and an available one says nothing`() {
        val retired = garmentDetail(
            record(isAvailable = 0, unavailableDate = "2026-02-01T00:00:00.000Z")
        )
        assertTrue(!retired.isAvailable)
        assertEquals("2026-02-01T00:00:00.000Z", retired.unavailableDate)

        // A date left behind by an earlier retirement must not show on a garment
        // that is back in use.
        val restored = garmentDetail(
            record(isAvailable = 1, unavailableDate = "2026-02-01T00:00:00.000Z")
        )
        assertTrue(restored.isAvailable)
        assertEquals(null, restored.unavailableDate)
    }

    @Test
    fun `the background button offers the action that is available`() {
        val original = garmentDetail(record(images = """["a.jpg"]""", cutouts = "[]"))
        val cutout = garmentDetail(record(images = """["a.jpg"]""", cutouts = """["a-cut.png"]"""))

        assertEquals(BackgroundAction.REMOVE, original.backgroundAction)
        assertEquals(BackgroundAction.UNDO, cutout.backgroundAction)

        // A garment imported as a cut-out has the same path in both slots, so
        // there is nothing to undo to and no original to run removal against --
        // which is the case the form used to get wrong, offering undo where
        // there was nothing to undo.
        val importedCutout = garmentDetail(
            record(images = """["cut.png"]""", cutouts = """["cut.png"]""")
        )
        assertEquals(null, importedCutout.backgroundAction)
    }

    @Test
    fun `every colour the picker offers has a name`() {
        // A hex in the picker that this cannot name would show as a raw code on
        // the detail screen -- the failure mode is silent, so it is stated here.
        val duplicates = GARMENT_COLORS.groupBy { it.second.uppercase() }.filterValues { it.size > 1 }
        assertEquals(emptyMap(), duplicates, "two colour keys share a hex")

        for ((key, hex) in GARMENT_COLORS) {
            val named = garmentDetail(record(palette = """["$hex"]""")).palette.single().colorKey
            assertEquals(key, named, "$hex is not named")
        }
    }

    @Test
    fun `a colour the picker does not have is shown without a name rather than dropped`() {
        val view = garmentDetail(record(palette = """["#123456"]"""))

        assertEquals("#123456", view.palette.single().hex)
        assertEquals(null, view.palette.single().colorKey)
    }
}
