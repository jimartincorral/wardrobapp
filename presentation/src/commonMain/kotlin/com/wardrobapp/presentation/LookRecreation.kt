package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.GARMENT_TYPE_PROMPT_NAMES
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.colorDistance
import com.wardrobapp.domain.colorSimilarity
import com.wardrobapp.domain.garmentTypeNamed
import kotlinx.serialization.Serializable

/*
 * A saved look, rebuilt from the wardrobe.
 *
 * Until this, a look did one thing: its photo's vector nudged every suggestion
 * a little towards it (StyleTaste). Somebody who saved a photo of a navy
 * blazer over a white shirt and jeans could not ask the obvious question --
 * "can I wear that?" -- and get "yes, with these" or "nearly; you have no
 * blazer". Gemini Nano can list what a look is made of, which the style model
 * cannot, so on a phone that has it a look opens into its pieces, each matched
 * to a garment or marked as missing.
 *
 * Everything here is plain code over that list, so it is tested without a
 * model: the question asked, the answer read, and the matching.
 */

/**
 * One thing worn in a look, as read off its photo: where it sits in the
 * catalogue, and its colour as a palette hex, when the reading named one the
 * palette has. Serializable because the reading is stored, so a look is read
 * once rather than every time it is opened.
 */
@Serializable
data class LookPiece(
    val category: String,
    val subcategory: String? = null,
    val color: String? = null,
) {
    /** The piece as a gap would describe it, for the add form to start from. */
    fun asWanted(): PhantomGarment = PhantomGarment(category, subcategory, color.orEmpty())
}

/** How many pieces a look is read as, at most: an outfit is not a wardrobe. */
const val MAX_LOOK_PIECES = 8

/**
 * Categories a look's pieces are never matched in: nobody is shown wearing
 * their underwear in a look worth saving, and a model that thinks it saw some
 * is wrong about something.
 */
private val UNREAD_CATEGORIES = setOf("underwear")

/**
 * The question put to Gemini Nano about a look's photo. One line per piece,
 * in a shape [parseLookPieces] reads, each part from a list it checks: the
 * types the catalogue has and the colours the palette has. Colours by the
 * palette's keys, in English whatever the app's language, since a key is what
 * is matched.
 */
val LOOK_PIECES_PROMPT: String = buildString {
    appendLine("List each item of clothing, footwear and accessory the person in this photo is wearing.")
    appendLine("Write one item per line, as: type | colour")
    appendLine("type is one of: ${GARMENT_TYPE_PROMPT_NAMES.joinToString(", ")}")
    appendLine("colour is one of: ${GARMENT_COLORS.joinToString(", ") { colorWord(it.first) }}")
    append("Write nothing else.")
}

/** A palette key as words: `lightBlue` is "light blue". */
private fun colorWord(key: String): String =
    key.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }.lowercase()

private fun letters(text: String): String = text.lowercase().filter { it.isLetter() }

/** Each palette colour by its key, its words, and the usual other spellings, as its hex. */
private val COLOR_BY_WORD: Map<String, String> = buildMap {
    for ((key, hex) in GARMENT_COLORS) {
        put(letters(key), hex)
        put(letters(colorWord(key)), hex)
    }
    // The spellings a model writes that the palette's keys do not.
    GARMENT_COLORS.toMap().let { palette ->
        palette["gray"]?.let { put("grey", it) }
        palette["lightBlue"]?.let { put("skyblue", it); put("paleblue", it) }
        palette["navy"]?.let { put("navyblue", it); put("darkblue", it) }
        palette["multi"]?.let { put("multicolour", it); put("multicolor", it); put("multicoloured", it) }
    }
}

/**
 * The pieces Gemini Nano listed, read as leniently as their shape allows and
 * as strictly as their content does: bullets, numbering and stray words are
 * stripped, but a type the catalogue does not have is dropped, a colour the
 * palette does not have is left unknown, and the same piece named twice
 * counts once. At most [MAX_LOOK_PIECES], in the order given.
 */
fun parseLookPieces(answer: String): List<LookPiece> {
    val pieces = mutableListOf<LookPiece>()
    for (raw in answer.lines()) {
        val line = raw.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").replace("*", "")
        val parts = line.split('|').map { it.trim() }
        if (parts.size < 2) continue
        val type = garmentTypeNamed(parts[0]) ?: continue
        if (type.category in UNREAD_CATEGORIES) continue
        val piece = LookPiece(type.category, type.subcategory, COLOR_BY_WORD[letters(parts[1])])
        if (piece !in pieces) pieces += piece
        if (pieces.size == MAX_LOOK_PIECES) break
    }
    return pieces
}

