package com.wardrobapp.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.wardrobapp.data.wardrobeFilesIn
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Framing a cut-out around its garment on the phone: the pixels read off a
 * real bitmap and drawn back onto a new one, and the pass over the stored
 * files. The arithmetic in between is :data's and tested there.
 *
 * Native graphics, because this draws: under Robolectric's legacy mode a
 * Canvas records what it was asked and no pixel changes, which would pass
 * the framing and fail everything it draws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CutoutFramesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun emptyTheDataDirectory() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** A 120 by 80 transparent canvas with a 40 by 60 garment at the far left. */
    private fun loose(): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 10f, 40f, 70f, Paint().apply { color = Color.rgb(150, 20, 30) })
        return bitmap
    }

    @Test
    fun `a garment off to one side is framed in the middle of a three-to-four canvas`() {
        val framed = loose().framedAroundGarment()

        assertNotNull(framed)
        // Margin 2 round 40 by 60 is 44 by 64; tall, so 48 by 64.
        assertEquals(48 to 64, framed!!.width to framed.height)
        assertEquals(255, Color.alpha(framed.getPixel(24, 32)))
        assertEquals(0, Color.alpha(framed.getPixel(0, 32)))
        assertEquals(0, Color.alpha(framed.getPixel(47, 32)))
        assertEquals(0, Color.alpha(framed.getPixel(24, 0)))
        assertEquals(0, Color.alpha(framed.getPixel(3, 32)))
        assertEquals(255, Color.alpha(framed.getPixel(4, 32)))

        assertNull("a framed cut-out was framed again", framed.framedAroundGarment())
    }

    @Test
    fun `the stored cut-outs are framed in place, and a framed one costs nothing`() {
        val directory = wardrobeFilesIn(context.filesDir).imagesDir.also { it.mkdirs() }
        File(directory, "a_nobg.png").outputStream().use { loose().compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Not a cut-out: not looked at, whatever is in it.
        File(directory, "b.jpg").outputStream().use { loose().compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val first = AndroidPhotoStore(context).frameLooseCutouts()

        assertEquals(1, first.framed)
        assertEquals(1, first.examined)
        val framed = BitmapFactory.decodeFile(File(directory, "a_nobg.png").path)
        assertEquals(48 to 64, framed.width to framed.height)
        assertEquals(0, directory.listFiles()!!.count { it.name.endsWith(".incoming") })

        assertEquals(0, AndroidPhotoStore(context).frameLooseCutouts().framed)
    }
}
