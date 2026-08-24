package com.wardrobapp.app

import android.graphics.Bitmap
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Reading a stored photo back off the disk.
 *
 * This is the test that was missing, and the bug it would have caught shipped:
 * background removal decoded the photo itself, and wrote
 *
 *     contentResolver.openInputStream(photo)?.use { decodeStream(it, null, bounds) }
 *         ?: throw IOException("That photo could not be opened.")
 *
 * where `bounds` had `inJustDecodeBounds = true`. A bounds-only decode returns
 * null by design -- filling in the options *is* the result -- so the elvis fired
 * on every photo that opened perfectly. Every attempt to remove a background, on
 * every garment, failed with a message saying the file could not be opened.
 *
 * Nothing caught it because nothing tested it: the decoding lives in `:app`, so
 * the 478 pure tests cannot see it, and no Robolectric test read a photo. Hence
 * this one. It asserts the two outcomes that have to stay distinguishable --
 * a photo that decodes, and a URI that cannot be opened at all.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoDecodingTest {

    private val context = RuntimeEnvironment.getApplication()
    private val photos = AndroidPhotoStore(context)

    @Before
    fun emptyTheDataDirectory() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** A real file with real PNG bytes in it, in the app's own photo directory. */
    private fun storedPhoto(name: String = "photo.png"): File {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val file = File(File(context.filesDir, "garment-images").also { it.mkdirs() }, name)

        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        assertTrue("the test's own photo was not written", file.length() > 0)
        return file
    }

    @Test
    fun `a stored photo decodes to a bitmap`() {
        // The assertion the shipped bug failed: a photo that opens must come back
        // as pixels, not as an exception about being unopenable.
        val bitmap = photos.bitmapFor(storedPhoto().toUri())

        assertNotNull("a readable photo decoded to nothing", bitmap)
        assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
    }

    @Test
    fun `a photo already within the stored size is not shrunk`() {
        // 120x80 is well under the 800px cap, so the decoder should hand back the
        // whole thing rather than sub-sampling it -- a cut-out is written at
        // whatever size this returns.
        val bitmap = photos.bitmapFor(storedPhoto().toUri())

        assertEquals(120, bitmap!!.width)
        assertEquals(80, bitmap.height)
    }

    @Test
    fun `a photo that cannot be opened is a failure rather than an empty bitmap`() {
        // The other half of the distinction: this is the case whose message the
        // shipped bug borrowed for everything.
        val missing = File(context.filesDir, "garment-images/not-here.png").toUri()

        assertThrows(IOException::class.java) { photos.bitmapFor(missing) }
    }

    // There is no test here for "a file that is not an image decodes to nothing",
    // which is the other branch `bitmapFor` has. Robolectric's BitmapFactory does
    // not parse pixels -- it hands back a stand-in bitmap whatever the bytes are --
    // so such a test would assert what the shadow does rather than what the app
    // does, and would pass while claiming something untrue about a device. The
    // branch stays because it is right on a phone; it is simply not checkable
    // here.
}