/** One piece of a look and what the wardrobe has for it, or null when it has nothing close. */
data class LookRow(val piece: LookPiece, val garment: GarmentRecord?)

/**
 * How far apart, in CIELAB ΔE (CIE76, as colorDistance measures), a garment's
 * colour may be from a piece's and still stand in for it.
 *
 * Both sides are palette colours -- a garment's are snapped to the palette
 * when they are detected, and a piece is read as one -- so this is really a
 * choice of which palette neighbours count. At 46: cream for white (22.8),
 * silver for grey (24.1), tan for khaki (7), blue for navy (45.1), burgundy
 * for red (43.5); not grey for black (53.6), and not navy for black (81). Red
 * for brown (44.5) gets in too, which is the price of blue for navy; the
 * closer colour still wins, through colorSimilarity, wherever there is one.
 */
const val LOOK_COLOR_TOLERANCE = 46.0

/** How much a garment's vector resembling the look's breaks a tie, against a colour's 0..1. */
private const val STYLE_WEIGHT = 0.2

/** The order pieces are matched in, outside in: the pieces that say the most about a look first. */
private val SLOT_ORDER = listOf("dresses", "tops", "bottoms", "midlayer", "outerwear", "shoes", "activewear", "loungewear", "accessories")

/**
 * The wardrobe's answer to each piece of a look: the garment that comes
 * closest, or none.
 *
 * A garment stands in for a piece when it is in the same category and, when
 * the piece's colour is known, within [LOOK_COLOR_TOLERANCE] of it. The same
 * type is preferred by a whole point, which outweighs any colour, so navy
 * trousers are matched by blue trousers before navy chinos; but in the
 * clothing a different type of the right colour does stand in -- chinos for
 * trousers -- while an accessory must be the same type, since a belt is no
 * kind of hat. Where both vectors exist, how much the garment's photo looks
 * like the look's breaks what is left of a tie. Each garment is used once,
 * and pieces are matched outside in, so a jacket does not take the top's
 * place. Only available garments are offered, as everywhere an outfit is
 * made.
 */
fun recreateLook(
    pieces: List<LookPiece>,
    garments: List<GarmentRecord>,
    lookVector: FloatArray? = null,
    garmentVectors: Map<String, FloatArray> = emptyMap(),
): List<LookRow> {
    val used = mutableSetOf<String>()
    // By position, not by piece: a look can have two white shirts in it.
    val matched = arrayOfNulls<GarmentRecord>(pieces.size)
    val order = pieces.indices.sortedBy { index ->
        SLOT_ORDER.indexOf(pieces[index].category).let { if (it < 0) SLOT_ORDER.size else it }
    }

    for (index in order) {
        val piece = pieces[index]
        val best = garments
            .asSequence()
            .filter { it.isAvailable && it.id !in used && it.category == piece.category }
            .filter { garment ->
                piece.subcategory == null || piece.category != "accessories" ||
                    piece.subcategory in garment.effectiveSubcategories
            }
            .mapNotNull { garment -> score(piece, garment, lookVector, garmentVectors[garment.id])?.let { garment to it } }
            .maxByOrNull { it.second }
            ?.first
        matched[index] = best
        best?.let { used += it.id }
    }

    return pieces.mapIndexed { index, piece -> LookRow(piece, matched[index]) }
}

/** How well [garment] stands in for [piece]; null when it cannot at all. */
private fun score(piece: LookPiece, garment: GarmentRecord, lookVector: FloatArray?, garmentVector: FloatArray?): Double? {
    val colourScore = piece.color?.let { wanted ->
        val closest = garment.palette.mapNotNull { colorDistance(wanted, it) }.minOrNull() ?: return null
        if (closest > LOOK_COLOR_TOLERANCE) return null
        garment.palette.maxOf { colorSimilarity(wanted, it) }
    } ?: 0.0
    val typeScore = if (piece.subcategory != null && piece.subcategory in garment.effectiveSubcategories) 1.0 else 0.0
    val styleScore = if (lookVector != null && garmentVector != null && lookVector.size == garmentVector.size) {
        lookVector.indices.sumOf { (lookVector[it] * garmentVector[it]).toDouble() }
    } else {
        0.0
    }
    return typeScore + colourScore + STYLE_WEIGHT * styleScore
}
