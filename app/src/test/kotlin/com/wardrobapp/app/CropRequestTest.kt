package com.wardrobapp.app

import android.graphics.Bitmap
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the crop screen is asked for.
 *
 * The requirement is one sentence -- a garment photo is cropped to 3:4 when it is
 * added -- and it lives in a handful of fields on somebody else's options object,
 * where nothing else would notice it changing. Every screen that shows a garment
 * photo assumes that shape, so a request that stopped fixing the ratio, or offered
 * a choice of ratios, would be a silently different app.
 */
@RunWith(RobolectricTestRunner::class)
class CropRequestTest {

    private val source = "content://media/external/images/1".toUri()
    private val output = "content://com.anonymous.wardrobapp.camera/crop/cropped.jpg".toUri()
    private val colors = lightColorScheme(surface = Color.Red, onSurface = Color.Blue)

    private val request = cropTo3by4(source, output, colors)

    @Test
    fun `the ratio is three by four, and fixed`() {
        val options = request.cropImageOptions

        assertEquals(3, options.aspectRatioX)
        assertEquals(4, options.aspectRatioY)
        assertTrue("the ratio can be reshaped", options.fixAspectRatio)
    }

    @Test
    fun `the photo goes in and the cropped copy comes out where we said`() {
        assertEquals(source, request.uri)
        assertEquals(output, request.cropImageOptions.customOutputUri)
        assertEquals(Bitmap.CompressFormat.JPEG, request.cropImageOptions.outputCompressFormat)
    }

    @Test
    fun `the screen is drawn in the app's own colours`() {
        // Not decoration: this is the only thing making the crop screen follow the
        // theme chosen in Settings, since its own DayNight theme follows the phone.
        val options = request.cropImageOptions

        assertEquals(Color.Red.toArgb(), options.toolbarColor as Int)
        assertEquals(Color.Blue.toArgb(), options.toolbarTintColor as Int)
        assertEquals(Color.Blue.toArgb(), options.activityMenuIconColor)
    }
}
