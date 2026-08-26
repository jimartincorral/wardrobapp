package com.wardrobapp.presentation

import com.wardrobapp.domain.OutfitSlot
import com.wardrobapp.domain.garmentSlotsFor

/**
 * An outfit as one picture: where each garment goes on the card.
 *
 * A flat-lay is how clothes are photographed together — worn top to bottom, laid
 * out top to bottom — so the arrangement is three bands: what goes on the upper
 * body, what goes on the legs, and what goes on the feet, with accessories beside
 * the shoes. Anything that fills no slot is left out rather than dropped into a
 * corner.
 *
 * The answer is fractions of the card rather than pixels, so the same layout
 * drives the card on screen and the image that gets shared: those are two
 * renderers, and a card that looked different from the picture of it would be two
 * layouts pretending to be one.
 *
 * Nothing here knows what a photo is. Whether the file loads, and what to do when
 * it does not, is the renderer's business.
 */

/** A garment to be placed: only what the layout needs to know about it. */
data class CardGarment(
    val id: String,
    val imageUri: String,
    val category: String,
    val subcategory: String? = null,
)

/**
 * Where one garment goes, as fractions of the card's width and height.
 *
 * `x` and `y` are the top-left corner, `width` and `height` the size, all in
 * 0..1. A renderer multiplies them by whatever it is drawing into.
 */
data class CardPlacement(
    val garment: CardGarment,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * The bands of a flat-lay, top to bottom.
 *
 * [weight] is how much of the card's height a band takes when every band is
 * present; a band with nothing in it gives its share to the others. Feet get less
 * than half of what a body gets, because shoes are shorter than a coat and a
 * card that pretends otherwise looks like a shoe advertisement.
 */
private enum class CardBand(val weight: Double) {
    UPPER(1.0),
    LOWER(1.0),
    FEET(0.55),
}

private fun bandOf(slot: OutfitSlot): CardBand = when (slot) {
    OutfitSlot.TOPS,
    OutfitSlot.DRESSES,
    OutfitSlot.OUTERWEAR,
    OutfitSlot.ACTIVEWEAR_SETS,
    OutfitSlot.LOUNGEWEAR_SETS,
    -> CardBand.UPPER

    OutfitSlot.BOTTOMS -> CardBand.LOWER

    // Accessories share the bottom band with the shoes: a bag or a belt laid
    // beside a pair of shoes is what a flat-lay does with them, and giving them a
    // band of their own would leave a strip of card empty in most outfits.
    OutfitSlot.SHOES,
    OutfitSlot.ACCESSORIES,
    -> CardBand.FEET
}

/**
 * Within a band, which garment is drawn first (leftmost).
 *
 * The garment the outfit is *about* leads, and the layer over it follows: a dress
 * before the coat over it, shoes before the bag beside them. Lower sorts first.
 */
private fun order(slot: OutfitSlot): Int = when (slot) {
    OutfitSlot.DRESSES -> 0
    OutfitSlot.ACTIVEWEAR_SETS -> 1
    OutfitSlot.LOUNGEWEAR_SETS -> 2
    OutfitSlot.TOPS -> 3
    OutfitSlot.OUTERWEAR -> 4
    OutfitSlot.BOTTOMS -> 5
    OutfitSlot.SHOES -> 6
    OutfitSlot.ACCESSORIES -> 7
}

/**
 * How much of the card one garment gets.
 *
 * Bands that are empty collapse and their height is shared out, so a top and a
 * pair of trousers fill the card rather than leaving a shoe-shaped hole at the
 * bottom. Within a band the garments share the width equally.
 *
 * Returns an empty list when there is nothing to draw, which is the renderer's
 * cue to show a placeholder rather than an empty card it has to guess about.
 */
fun outfitCardLayout(garments: List<CardGarment>): List<CardPlacement> {
    val placed = garments.mapNotNull { garment ->
        garmentSlotsFor(garment.category, garment.subcategory).firstOrNull()?.let { garment to it }
    }
    if (placed.isEmpty()) return emptyList()

    // Only the bands that have something in them, in top-to-bottom order.
    val bands = CardBand.entries.filter { band -> placed.any { bandOf(it.second) == band } }
    val total = bands.sumOf { it.weight }

    var y = 0.0

    return bands.flatMap { band ->
        val height = band.weight / total
        val row = placed
            .filter { bandOf(it.second) == band }
            // Stable within a slot: the outfit's own order decides, so the same
            // outfit always draws the same way.
            .sortedBy { order(it.second) }

        val width = 1.0 / row.size
        val top = y
        y += height

        row.mapIndexed { index, (garment, _) ->
            CardPlacement(
                garment = garment,
                x = index * width,
                y = top,
                width = width,
                height = height,
            )
        }
    }
}

/**
 * The shape of a card: three wide to four tall, the shape a garment photo is.
 *
 * Shared by both renderers so a card cannot be one shape on screen and another in
 * the file that gets shared.
 */
const val CARD_ASPECT_WIDTH = 3

const val CARD_ASPECT_HEIGHT = 4
