package com.wardrobapp.server

import com.wardrobapp.data.cutoutFraming
import com.wardrobapp.data.isCutoutFilename
import com.wardrobapp.data.opaqueBounds
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * Framing cut-outs around their garment, on the server.
 *
 * The arithmetic and the reasons are :data's CutoutFraming, shared with the
 * phone; what is here is reading a BufferedImage's pixels into the shape it
 * wants and drawing the answer onto a fresh canvas.
 */

/**
 * [image] framed around its garment, or null when framing would change
 * nothing worth writing: it is framed already, or there is no garment in it.
 */
fun framedAroundGarment(image: BufferedImage): BufferedImage? {
    val width = image.width
    val height = image.height
    val pixels = image.getRGB(0, 0, width, height, null, 0, width)

    val framing = cutoutFraming(opaqueBounds(pixels, width, height), width, height) ?: return null
    if (!framing.changes(width, height)) return null

    // ARGB, and transparent where nothing is drawn: a new image is all zero,
    // which in ARGB is transparent black.
    val framed = BufferedImage(framing.width, framing.height, BufferedImage.TYPE_INT_ARGB)
    framed.createGraphics().apply {
        drawImage(image, framing.drawX, framing.drawY, null)
        dispose()
    }
    return framed
}

/**
 * Frame every cut-out in [directory] that is not framed yet; how many were.
 *
 * The pass that cut-outs stored before framing existed are owed, run when the
 * server starts (see Main) and safe to run again: a cut-out already framed
 * costs a decode and nothing else. In place, under the same name, because the
 * garment rows hold that name -- written beside and moved in, as PhotoFiles
 * stores an upload, so a server stopped halfway leaves a stray `.part` rather
 * than half a PNG where a garment's photo was. A file that will not decode is
 * left exactly as it is.
 */
fun frameLooseCutouts(directory: File): Int {
    val cutouts = directory.listFiles()
        ?.filter { it.isFile && isCutoutFilename(it.name) }
        ?.sortedBy { it.name }
        ?: return 0

    var framed = 0
    for (file in cutouts) {
        try {
            val image = ImageIO.read(file) ?: continue
            val result = framedAroundGarment(image) ?: continue

            val partial = File.createTempFile("frame-", ".part", directory)
            try {
                ImageIO.write(result, "png", partial)
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                framed += 1
            } finally {
                partial.delete()
            }
        } catch (_: Exception) {
            // Unreadable, unwritable, out of room: this one stays as it was.
        }
    }
    return framed
}
