package com.wardrobapp.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How big a stored photo is, and what it is called.
 *
 * Worth testing precisely because getting it wrong fails silently: a photo
 * stored at four times the intended size still displays perfectly, and the cost
 * turns up later as a backup nobody can move off the phone.
 */
class PhotoStorageTest {

    private fun size(width: Int, height: Int) = storedPhotoSize(width, height)

    @Test
    fun `caps a landscape photo on its width`() {
        assertEquals(StoredPhotoSize(800, 600), size(1600, 1200))
    }

    @Test
    fun `caps a portrait photo on its height`() {
        // The React Native app resizes by width alone, so this import was stored
        // at 800x3200 -- four times the pixels the cap implies. The cap is on the
        // longest side, which is what "max dimension" has to mean.
        assertEquals(StoredPhotoSize(200, 800), size(1000, 4000))
    }

    @Test
    fun `leaves a photo already inside the cap alone`() {
        // Scaling *to* a width, as the TypeScript does, enlarges a small photo --
        // a bigger file with no more detail in it.
        assertEquals(StoredPhotoSize(320, 240), size(320, 240))
        assertEquals(StoredPhotoSize(240, 320), size(240, 320))
    }

    @Test
    fun `leaves a photo exactly at the cap alone`() {
        assertEquals(StoredPhotoSize(800, 450), size(800, 450))
        assertEquals(StoredPhotoSize(450, 800), size(450, 800))
        assertEquals(StoredPhotoSize(800, 800), size(800, 800))
    }

    @Test
    fun `keeps the aspect ratio`() {
        for ((width, height) in listOf(
            4000 to 3000, 3000 to 4000, 5000 to 5000, 2400 to 1080, 1080 to 2400, 6000 to 1000,
        )) {
            val stored = size(width, height)
            val before = width.toDouble() / height.toDouble()
            val after = stored.width.toDouble() / stored.height.toDouble()

            // Within a pixel's worth of rounding at this scale.
            assertTrue(
                abs(before - after) < 0.01 * before,
                "${width}x$height became ${stored.width}x${stored.height}, " +
                    "ratio $before -> $after",
            )
        }
    }

    @Test
    fun `never stores a photo larger than the cap`() {
        for ((width, height) in listOf(
            801 to 100, 100 to 801, 12000 to 9000, 9000 to 12000, 20000 to 3,
        )) {
            val stored = size(width, height)

            assertTrue(
                stored.width <= MAX_PHOTO_DIMENSION && stored.height <= MAX_PHOTO_DIMENSION,
                "${width}x$height became ${stored.width}x${stored.height}",
            )
        }
    }

    @Test
    fun `never rounds a side away entirely`() {
        // A panorama scaled by its long side rounds its short side to zero, and
        // a zero-width bitmap cannot be created at all -- the save would throw
        // rather than store a thin photo.
        val stored = size(40000, 3)

        assertEquals(800, stored.width)
        assertTrue(stored.height >= 1, "the short side rounded to ${stored.height}")
    }

    @Test
    fun `hands back a photo it cannot measure`() {
        // A decoder reporting nothing is a photo that will fail to decode. That
        // is worth reporting as itself rather than turning into a size.
        assertEquals(StoredPhotoSize(0, 0), size(0, 0))
        assertEquals(StoredPhotoSize(-1, 100), size(-1, 100))
    }

    @Test
    fun `names a photo and a cut-out apart`() {
        assertEquals("abc.jpg", photoFilename("abc"))
        assertEquals("abc_nobg.png", cutoutFilename("abc"))
    }

    @Test
    fun `a cut-out is recognisable by its name`() {
        // A cross-app contract: the React Native app finds cut-outs by this
        // suffix to recompress the ones saved before they were downscaled, so one
        // written under another name would be missed.
        assertTrue(isCutoutFilename(cutoutFilename("abc")))
        assertTrue(!isCutoutFilename(photoFilename("abc")))
        assertTrue(!isCutoutFilename("holiday_nobg.jpg"))
        assertTrue(!isCutoutFilename("nobg.png"))
    }

    @Test
    fun `stores cut-outs as PNG and photos as JPEG`() {
        // JPEG has no alpha channel, so a cut-out saved as one fills the removed
        // background with black -- the opposite of what removing it was for.
        assertTrue(cutoutFilename("x").endsWith(".png"))
        assertTrue(photoFilename("x").endsWith(".jpg"))
    }

