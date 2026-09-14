package com.wardrobapp.presentation

import java.io.File
import java.util.zip.InflaterInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher icon is complete, and is still the logo.
 *
 * Here, in a module that builds without the Android SDK, for the same reason
 * [StringResourceParityTest] is: `:app` cannot be compiled on a machine with no
 * SDK, so a test that reads its resources is the only check available before CI.
 *
 * What it is guarding against is the quiet kind of wrong. A missing density does
 * not fail a build -- Android scales the nearest one it can find -- so the first
 * sign is a blurry icon on somebody's phone. A raster the wrong size for the
 * folder it is in is worse, because it looks deliberate. And the adaptive icon is
 * three references to files that have to exist, none of which the manifest
 * checks.
 *
 * Every icon here is cut from `art/logo.png` by
 * `scripts/generate-launcher-icons.py`. That makes two more things worth
 * checking, both of which look fine in a file listing and wrong on a phone: that
 * the artwork stays inside the circle a launcher may mask it to, and that the
 * master it is all cut from is still in the repository. The second matters more
 * than it looks -- without the logo, the icons are the only copy of it, and the
 * only way to change one is to edit a PNG by hand.
 */
class LauncherIconTest {

    /** The legacy icon's size at each density, in pixels: 48dp apiece. */
    private val densities = mapOf(
        "mipmap-mdpi" to 48,
        "mipmap-hdpi" to 72,
        "mipmap-xhdpi" to 96,
        "mipmap-xxhdpi" to 144,
        "mipmap-xxxhdpi" to 192,
    )

    /** What the adaptive icon's layers are, against the same 48dp: 108dp apiece. */
    private val layerScale = 108.0 / 48.0

    @Test
    fun `every density has every icon, at the size that density means`() {
        val failures = mutableListOf<String>()

        for ((folder, legacy) in densities) {
            val expected = mapOf(
                "ic_launcher.png" to legacy,
                "ic_launcher_round.png" to legacy,
                // The adaptive layers are the larger canvas, of which the
                // launcher shows the middle.
                "ic_launcher_foreground.png" to (legacy * layerScale).toInt(),
                "ic_launcher_monochrome.png" to (legacy * layerScale).toInt(),
            )

            for ((name, size) in expected) {
                check(File(File(resourceDirectory(), folder), name), size, folder, failures)
            }

            // Not a launcher icon, but cut from the same logo by the same script
            // and able to go stale in the same silence: the mark the Welcome
            // screen draws, at 56dp.
            val drawable = "drawable-${folder.substringAfter('-')}"
            check(
                File(File(resourceDirectory(), drawable), "ic_brand_mark.png"),
                legacy * 56 / 48,
                drawable,
                failures,
            )
        }

        assertTrue(
            failures.isEmpty(),
            "run scripts/generate-launcher-icons.py:\n  " + failures.joinToString("\n  "),
        )
    }

    /** One file, at the size the folder it is in means. */
    private fun check(file: File, size: Int, folder: String, failures: MutableList<String>) {
        if (!file.isFile) {
            failures += "$folder/${file.name} is missing"
            return
        }

        val actual = pngSize(file)
        if (actual == null) {
            failures += "$folder/${file.name} is not a PNG"
        } else if (actual.first != size || actual.second != size) {
            failures += "$folder/${file.name} is ${actual.first}x${actual.second}, expected " +
                "${size}x$size"
        }
    }

