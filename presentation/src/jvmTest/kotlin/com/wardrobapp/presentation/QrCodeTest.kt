package com.wardrobapp.presentation

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QrCode, read back by ZXing -- the decoder Android's scanners are built on --
 * from the picture the browser draws: dark modules on white, with the quiet
 * zone the screen leaves around it.
 *
 * Every version, because each has its own block structure, alignment
 * patterns from version 2 and version information from 7, and a mistake in
 * any of those breaks only the versions that have it. And every mask, by way
 * of varied payloads, since which mask wins depends on the data.
 */
class QrCodeTest {

    @Test
    fun `a pairing link reads back as itself`() {
        val link = pairingLinkFor("http://homeassistant.local:8100/", "ABCDE-FGHIJ-KMNPQ-RSTVW")
        assertEquals(link, decode(QrCode.encode(link)!!))
    }

    @Test
    fun `every version reads back, at its fullest and just past the one before`() {
        for (version in 1..QrCode.MAX_VERSION) {
            val capacity = capacityOf(version)
            for (length in setOf(capacity, capacityOf(version - 1) + 1)) {
                val text = payload(length, seed = version)
                val code = QrCode.encode(text)!!
                assertEquals(version * 4 + 17, code.size, "length $length")
                assertEquals(text, decode(code), "version $version, length $length")
            }
        }
    }

    @Test
    fun `many payloads, and so every mask, read back`() {
        for (seed in 0 until 60) {
            val text = payload(20 + seed * 3, seed)
            assertEquals(text, decode(QrCode.encode(text)!!), "seed $seed")
        }
    }

    @Test
    fun `text beyond ASCII is carried as UTF-8`() {
        val text = "wardrobapp://pair?address=http%3A%2F%2Fcasa.local&code=ÑANDÚ-€"
        assertEquals(text, decode(QrCode.encode(text)!!))
    }

    @Test
    fun `too much to carry is refused rather than cut short`() {
        assertTrue(QrCode.MAX_BYTES >= 200)
        assertNull(QrCode.encode("x".repeat(QrCode.MAX_BYTES + 1)))
    }

    /** The most bytes a version holds; found by asking, so the test does not share the encoder's table. */
    private fun capacityOf(version: Int): Int {
        if (version == 0) return 0
        var length = 1
        while (length < QrCode.MAX_BYTES && QrCode.encode("a".repeat(length + 1))!!.size <= version * 4 + 17) length++
        return length
    }

    /** Printable, varied text, so masks and penalties see different data from one seed to the next. */
    private fun payload(length: Int, seed: Int): String {
        val random = kotlin.random.Random(seed)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~%/:?&="
        return String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
    }

    private fun decode(code: QrCode, scale: Int = 4, quiet: Int = 4): String {
        val side = (code.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            val dark = x in 0 until code.size && y in 0 until code.size && code.isDark(x, y)
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        // Not PURE_BARCODE: that hint skips finding the code in the picture,
        // which is the half of decoding a phone actually does.
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        return QRCodeReader().decode(bitmap, hints).text
    }
}
