package com.wardrobapp.app

import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.wardrobeFilesIn
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
 * The pass that deletes photos, which is the one worth being sure about.
 *
 * `unreferencedPhotosTest` in :data covers which names are condemned; these cover
 * what the filesystem and the wardrobe add to that question -- how old a file has
 * to be before it is fair game, and that "every garment" really means every one.
 * Both mistakes here take a photo off somebody's phone.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoSweepTest {

    private val context = RuntimeEnvironment.getApplication()

    private val photos: File get() = wardrobeFilesIn(context.filesDir).imagesDir

    @Before
    fun emptyTheDataDirectory() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** A file, aged so the sweep is allowed to consider it. */
    private fun settled(name: String): File =
        File(photos.also { it.mkdirs() }, name).apply {
            writeBytes(ByteArray(128) { 9 })
            setLastModified(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
        }

    @Test
    fun `a file nothing points at goes, and a referenced one stays`() {
        val orphan = settled("orphan.jpg")
        val kept = settled("kept.jpg")

        val summary = AndroidPhotoStore(context).deleteUnreferenced(listOf("kept.jpg"))

        assertFalse(orphan.exists())
        assertTrue(kept.exists())
        assertEquals(1, summary.deleted)
        assertEquals(128L, summary.bytesSaved)
        // Both were looked at, which is what the "nothing to do" message counts.
        assertEquals(2, summary.examined)
    }

    @Test
    fun `a photo written moments ago is left alone`() {
        // The case that would hurt: a cut-out is written the instant the background
        // comes off, and nothing references it until the form is saved. A sweep
        // running in between must not take it.
        val justWritten = File(photos.also { it.mkdirs() }, "fresh_nobg.png")
            .apply { writeBytes(ByteArray(16)) }

        val summary = AndroidPhotoStore(context).deleteUnreferenced(emptyList())

        assertTrue("a photo from a form in progress was deleted", justWritten.exists())
        assertEquals(0, summary.deleted)
    }

    @Test
    fun `a retired garment keeps its photos`() {
        // Retiring is meant to be reversible, and a garment with no photo left is
        // not something you can bring back. This goes through the container, since
        // asking the wardrobe for "every garment" is where it would go wrong: the
        // default filter is available-only.
        val container = AppContainer(context)
        val retired = settled("retired.jpg")

        container.garmentWrites.insert(
            GarmentWrites.NewGarment(
                id = "g-retired",
                imageUri = "retired.jpg",
                imageUris = listOf("retired.jpg"),
                category = "tops",
                colorPrimary = "#000000",
                now = "2026-01-01T00:00:00.000Z",
            )
        )
        container.garmentWrites.markUnavailable("g-retired", "2026-02-01T00:00:00.000Z")

        val summary = container.tidyPhotos { _, _ -> }

        assertTrue("a retired garment's photo was swept", retired.exists())
        assertEquals(0, summary.deleted)
    }

    @Test
    fun `the photos of a garment that was deleted are reclaimed`() {
        // The other side of the same question, and the reason this pass exists: a
        // wardrobe that has had garments come and go leaves files behind.
        val container = AppContainer(context)
        val leftBehind = settled("gone.jpg")

        val summary = container.tidyPhotos { _, _ -> }

        assertFalse(leftBehind.exists())
        assertEquals(1, summary.deleted)
    }
}
