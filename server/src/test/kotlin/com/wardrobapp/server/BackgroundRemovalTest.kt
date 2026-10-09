package com.wardrobapp.server

import com.wardrobapp.api.NotFoundException
import com.wardrobapp.api.Routes
import com.wardrobapp.api.ServerException
import com.wardrobapp.api.ServerFeatures
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cutting a garment out of its background on the server: the work around the
 * model with a stand-in for it, the routes the browser asks, and -- once --
 * the real model on a photo whose answer is not in doubt.
 */
class BackgroundRemovalTest {

    /** A photo: [garment] filling the middle half of a [background] frame. */
    private fun photo(width: Int = 120, height: Int = 80, garment: Color = Color(150, 20, 30), background: Color = Color(235, 230, 220)): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = background
            fillRect(0, 0, width, height)
            color = garment
            fillRect(width / 4, height / 4, width / 2, height / 2)
            dispose()
        }
        return ByteArrayOutputStream().use { ImageIO.write(image, "jpg", it); it.toByteArray() }
    }

    /** A model that says the subject is the left half of whatever it is shown. */
    private class LeftHalf(override val size: Int = 16) : Segmenter {
        var closed = false
        override fun segment(input: FloatArray) = FloatArray(size * size) { if (it % size < size / 2) 3f else -2f }
        override fun close() { closed = true }
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    private fun alpha(image: BufferedImage, x: Int, y: Int) = image.getRGB(x, y) ushr 24

    @Test
    fun `the cut-out is what the model kept, framed, with the rest made transparent`() {
        val cutout = decode(BackgroundRemover.cutOut(photo(), LeftHalf()))

        // The left half of a 120 by 80 photo, 60 by 80, with a margin of 4%
        // of its longer side, on a 3:4 canvas: 66 wide, 88 tall, give or take
        // where the smoothed mask's edge falls. See CutoutFraming in :data.
        assertTrue(cutout.width in 62..72, "the cut-out is ${cutout.width} wide")
        assertEquals(Math.round(cutout.width / 0.75f), cutout.height, "the canvas is not 3:4")
        assertTrue(cutout.colorModel.hasAlpha())
        // Garment in the middle, margin at the edge.
        assertEquals(255, alpha(cutout, cutout.width / 2, cutout.height / 2))
        assertEquals(0, alpha(cutout, cutout.width - 1, cutout.height / 2))
        assertEquals(0, alpha(cutout, cutout.width / 2, 0))
        // The colour kept where the garment is: the photo's, give or take the JPEG.
        val kept = Color(cutout.getRGB(cutout.width / 2, cutout.height / 2))
        assertTrue(abs(kept.red - 150) < 12 && kept.green < 40, "the garment's colour changed: $kept")
    }

    @Test
    fun `a cut-out is framed around its garment, and a framed one is left as it is`() {
        // A 120 by 80 transparent canvas with a 40 by 60 garment at the far
        // left: the case of a cape photographed off to one side.
        val loose = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
        loose.createGraphics().apply {
            color = Color(150, 20, 30)
            fillRect(0, 10, 40, 60)
            dispose()
        }

        val framed = framedAroundGarment(loose)!!

        // Margin 2 round a 40 by 60 garment is 44 by 64; tall, so 48 by 64.
        assertEquals(48 to 64, framed.width to framed.height)
        assertEquals(255, alpha(framed, 24, 32))
        assertEquals(0, alpha(framed, 0, 32))
        assertEquals(0, alpha(framed, 47, 32))
        assertEquals(0, alpha(framed, 24, 0))
        // The garment's left edge is at 4: the width's spare 4 shared, plus
        // the margin of 2.
        assertEquals(0, alpha(framed, 3, 32))
        assertEquals(255, alpha(framed, 4, 32))

        assertEquals(null, framedAroundGarment(framed), "a framed cut-out was framed again")
    }

    @Test
    fun `the stored cut-outs are framed on a start, once each`() {
        val directory = Files.createTempDirectory("cutouts").toFile()
        try {
            val loose = BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB)
            loose.createGraphics().apply {
                color = Color(150, 20, 30)
                fillRect(0, 10, 40, 60)
                dispose()
            }
            ImageIO.write(loose, "png", File(directory, "a_nobg.png"))
            // A photo that is not a cut-out, whatever is in it, is not looked at.
            File(directory, "b.jpg").writeBytes(photo())
            // And a cut-out that will not decode is left exactly as it was.
            File(directory, "c_nobg.png").writeBytes(byteArrayOf(1, 2, 3))

            assertEquals(1, frameLooseCutouts(directory))
            val framed = ImageIO.read(File(directory, "a_nobg.png"))
            assertEquals(48 to 64, framed.width to framed.height)
            assertEquals(3, File(directory, "c_nobg.png").length().toInt())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".part") }, "a staged copy was left behind")

            assertEquals(0, frameLooseCutouts(directory), "the second pass found something to do")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the model is shown the photo the way it was trained on`() {
        // A white photo, at its brightest everywhere: each plane is that
        // channel's 1, less ImageNet's mean, over its deviation.
        val white = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 40, 30); dispose() }
        }
        val input = BackgroundRemover.modelInput(white, 8)

        assertEquals(3 * 64, input.size)
        val expected = listOf((1 - 0.485f) / 0.229f, (1 - 0.456f) / 0.224f, (1 - 0.406f) / 0.225f)
        for (channel in 0..2) {
            for (i in 0 until 64) assertEquals(expected[channel], input[channel * 64 + i], 1e-4f)
        }
    }

    @Test
    fun `a mask is stretched and scaled smoothly, centre onto centre`() {
        assertEquals(listOf(0f, 0.5f, 1f), BackgroundRemover.stretched(floatArrayOf(-1f, 0f, 1f)).toList())
        assertEquals(listOf(0f, 0f), BackgroundRemover.stretched(floatArrayOf(4f, 4f)).toList())

        // Two pixels, nothing and everything, to four: the ends stay put and
        // the middle two are in between, symmetrically.
        val scaled = BackgroundRemover.scaledBilinear(floatArrayOf(0f, 1f), 2, 1, 4, 1)
        assertEquals(listOf(0f, 0.25f, 0.75f, 1f), scaled.toList())
    }

    @Test
    fun `something that is not a photo is refused`() {
        assertFailsWith<IllegalArgumentException> { BackgroundRemover.cutOut(byteArrayOf(1, 2, 3), LeftHalf()) }
    }

    @Test
    fun `a photo too large to decode safely is refused by its header, before it is decoded`() {
        // A flat PNG a side over the limit: a few kilobytes on disk, hundreds
        // of megabytes decoded. The refusal names the size, since the person
        // who sent it is the one who can scale it.
        val huge = BufferedImage(BackgroundRemover.MAX_SIDE + 1, 8, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { ImageIO.write(huge, "png", it); it.toByteArray() }
        val refusal = assertFailsWith<PhotoRejected.TooLarge> { BackgroundRemover.cutOut(bytes, LeftHalf()) }
        assertTrue("${BackgroundRemover.MAX_SIDE + 1} by 8" in refusal.message.orEmpty(), refusal.message)

        assertEquals(120 to 80, BackgroundRemover.dimensionsOf(photo()))
        assertEquals(null, BackgroundRemover.dimensionsOf(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `the model is loaded when asked for, and let go when idle`() {
        val opened = AtomicInteger()
        val models = mutableListOf<LeftHalf>()
        BackgroundRemover(idleSeconds = 0) { LeftHalf().also { models += it; opened.incrementAndGet() } }.use { remover ->
            assertFalse(remover.loaded, "loaded before anybody asked")
            remover.cutOut(photo())
            waitUntil { !remover.loaded }
            assertTrue(models.single().closed)

            remover.cutOut(photo())
            assertEquals(2, opened.get(), "not opened again after being let go")
        }
    }

    @Test
    fun `the browser can cut a stored photo out, and keeps the original`() = serverTest(backgrounds = BackgroundRemover { LeftHalf() }) {
        assertTrue(http.get(Routes.FEATURES).body<ServerFeatures>().removesBackgrounds)

        val original = photos.upload(photo(), io.ktor.http.ContentType.Image.JPEG)
        val cutout = garmentDetail.cutOut(original)

        assertTrue(cutout.startsWith("p/main/photos/") && cutout.endsWith("_nobg.png"), cutout)
        val served = decode(fromPage(cutout).readRawBytes())
        assertEquals(255, alpha(served, served.width / 2, served.height / 2))
        assertEquals(0, alpha(served, served.width - 1, served.height / 2))
        assertEquals(200, fromPage(original).status.value, "the original went")
        // The browser's own photo work asks the same route.
        assertTrue(photos.cutOut(original).endsWith("_nobg.png"))
    }

    @Test
    fun `a photo there is none of is not found`() = serverTest(backgrounds = BackgroundRemover { LeftHalf() }) {
        assertFailsWith<NotFoundException> { photos.cutOut("p/main/photos/nothing.jpg") }
    }

    @Test
    fun `a server without the model says so, and refuses`() = serverTest {
        assertFalse(http.get(Routes.FEATURES).body<ServerFeatures>().removesBackgrounds)
        val original = uploadPhoto()
        assertEquals(501, assertFailsWith<ServerException> { photos.cutOut(original) }.status)
    }

    @Test
    fun `no model configured, or none where it was said to be, is no remover`() {
        assertEquals(null, BackgroundRemover.at(null))
        assertEquals(null, BackgroundRemover.at(File("/nowhere/silueta.onnx")))
    }

    /**
     * The real model, as the build downloaded it, on a photo with one answer:
     * a dark red cloth in the middle of a pale floor. Not a test of how good
     * the model is -- that was judged on real photos when it was chosen, and
     * is in BackgroundRemover's comment -- but that the model is the shape
     * this code thinks it is, that ONNX Runtime loads here, and that what
     * comes out is the right way round.
     */
    @Test
    fun `the real model cuts a garment out`() {
        val model = File(System.getProperty("backgroundModel"))
        assertTrue(model.isFile, "the build did not download the model to $model")
        val remover = BackgroundRemover.at(model)!!
        remover.use {
            // Framed, so the garment is at the middle of whatever came back
            // and the corner is its margin.
            val cutout = decode(it.cutOut(photo(width = 400, height = 300)))
            val middle = alpha(cutout, cutout.width / 2, cutout.height / 2)
            assertTrue(middle > 200, "the garment was cut away: $middle")
            assertTrue(alpha(cutout, 2, 2) < 50, "the floor was kept: ${alpha(cutout, 2, 2)}")
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