    // ---- turning a photo the right way up -----------------------------------

    @Test
    fun `reads the eight EXIF orientations`() {
        // Half of them mirror as well as rotate, which is the part that is easy
        // to get wrong -- and getting it wrong flips a garment left for right,
        // which looks plausible until you compare it with the real thing.
        assertEquals(PhotoOrientation(0, false), photoOrientation(1))
        assertEquals(PhotoOrientation(0, true), photoOrientation(2))
        assertEquals(PhotoOrientation(180, false), photoOrientation(3))
        assertEquals(PhotoOrientation(180, true), photoOrientation(4))
        assertEquals(PhotoOrientation(90, true), photoOrientation(5))
        assertEquals(PhotoOrientation(90, false), photoOrientation(6))
        assertEquals(PhotoOrientation(270, true), photoOrientation(7))
        assertEquals(PhotoOrientation(270, false), photoOrientation(8))
    }

    @Test
    fun `treats an absent or nonsense orientation as upright`() {
        // A photo with no EXIF at all is the common case, not an error.
        for (tag in listOf(0, -1, 9, 42)) {
            assertTrue(photoOrientation(tag).isUpright, "tag $tag was not treated as upright")
        }
    }

    @Test
    fun `knows which turns swap the sides`() {
        assertTrue(photoOrientation(6).swapsSides)
        assertTrue(photoOrientation(8).swapsSides)
        assertTrue(!photoOrientation(1).swapsSides)
        assertTrue(!photoOrientation(3).swapsSides)
    }

    @Test
    fun `caps a photo by the side it will have once turned`() {
        // A 4000x1000 photo tagged "rotate 90" is really 1000x4000. Capping it as
        // stored gives 800x200: the cap applied to the wrong side, and the photo
        // still on its side. Turning comes first.
        val oriented = orientedSize(4000, 1000, photoOrientation(6))

        assertEquals(StoredPhotoSize(1000, 4000), oriented)
        assertEquals(StoredPhotoSize(200, 800), storedPhotoSize(oriented.width, oriented.height))
    }

    @Test
    fun `leaves the sides alone for a turn that does not swap them`() {
        assertEquals(StoredPhotoSize(4000, 1000), orientedSize(4000, 1000, photoOrientation(3)))
        assertEquals(StoredPhotoSize(4000, 1000), orientedSize(4000, 1000, photoOrientation(1)))
    }

    // ---- decoding without running out of memory -----------------------------

    @Test
    fun `sub-samples a large photo down towards the target`() {
        // 4000x3000 to 800x600: halving twice gives 1000x750, still above the
        // target; a third halving would drop below it.
        assertEquals(4, decodeSampleSize(4000, 3000, StoredPhotoSize(800, 600)))
    }

    @Test
    fun `never sub-samples below the target`() {
        // Sampling past the target throws away detail the stored photo is meant
        // to keep, and cannot be got back by scaling up afterwards.
        for ((width, height) in listOf(1600 to 1200, 2000 to 1500, 4000 to 3000, 9000 to 6000)) {
            val target = storedPhotoSize(width, height)
            val sample = decodeSampleSize(width, height, target)

            assertTrue(
                width / sample >= target.width && height / sample >= target.height,
                "${width}x$height sampled by $sample falls below ${target.width}x${target.height}",
            )
        }
    }

    @Test
    fun `does not sub-sample a photo that is already small`() {
        assertEquals(1, decodeSampleSize(400, 300, StoredPhotoSize(400, 300)))
        assertEquals(1, decodeSampleSize(800, 600, StoredPhotoSize(800, 600)))
    }

    @Test
    fun `only ever asks for a power of two`() {
        // Android's decoder rounds anything else down to one, so a value like 3
        // would quietly mean "no sub-sampling at all".
        for (width in listOf(801, 1000, 1600, 2500, 4000, 12000)) {
            val sample = decodeSampleSize(width, width, storedPhotoSize(width, width))

            assertTrue(
                sample > 0 && (sample and (sample - 1)) == 0,
                "${width}px asked for a sample size of $sample",
            )
        }
    }

    @Test
    fun `asks for no sub-sampling when it cannot tell the size`() {
        assertEquals(1, decodeSampleSize(0, 0, StoredPhotoSize(800, 800)))
        assertEquals(1, decodeSampleSize(1000, 1000, StoredPhotoSize(0, 0)))
    }

}
