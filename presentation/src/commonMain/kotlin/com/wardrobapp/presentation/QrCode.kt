package com.wardrobapp.presentation

import kotlin.math.abs

/**
 * A QR code, as the grid of modules a screen draws: [isDark] at each of
 * [size] × [size] positions, quiet zone not included.
 *
 * Written here rather than depended on, for the one thing this app shows a QR
 * code for -- a pairing link of about a hundred bytes, in Settings in the
 * browser. The libraries that would draw one in Compose Multiplatform each pin
 * a Compose version of their own, and :ui's two build files would have to
 * agree with every one of them; the libraries that do not are JVM-only, and
 * the page that shows the code is Wasm. An encoder for one mode at one error
 * correction level is a few hundred lines with nothing to keep up with, and
 * QrCodeTest reads every code it makes back with ZXing's decoder, which is
 * what a phone's scanner is.
 *
 * What it does, and no more: byte mode, error correction level M, versions 1
 * to 10 (up to 213 bytes), with the mask chosen by the standard's penalty
 * rules. M rather than L because a code on a laptop screen is read at an
 * angle, through glare, by a phone that may not focus well; it costs a
 * version or so at this size, which nobody will notice. The algorithm follows
 * Project Nayuki's QR Code generator (MIT), which is the clearest reading of
 * ISO/IEC 18004 there is.
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    fun isDark(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        /** The highest version [encode] will use; [MAX_BYTES] is what it holds. */
        const val MAX_VERSION = 10

        /** The most bytes a code here can carry, at level M in version [MAX_VERSION]. */
        val MAX_BYTES: Int get() = byteCapacity(MAX_VERSION)

        /**
         * [text], as UTF-8, in the smallest version that holds it; null if it
         * is longer than [MAX_BYTES], which nothing this app shows comes near.
         */
        fun encode(text: String): QrCode? {
            val bytes = text.encodeToByteArray()
            val version = (1..MAX_VERSION).firstOrNull { bytes.size <= byteCapacity(it) } ?: return null
            return Builder(version).build(dataCodewords(bytes, version))
        }

        /** Bytes a version holds in byte mode at level M: its data codewords less the mode and count headers. */
        private fun byteCapacity(version: Int): Int =
            (numDataCodewords(version) * 8 - 4 - countBits(version)) / 8

        /** Byte mode's character count is eight bits wide up to version 9, sixteen from 10. */
        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        /** The data bit stream, padded to fill the version: mode, count, the bytes, terminator, padding. */
        private fun dataCodewords(bytes: ByteArray, version: Int): IntArray {
            val capacity = numDataCodewords(version)
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(bytes.size, countBits(version))
            for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
            bits.append(0, minOf(4, capacity * 8 - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity * 8) {
                bits.append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return bits.toCodewords()
        }

        /** Modules left for data and error correction once the function patterns are drawn. */
        private fun numRawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val numAlign = version / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun numDataCodewords(version: Int): Int =
            numRawDataModules(version) / 8 - ECC_CODEWORDS_PER_BLOCK[version] * NUM_BLOCKS[version]

        // Level M, versions 1 to 10, from the standard's table 9. Index 0 is
        // there so a version indexes its own entry.
        private val ECC_CODEWORDS_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        /** Format bits' error correction level for M, which is 00 -- not M's position in L, M, Q, H. */
        private const val LEVEL_M_FORMAT_BITS = 0

        private class BitBuffer {
            private val bits = ArrayList<Boolean>()
            val size: Int get() = bits.size

            fun append(value: Int, length: Int) {
                for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
            }

            fun toCodewords(): IntArray = IntArray(bits.size / 8) { i ->
                (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) }
            }
        }

        /** The grid for one version, drawn in the order the standard describes. */
        private class Builder(private val version: Int) {
            val size = version * 4 + 17
            val modules = Array(size) { BooleanArray(size) }
            val isFunction = Array(size) { BooleanArray(size) }

            fun build(data: IntArray): QrCode {
                drawFunctionPatterns()
                drawCodewords(addEccAndInterleave(data))
                val mask = (0 until 8).minBy { mask ->
                    applyMask(mask)
                    drawFormatBits(mask)
                    penalty().also { applyMask(mask) } // XOR twice is undoing it
                }
                applyMask(mask)
                drawFormatBits(mask)
                return QrCode(size, modules)
            }

            private fun set(x: Int, y: Int, dark: Boolean) {
                modules[y][x] = dark
                isFunction[y][x] = true
            }

            private fun drawFunctionPatterns() {
                for (i in 0 until size) {
                    set(6, i, i % 2 == 0)
                    set(i, 6, i % 2 == 0)
                }
                drawFinder(3, 3)
                drawFinder(size - 4, 3)
                drawFinder(3, size - 4)

                val positions = alignmentPositions()
                val last = positions.size - 1
                for (i in positions.indices) {
                    for (j in positions.indices) {
                        // Not where a finder already is.
                        if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
                        drawAlignment(positions[i], positions[j])
                    }
                }

                // Reserved now so data is not written over them; the real
                // format bits go in once the mask is chosen.
                drawFormatBits(0)
                drawVersion()
            }

            /** A finder and its light separator, clipped at the edges of the symbol. */
            private fun drawFinder(x: Int, y: Int) {
                for (dy in -4..4) {
                    for (dx in -4..4) {
                        val distance = maxOf(abs(dx), abs(dy))
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until size && yy in 0 until size) set(xx, yy, distance != 2 && distance != 4)
                    }
                }
            }

            private fun drawAlignment(x: Int, y: Int) {
                for (dy in -2..2) {
                    for (dx in -2..2) set(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
                }
            }

            /** Centres of the alignment patterns along each axis: none in version 1. */
            private fun alignmentPositions(): IntArray {
                if (version == 1) return IntArray(0)
                val numAlign = version / 7 + 2
                val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
                val result = IntArray(numAlign)
                result[0] = 6
                var position = size - 7
                for (i in numAlign - 1 downTo 1) {
                    result[i] = position
                    position -= step
                }
                return result
            }

            private fun drawFormatBits(mask: Int) {
                val data = LEVEL_M_FORMAT_BITS shl 3 or mask
                var remainder = data
                repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
                val bits = (data shl 10 or remainder) xor 0x5412
                fun bit(i: Int) = (bits ushr i) and 1 != 0

                // Around the top-left finder.
                for (i in 0..5) set(8, i, bit(i))
                set(8, 7, bit(6))
                set(8, 8, bit(7))
                set(7, 8, bit(8))
                for (i in 9 until 15) set(14 - i, 8, bit(i))

                // And split between the other two.
                for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
                for (i in 8 until 15) set(8, size - 15 + i, bit(i))
                set(8, size - 8, true) // The dark module, always dark.
            }

            /** Versions 7 and up say which they are, twice, beside two of the finders. */
            private fun drawVersion() {
                if (version < 7) return
                var remainder = version
                repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
                val bits = version shl 12 or remainder
                for (i in 0 until 18) {
                    val dark = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, dark)
                    set(b, a, dark)
                }
            }

            /** The data split into blocks, each given its error correction, and the blocks interleaved. */
            private fun addEccAndInterleave(data: IntArray): IntArray {
                val numBlocks = NUM_BLOCKS[version]
                val eccLength = ECC_CODEWORDS_PER_BLOCK[version]
                val rawCodewords = numRawDataModules(version) / 8
                val numShortBlocks = numBlocks - rawCodewords % numBlocks
                val shortBlockLength = rawCodewords / numBlocks

                val divisor = reedSolomonDivisor(eccLength)
                val blocks = ArrayList<IntArray>()
                var k = 0
                for (i in 0 until numBlocks) {
                    val length = shortBlockLength - eccLength + if (i < numShortBlocks) 0 else 1
                    val dat = data.copyOfRange(k, k + length)
                    k += length
                    val ecc = reedSolomonRemainder(dat, divisor)
                    // A short block is padded by one so the columns line up;
                    // the padding is skipped when interleaving.
                    val padded = if (i < numShortBlocks) dat + 0 else dat
                    blocks.add(padded + ecc)
                }

                val result = ArrayList<Int>(rawCodewords)
                for (i in blocks[0].indices) {
                    for ((j, block) in blocks.withIndex()) {
                        if (i != shortBlockLength - eccLength || j >= numShortBlocks) result.add(block[i])
                    }
                }
                return result.toIntArray()
            }

            /** Data in the zigzag the standard reads it in: up and down two-module columns from the right. */
            private fun drawCodewords(data: IntArray) {
                var i = 0
                var right = size - 1
                while (right >= 1) {
                    if (right == 6) right = 5 // The vertical timing pattern is skipped whole.
                    for (vertical in 0 until size) {
                        for (j in 0..1) {
                            val x = right - j
                            val upward = (right + 1) and 2 == 0
                            val y = if (upward) size - 1 - vertical else vertical
                            if (!isFunction[y][x] && i < data.size * 8) {
                                modules[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                                i++
                            }
                            // Anything left over is a remainder bit, light,
                            // which is what the grid already holds.
                        }
                    }
                    right -= 2
                }
            }

            private fun applyMask(mask: Int) {
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val invert = when (mask) {
                            0 -> (x + y) % 2 == 0
                            1 -> y % 2 == 0
                            2 -> x % 3 == 0
                            3 -> (x + y) % 3 == 0
                            4 -> (x / 3 + y / 2) % 2 == 0
                            5 -> x * y % 2 + x * y % 3 == 0
                            6 -> (x * y % 2 + x * y % 3) % 2 == 0
                            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                        }
                        if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
                    }
                }
            }

            /**
             * The standard's four penalties: long runs of one colour, two-by-two
             * blocks, anything a scanner could take for a finder, and a balance
             * of dark and light away from half. The lowest total is the mask
             * that reads best.
             */
            private fun penalty(): Int {
                var result = 0
                fun line(get: (Int) -> Boolean) {
                    var run = 1
                    for (i in 1 until size) {
                        if (get(i) == get(i - 1)) {
                            run++
                        } else {
                            if (run >= 5) result += run - 2
                            run = 1
                        }
                    }
                    if (run >= 5) result += run - 2

                    // Dark-light-dark-dark-dark-light-dark with four light on
                    // either side; beyond the edge counts as light, as the
                    // quiet zone is.
                    fun at(i: Int) = i in 0 until size && get(i)
                    for (i in -4 until size) {
                        val core = at(i) && !at(i + 1) && at(i + 2) && at(i + 3) && at(i + 4) && !at(i + 5) && at(i + 6)
                        if (!core) continue
                        val lightBefore = (1..4).none { at(i - it) }
                        val lightAfter = (7..10).none { at(i + it) }
                        if (lightBefore || lightAfter) result += 40
                    }
                }
                for (y in 0 until size) line { x -> modules[y][x] }
                for (x in 0 until size) line { y -> modules[y][x] }

                for (y in 0 until size - 1) {
                    for (x in 0 until size - 1) {
                        val c = modules[y][x]
                        if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
                    }
                }

                val dark = modules.sumOf { row -> row.count { it } }
                val total = size * size
                result += abs(dark * 20 - total * 10) / total * 10
                return result
            }
        }

        private fun reedSolomonDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1, the field QR codes use. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }
    }
}
