package com.wardrobapp.app

import com.wardrobapp.data.wardrobeFilesIn
import com.wardrobapp.presentation.GarmentFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * What a save leaves on the disk.
 *
 * `GarmentFormTest` in :presentation covers the rule -- a slot whose background was
 * removed stores the cut-out in both columns and reports the original as
 * discardable -- and `OrphanedImageRefsTest` in :data covers which references a
 * save leaves behind. Nothing covered the part where a file is actually deleted,
 * which is the part a person notices: a wardrobe that keeps both copies of every
 * photo it cut out is twice the size it should be, in the app and in every backup.
 *
 * So this runs the real save against a real database and real files, and looks at
 * the directory afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class GarmentSavePhotosTest {

    private val context = RuntimeEnvironment.getApplication()

    private val photos: File get() = wardrobeFilesIn(context.filesDir).imagesDir

    /** A fresh install: an empty photo directory and no database. */
    @Before
    fun emptyTheDataDirectory() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Bytes, not an image: nothing on this path decodes a photo. */
    private fun photo(name: String): File =
        File(photos.also { it.mkdirs() }, name).apply { writeBytes(ByteArray(64) { 7 }) }

    private fun form(
        images: List<String>,
        cutouts: List<String>,
    ) = GarmentFormState(imageUris = images, bgRemovedUris = cutouts).normalized()

    @Test
    fun `a photo whose background was removed leaves only the cut-out`() {
        val original = photo("g1.jpg")
        val cutout = photo("g1_nobg.png")
        val container = AppContainer(context)

        GarmentFormViewModel(container, garmentId = null)
            .write(form(listOf("g1.jpg"), listOf("g1_nobg.png")))

        assertFalse("the original was kept, and is dead weight", original.exists())
        assertTrue("the cut-out is the photo now, and must survive", cutout.exists())

        // And the row points at the cut-out from both columns, which is what makes
        // the original safe to delete rather than a gap in the garment.
        val garment = container.garments.allGarments().single()
        assertEquals(listOf("g1_nobg.png"), garment.imageUris.map { it.substringAfterLast('/') })
        assertEquals(listOf("g1_nobg.png"), garment.imageUrisNoBg.map { it.substringAfterLast('/') })
    }

    @Test
    fun `a photo with no cut-out is left exactly as it is`() {
        val plain = photo("g2.jpg")

        GarmentFormViewModel(AppContainer(context), garmentId = null)
            .write(form(listOf("g2.jpg"), listOf("")))

        assertTrue("a photo with no cut-out is the garment's only copy", plain.exists())
    }

    @Test
    fun `saving twice does not delete the cut-out the first save kept`() {
        // The second save sees a slot whose two columns hold the same file. Treating
        // that as "an original to discard" would delete the photo it just stored --
        // which is why the rule checks that the two are genuinely different files.
        val cutout = photo("g3_nobg.png")
        val container = AppContainer(context)

        GarmentFormViewModel(container, garmentId = null)
            .write(form(listOf("g3_nobg.png"), listOf("g3_nobg.png")))

        assertTrue(cutout.exists())
        assertEquals(1, container.garments.allGarments().size)
    }
}
