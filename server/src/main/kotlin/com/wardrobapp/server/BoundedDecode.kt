package com.wardrobapp.server

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * [photo] decoded at no more than [maxSide] pixels a side, skipping pixels to
 * get there; null when no reader knows the format.
 *
 * Why this exists rather than `ImageIO.read`: the 20 MB a stored photo may
 * weigh is a bound on the file, not on the picture. A flat-colour PNG that
 * claims twenty thousand pixels a side compresses to under a megabyte and
 * decodes to more than a gigabyte, and the server runs in a quarter of a
 * Raspberry Pi's memory -- so one such photo, from a paired phone, the
 * browser, or the page of a shop somebody imported from, put the box that
 * runs Home Assistant out of memory every time the style pass came round to
 * it. `OutOfMemoryError` is not an `Exception`, so nothing on the way caught
 * it either. BackgroundRemover measured its photos before decoding them; the
 * style encoder did not.
 *
 * Subsampled rather than refused, which is what BackgroundRemover does,
 * because the two want different things. A cut-out keeps the photo's own
 * size, so a photo too large to cut out has to be turned away. The style
 * model looks at 224 pixels of it, so a large photo is no reason to go
 * without a vector -- a shop's 6000-pixel product shot is a perfectly good
 * garment. The reader skips rows and columns as it goes, so memory is bounded
 * by what comes out rather than by what the header claims. A photo already
 * within [maxSide] decodes exactly as `ImageIO.read` decodes it, which matters:
 * the phone embeds the same photos, and the two sides' vectors are compared.
 */
internal fun decodeAtMost(photo: ByteArray, maxSide: Int): BufferedImage? =
    ImageIO.createImageInputStream(ByteArrayInputStream(photo))?.use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            // Seek forward only and ignore metadata, as ImageIO.read does.
            reader.setInput(input, true, true)
            val step = maxOf(
                ceilDiv(reader.getWidth(0), maxSide),
                ceilDiv(reader.getHeight(0), maxSide),
                1,
            )
            val param = reader.defaultReadParam
            if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
            reader.read(0, param)
        } finally {
            reader.dispose()
        }
    }

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
