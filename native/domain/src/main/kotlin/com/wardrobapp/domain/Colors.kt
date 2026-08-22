package com.wardrobapp.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Colour comparison for garments.
 *
 * Two different questions get asked of colours here, and they need different
 * answers:
 *
 *  - "are these the same colour?" -- duplicate detection. Answered by CIE76 dE,
 *    a perceptual *magnitude*.
 *  - "do these go together?" -- outfit harmony. Answered by hue angle, because
 *    whether two colours clash is a question about hue, not about how far apart
 *    they are overall.
 *
 * Conflating the two is what made harmony wrong: dE is dominated by lightness,
 * so navy-and-red (dE 127) and blue-and-orange (dE 139) were classified as
 * clashing while beige-on-brown (dE 72) was classified as a great match.
 */

/** Sentinel stored for garments the user marked as multi-coloured. */
const val MULTI_COLOR = "#RAINBOW"

/**
 * Distance reported between a multi-coloured garment and a specific colour.
 * Large enough to read as "not the same colour" without implying a measurement.
 */
const val MULTI_COLOR_DISTANCE = 100.0

/** Below this Lab chroma a colour reads as achromatic: it goes with anything. */
const val NEUTRAL_CHROMA = 15.0

/** dE below which two colours are the same colour for practical purposes. */
const val SAME_COLOR_DELTA_E = 5.0

/**
 * Scale for turning dE into a 0..1 similarity.
 *
 * `1 - dE/100` used to do this, but dE across this app's own palette reaches
 * 176, so 19% of colour pairs clamped to exactly 0 and became indistinguishable
 * from each other. An exponential decay is monotone over the whole range and
 * spends most of its resolution on the 0-40 band that actually matters.
 */
const val SIMILARITY_SCALE = 25.0

data class Rgb(val r: Int, val g: Int, val b: Int)

data class Lab(val l: Double, val a: Double, val b: Double)

private val HEX_PATTERN = Regex("^(?:[0-9a-f]{3}|[0-9a-f]{6})$", RegexOption.IGNORE_CASE)

/**
 * Parse `#RGB` or `#RRGGBB`, with or without the hash, in either case.
 *
 * Returns null rather than partial components for anything else. The original
 * returned `[255, 15, NaN]` for `#fff`, and that NaN propagated silently: every
 * comparison against it was false, so a malformed colour ended up scoring as a
 * mild *positive* harmony match. Restored backups and imported URLs are both
 * sources of colours this code did not write.
 */
fun parseHexColor(hex: String): Rgb? {
    val clean = hex.trim().removePrefix("#")
    if (!HEX_PATTERN.matches(clean)) return null

    val full = if (clean.length == 3) clean.map { "$it$it" }.joinToString("") else clean

    return Rgb(
        full.substring(0, 2).toInt(16),
        full.substring(2, 4).toInt(16),
        full.substring(4, 6).toInt(16),
    )
}

/** Convert sRGB to CIE Lab (D65 white point). */
internal fun rgbToLab(rgb: Rgb): Lab {
    fun linearize(channel: Int): Double {
        val n = channel / 255.0
        return if (n > 0.04045) ((n + 0.055) / 1.055).pow(2.4) else n / 12.92
    }

    val rn = linearize(rgb.r)
    val gn = linearize(rgb.g)
    val bn = linearize(rgb.b)

    // Linear RGB to XYZ, normalised against D65
    var x = (rn * 0.4124564 + gn * 0.3575761 + bn * 0.1804375) / 0.95047
    var y = (rn * 0.2126729 + gn * 0.7151522 + bn * 0.0721750) / 1.00000
    var z = (rn * 0.0193339 + gn * 0.1191920 + bn * 0.9503041) / 1.08883

    val epsilon = 0.008856
    val kappa = 903.3
    x = if (x > epsilon) cbrt(x) else (kappa * x + 16) / 116
    y = if (y > epsilon) cbrt(y) else (kappa * y + 16) / 116
    z = if (z > epsilon) cbrt(z) else (kappa * z + 16) / 116

    return Lab(116 * y - 16, 500 * (x - y), 200 * (y - z))
}

private fun labOf(hex: String): Lab? = parseHexColor(hex)?.let { rgbToLab(it) }

private fun isMultiColor(hex: String) = hex.trim().uppercase() == MULTI_COLOR

private fun sameHexString(hex1: String, hex2: String) =
    hex1.trim().uppercase() == hex2.trim().uppercase()

