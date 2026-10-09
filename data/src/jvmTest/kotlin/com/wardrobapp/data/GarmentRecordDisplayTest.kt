package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which picture a garment shows, and whether it is a cut-out.
 *
 * `displayImage` has preferred the cut-out for as long as there have been
 * cut-outs; what is new is that a screen needs to know when it got one, because
 * a cut-out is drawn whole on a plain tile where a photo is cropped to its frame
 * (see GarmentPhoto in :ui). The two answers have to agree with each other, so
 * they are tested against each other rather than against literals alone.
 */
class GarmentRecordDisplayTest {

    private val directory = "file:///data/user/0/com.anonymous.wardrobapp/files/garment-images/"

    private fun read(vararg columns: Pair<String, Any?>) =
        normalizeGarmentRow(mapOf("id" to "g1", "category" to "tops", *columns), directory)

    @Test
    fun `a garment with no cut-out shows its photo, and says so`() {
        val record = read("image_uri" to "front.jpg", "image_uris" to """["front.jpg","back.jpg"]""")

        assertNull(record.displayCutout)
        assertEquals("${directory}front.jpg", record.displayImage)
    }

    @Test
    fun `the first slot with a cut-out is the one shown`() {
        // An empty string is a slot without a cut-out, and the position is what
        // says which photo it belongs to -- so the back's cut-out shows even
        // though the front has none.
        val record = read(
            "image_uri" to "front.jpg",
            "image_uris" to """["front.jpg","back.jpg"]""",
            "image_uris_nobg" to """["","back-nobg.png"]""",
        )

        assertEquals("${directory}back-nobg.png", record.displayCutout)
        assertEquals(record.displayCutout, record.displayImage)
    }

    @Test
    fun `slots that are all empty are no cut-out at all`() {
        val record = read(
            "image_uri" to "front.jpg",
            "image_uris_nobg" to """["",""]""",
        )

        assertNull(record.displayCutout)
        assertEquals("${directory}front.jpg", record.displayImage)
    }
}