    @Test
    fun `the adaptive icon names three layers that exist`() {
        for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val file = File(File(resourceDirectory(), "mipmap-anydpi-v26"), name)
            assertTrue(file.isFile, "$name is missing, so Android 8 and up fall back to the raster")

            val document = parse(file)

            // Every layer, including monochrome: without it, a phone with themed
            // icons turned on shows this app as a grey blob while every other icon
            // is tinted.
            for (layer in listOf("background", "foreground", "monochrome")) {
                val elements = document.getElementsByTagName(layer)
                assertEquals(1, elements.length, "$name has no <$layer>")

                val reference = (elements.item(0) as org.w3c.dom.Element)
                    .getAttribute("android:drawable")
                assertTrue(reference.isNotEmpty(), "$name's <$layer> names no drawable")
                assertTrue(
                    referencedResourceExists(reference),
                    "$name's <$layer> points at $reference, which does not exist",
                )
            }
        }
    }

    @Test
    fun `the logo the icons are cut from is in the repository`() {
        // The icons are derived files. Losing the master would not break a build
        // or fail a test anywhere else, and would be discovered by whoever next
        // needed to change the icon and found that the only way to do it was to
        // repaint five PNGs.
        val logo = File(resourceDirectory().parentFile.parentFile.parentFile.parentFile, "art/logo.png")
        assertTrue(logo.isFile, "art/logo.png is missing; the icons cannot be regenerated")
        assertTrue(pngSize(logo) != null, "art/logo.png is not a PNG")
    }

    @Test
    fun `the artwork stays inside the circle a launcher may mask it to`() {
        // The 108dp canvas promises very little. Launchers mask it to a shape of
        // their own -- circle, squircle, teardrop -- and each of those sits inside
        // a circle of radius 36dp about the centre, so that circle rather than the
        // 72dp square usually quoted is the real boundary.
        //
        // The generator fits the artwork to a little under it. This is what says
        // it still does: a change to the fit, or a logo whose mark sits somewhere
        // new in the frame, comes out here rather than as a clipped icon on a
        // launcher that happens to use round ones.
        //
        // The largest layer only. Every density is the same drawing at a different
        // size, so one of them answers for all five, and this decodes a whole PNG
        // to ask.
        val file = File(File(resourceDirectory(), "mipmap-xxxhdpi"), "ic_launcher_foreground.png")
        val (size, pixels) = decodePng(file)

        val centre = size / 2.0
        val limit = size * 36.0 / 108.0
        var furthest = 0.0

        for (y in 0 until size) {
            for (x in 0 until size) {
                // Anything but the very faintest edge of the letterpress, whose
                // outermost pixels fade to nothing and would put the measurement
                // wherever the matte's threshold happened to fall.
                if ((pixels[(y * size + x) * 4 + 3].toInt() and 0xff) < 24) continue
                furthest = maxOf(furthest, hypot(x + 0.5 - centre, y + 0.5 - centre))
            }
        }

        assertTrue(furthest > 0, "the foreground layer is empty")
        assertTrue(
            furthest <= limit,
            "the artwork reaches ${"%.0f".format(furthest)}px from the centre of a " +
                "${size}px layer, past the ${"%.0f".format(limit)}px a round mask leaves",
        )
    }

    @Test
    fun `the splash follows the theme and the icon does not`() {
        // These two were one value once, which was right while the icon was white
        // on violet: the splash could be the icon's own background and neither
        // could flash a colour the other did not have.
        //
        // The logo's cream breaks that. Cream is the correct window on a light
        // phone and is the white flash on a dark one, so the splash needs a night
        // variant -- and the icon must not have one, because a launcher icon that
        // changes colour with the system theme is two icons. Hence two colours,
        // equal in values/ and different in values-night/, which is exactly the
        // arrangement somebody tidying up would collapse back into one.
        val day = File(File(resourceDirectory(), "values"), "splash_background.xml")
        val night = File(File(resourceDirectory(), "values-night"), "splash_background.xml")
        assertTrue(day.isFile, "no values/splash_background.xml")
        assertTrue(night.isFile, "no values-night/splash_background.xml, so a dark phone flashes cream")

        val icon = colours(File(File(resourceDirectory(), "values"), "ic_launcher_background.xml"))
        assertEquals(
            icon["ic_launcher_background"],
            colours(day)["splash_background"],
            "the light splash is not the icon's background, so the two show a seam",
        )
        assertTrue(
            colours(night)["splash_background"] != colours(day)["splash_background"],
            "the night splash is the light one, which is the flash it exists to prevent",
        )
        assertTrue(
            !File(resourceDirectory(), "values-night").resolve("ic_launcher_background.xml").isFile,
            "the icon's background has a night variant, so the app has two launcher icons",
        )
    }

    /**
     * Whether `@drawable/x`, `@mipmap/x` or `@color/x` names something that is there.
     *
     * Only the kinds the adaptive icon uses. A bitmap layer lives in the density
     * folders, so a `@mipmap` reference is satisfied by any one of them; a colour
     * is a name inside a values file, so that is looked up by name rather than by
     * filename.
     */
    private fun referencedResourceExists(reference: String): Boolean {
        val (kind, name) = reference.removePrefix("@").split('/', limit = 2)
            .let { if (it.size == 2) it[0] to it[1] else return false }

        return when (kind) {
            "drawable" -> File(File(resourceDirectory(), "drawable"), "$name.xml").isFile ||
                File(File(resourceDirectory(), "drawable"), "$name.png").isFile
            "mipmap" -> densities.keys.any {
                File(File(resourceDirectory(), it), "$name.png").isFile
            }
            "color" -> File(resourceDirectory(), "values")
                .listFiles()
                ?.any { it.extension == "xml" && it.readText().contains("name=\"$name\"") } == true
            else -> false
        }
    }

    /** The IHDR width and height of a PNG, or null if it is not one. */
    private fun pngSize(file: File): Pair<Int, Int>? {
        val header = file.inputStream().use { it.readNBytes(24) }
        if (header.size < 24) return null

        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        if (!header.copyOfRange(0, 4).contentEquals(signature)) return null

        // IHDR is required to be the first chunk, so the dimensions are at a fixed
        // offset: 8 signature + 4 length + 4 type.
        fun intAt(offset: Int): Int = (0..3).fold(0) { value, byte ->
            (value shl 8) or (header[offset + byte].toInt() and 0xff)
        }

        return intAt(16) to intAt(20)
    }

    /**
     * A square 8-bit RGBA PNG as its size and its pixels.
     *
     * Only what the generator writes, which is why it can be this short: no
     * palettes, no interlacing, no bit depth but eight. Anything else fails rather
     * than being guessed at, because a wrong guess here would be a test that
     * measured noise and passed.
     */
    private fun decodePng(file: File): Pair<Int, ByteArray> {
        val bytes = file.readBytes()
        var at = 8
        var width = 0
        var height = 0
        val compressed = java.io.ByteArrayOutputStream()

        while (at < bytes.size) {
            fun intAt(offset: Int): Int = (0..3).fold(0) { value, byte ->
                (value shl 8) or (bytes[offset + byte].toInt() and 0xff)
            }

            val length = intAt(at)
            val kind = String(bytes, at + 4, 4, Charsets.US_ASCII)
            when (kind) {
                "IHDR" -> {
                    width = intAt(at + 8)
                    height = intAt(at + 12)
                    val depth = bytes[at + 16].toInt()
                    val colour = bytes[at + 17].toInt()
                    val interlace = bytes[at + 20].toInt()
                    assertTrue(
                        depth == 8 && colour == 6 && interlace == 0,
                        "${file.name} is depth $depth, colour type $colour, interlace " +
                            "$interlace; this reader only does 8-bit RGBA",
                    )
                }
                "IDAT" -> compressed.write(bytes, at + 8, length)
            }
            at += 12 + length
        }

        assertEquals(width, height, "${file.name} is not square")

        val raw = InflaterInputStream(compressed.toByteArray().inputStream()).readBytes()
        val stride = width * 4
        val pixels = ByteArray(height * stride)

        // Undo the per-row filters. A PNG may use a different one on each row, so
        // all five have to be here whatever this file happens to contain.
        for (y in 0 until height) {
            val start = y * (stride + 1)
            val method = raw[start].toInt()
            for (i in 0 until stride) {
                val value = raw[start + 1 + i].toInt() and 0xff
                val left = if (i >= 4) pixels[y * stride + i - 4].toInt() and 0xff else 0
                val up = if (y > 0) pixels[(y - 1) * stride + i].toInt() and 0xff else 0
                val corner =
                    if (y > 0 && i >= 4) pixels[(y - 1) * stride + i - 4].toInt() and 0xff else 0

                val predicted = when (method) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> {
                        val pa = kotlin.math.abs(up - corner)
                        val pb = kotlin.math.abs(left - corner)
                        val pc = kotlin.math.abs(left + up - 2 * corner)
                        if (pa <= pb && pa <= pc) left else if (pb <= pc) up else corner
                    }
                    else -> throw AssertionError("unknown PNG filter $method on row $y")
                }
                pixels[y * stride + i] = ((value + predicted) and 0xff).toByte()
            }
        }

        return width to pixels
    }

    private fun colours(file: File): Map<String, String> {
        val elements = parse(file).getElementsByTagName("color")
        return (0 until elements.length)
            .map { elements.item(it) as org.w3c.dom.Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .also { it.isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(file)

    private fun resourceDirectory(): File {
        val path = System.getProperty("appResDir")
            ?: error("appResDir was not set; see presentation/build.gradle.kts")
        return File(path)
    }
}