private fun deltaE(lab1: Lab, lab2: Lab): Double = sqrt(
    (lab2.l - lab1.l).pow(2) + (lab2.a - lab1.a).pow(2) + (lab2.b - lab1.b).pow(2)
)

/**
 * CIE76 dE between two hex colours: 0 for identical, larger for more different.
 *
 * Returns null when either colour cannot be parsed, so callers decide what an
 * unknown colour means rather than inheriting a NaN.
 */
fun colorDistance(hex1: String, hex2: String): Double? {
    // Equality first: two multi-coloured garments are the same colour as each
    // other, and the sentinel check below used to report them as 100 apart.
    if (sameHexString(hex1, hex2)) return 0.0
    if (isMultiColor(hex1) || isMultiColor(hex2)) return MULTI_COLOR_DISTANCE

    val lab1 = labOf(hex1) ?: return null
    val lab2 = labOf(hex2) ?: return null

    return deltaE(lab1, lab2)
}

/**
 * How alike two colours are, from 1 (identical) down towards 0.
 *
 * An unknown colour scores 0: absence of information must not read as a match.
 */
fun colorSimilarity(hex1: String, hex2: String): Double {
    val distance = colorDistance(hex1, hex2) ?: return 0.0
    return exp(-distance / SIMILARITY_SCALE)
}

/** How two colours relate, in the terms that decide whether they go together. */
enum class ColorRelationship {
    UNKNOWN,
    SAME,
    NEUTRAL,
    ANALOGOUS,
    NEAR_MISS,
    CONTRASTING,
}

/** Smallest angle between two hues, in degrees (0..180). */
private fun hueGap(a: Double, b: Double): Double {
    val gap = abs(a - b) % 360
    return if (gap > 180) 360 - gap else gap
}

/**
 * Classify a colour pair.
 *
 * Hue drives this, with chroma deciding what counts as a neutral. Lab hue angles
 * are not spaced like an artist's colour wheel, so the bands below are set
 * against this app's actual palette rather than to textbook angles: true
 * contrasts (navy/red, blue/orange, red/green) land above 90 degrees apart,
 * and genuine analogues (navy/blue, gold/yellow, red/burgundy) below 45.
 */
fun colorRelationship(hex1: String, hex2: String): ColorRelationship {
    if (isMultiColor(hex1) || isMultiColor(hex2)) return ColorRelationship.UNKNOWN

    val lab1 = labOf(hex1) ?: return ColorRelationship.UNKNOWN
    val lab2 = labOf(hex2) ?: return ColorRelationship.UNKNOWN

    if (deltaE(lab1, lab2) < SAME_COLOR_DELTA_E) return ColorRelationship.SAME

    // A greyed-out colour has no hue worth comparing, so it sits with anything.
    // Derived from chroma rather than a hardcoded list of four hexes, which meant
    // beige and lavender were treated as loud colours.
    val chroma1 = hypot(lab1.a, lab1.b)
    val chroma2 = hypot(lab2.a, lab2.b)
    if (chroma1 < NEUTRAL_CHROMA || chroma2 < NEUTRAL_CHROMA) return ColorRelationship.NEUTRAL

    val hue1 = atan2(lab1.b, lab1.a) * 180 / PI
    val hue2 = atan2(lab2.b, lab2.a) * 180 / PI
    val gap = hueGap(hue1, hue2)

    return when {
        gap <= 45 -> ColorRelationship.ANALOGOUS
        gap <= 90 -> ColorRelationship.NEAR_MISS
        else -> ColorRelationship.CONTRASTING
    }
}

/**
 * How well two colours go together, from 0 (no opinion) to 1.
 *
 * Nothing scores negative. The original penalised anything more than dE 90
 * apart, which meant it actively pushed the suggestion engine away from
 * navy-and-red and blue-and-orange.
 */
fun colorHarmonyScore(hex1: String, hex2: String): Double = when (colorRelationship(hex1, hex2)) {
    ColorRelationship.SAME -> 0.3 // Works, but reads as unconsidered.
    ColorRelationship.NEUTRAL -> 0.5
    ColorRelationship.ANALOGOUS -> 0.6
    ColorRelationship.NEAR_MISS -> 0.2 // Close enough to look accidental rather than chosen.
    ColorRelationship.CONTRASTING -> 0.7
    ColorRelationship.UNKNOWN -> 0.0 // No information is not the same as a good match.
}
