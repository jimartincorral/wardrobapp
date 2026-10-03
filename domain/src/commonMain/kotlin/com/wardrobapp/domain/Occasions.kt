package com.wardrobapp.domain

/**
 * Occasions a garment type is suitable for.
 *
 * Occasion used to be a set of chips the user ticked per garment, which meant it
 * only worked for people willing to tag their whole wardrobe -- so in practice it
 * stayed empty and the filter did nothing. Deriving it from the garment's type
 * instead makes the filter work for everyone with no data entry, at the cost of
 * not being able to express "this specific shirt is my work shirt". That trade is
 * worth it while nothing is being tagged at all.
 *
 * A garment can suit several occasions; order here does not matter, results come
 * back in Occasion declaration order.
 */
private val SUBCATEGORY_OCCASIONS: Map<String, List<Occasion>> = mapOf(
    // Tops
    "T-Shirt" to listOf(Occasion.CASUAL),
    "Blouse" to listOf(Occasion.WORK, Occasion.FORMAL),
    "Shirt" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Tank Top" to listOf(Occasion.CASUAL),
    "Sweater" to listOf(Occasion.CASUAL, Occasion.WORK),
    "Hoodie" to listOf(Occasion.CASUAL, Occasion.LOUNGE),
    "Crop Top" to listOf(Occasion.CASUAL),
    "Polo" to listOf(Occasion.CASUAL, Occasion.WORK),

    // Bottoms
    "Jeans" to listOf(Occasion.CASUAL),
    "Pants" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Shorts" to listOf(Occasion.CASUAL),
    "Skirt" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Leggings" to listOf(Occasion.SPORT, Occasion.CASUAL),
    "Sweatpants" to listOf(Occasion.LOUNGE, Occasion.CASUAL),
    "Chinos" to listOf(Occasion.WORK, Occasion.CASUAL),

    // Dresses
    "Mini" to listOf(Occasion.CASUAL),
    "Midi" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Maxi" to listOf(Occasion.FORMAL, Occasion.CASUAL),
    "Cocktail" to listOf(Occasion.FORMAL),
    "Sundress" to listOf(Occasion.CASUAL),
    "Jumpsuit" to listOf(Occasion.CASUAL, Occasion.WORK),
    "Romper" to listOf(Occasion.CASUAL),

    // Mid-layer
    "Blazer" to listOf(Occasion.WORK, Occasion.FORMAL),
    "Overshirt" to listOf(Occasion.CASUAL),
    "Vest" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Poncho" to listOf(Occasion.CASUAL),
    "Cape" to listOf(Occasion.FORMAL),

    // Outerwear
    "Jacket" to listOf(Occasion.CASUAL),
    "Coat" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Cardigan" to listOf(Occasion.CASUAL, Occasion.WORK),
    "Windbreaker" to listOf(Occasion.SPORT, Occasion.CASUAL),
    "Parka" to listOf(Occasion.CASUAL),

    // Shoes
    "Sneakers" to listOf(Occasion.CASUAL, Occasion.SPORT),
    "Boots" to listOf(Occasion.CASUAL),
    "Sandals" to listOf(Occasion.CASUAL),
    "Heels" to listOf(Occasion.FORMAL, Occasion.WORK),
    "Flats" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Loafers" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Athletic" to listOf(Occasion.SPORT),

    // Accessories
    "Hat" to listOf(Occasion.CASUAL),
    "Scarf" to listOf(Occasion.CASUAL),
    "Foulard" to listOf(Occasion.WORK),
    "Belt" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Bag" to listOf(Occasion.CASUAL, Occasion.WORK),
    "Wallet" to listOf(Occasion.CASUAL),
    "Gloves" to listOf(Occasion.CASUAL),
    // Formal like the rest of the jewellery, and casual too: small pieces are
    // everyday wear in a way a statement piece is not.
    "Earrings" to listOf(Occasion.FORMAL, Occasion.CASUAL),
    "Necklaces" to listOf(Occasion.FORMAL, Occasion.CASUAL),
    "Bracelets" to listOf(Occasion.FORMAL, Occasion.CASUAL),
    "Rings" to listOf(Occasion.FORMAL, Occasion.CASUAL),
    "Jewelry" to listOf(Occasion.FORMAL),
    "Watch" to listOf(Occasion.WORK, Occasion.CASUAL),
    // Unlike Sunglasses below: these are worn for eyesight rather than weather,
    // so a work day is exactly where they belong and a beach is not their type.
    "Eyewear" to listOf(Occasion.WORK, Occasion.CASUAL),
    "Sunglasses" to listOf(Occasion.CASUAL),
    "Tie" to listOf(Occasion.WORK, Occasion.FORMAL),

    // Activewear
    "Sports Bra" to listOf(Occasion.SPORT),
    "Workout Top" to listOf(Occasion.SPORT),
    "Workout Shorts" to listOf(Occasion.SPORT),
    "Yoga Pants" to listOf(Occasion.SPORT),
    "Track Suit" to listOf(Occasion.SPORT, Occasion.LOUNGE),

    // Loungewear
    "Pajama Set" to listOf(Occasion.LOUNGE),
    "Pajama Top" to listOf(Occasion.LOUNGE),
    "Pajama Bottoms" to listOf(Occasion.LOUNGE),
    "Nightgown" to listOf(Occasion.LOUNGE),
    "Robe" to listOf(Occasion.LOUNGE),
    "Lounge Set" to listOf(Occasion.LOUNGE),
)

/** Fallback when a garment has no subcategory, or an unrecognised one. */
private val CATEGORY_OCCASIONS: Map<String, List<Occasion>> = mapOf(
    "activewear" to listOf(Occasion.SPORT),
    "loungewear" to listOf(Occasion.LOUNGE),
    // Underwear is not an outfit-occasion concept; it deliberately maps to none.
    "underwear" to emptyList(),
)

private val DEFAULT_OCCASIONS = listOf(Occasion.CASUAL)

fun occasionsFor(category: String, subcategories: List<String>?): List<Occasion> {
    val matched = mutableSetOf<Occasion>()

    for (subcategory in subcategories ?: emptyList()) {
        matched.addAll(SUBCATEGORY_OCCASIONS[subcategory] ?: emptyList())
    }

    if (matched.isEmpty()) {
        matched.addAll(CATEGORY_OCCASIONS[category] ?: DEFAULT_OCCASIONS)
    }

    return Occasion.entries.filter { it in matched }
}

fun Garment.occasions(): List<Occasion> = occasionsFor(category, effectiveSubcategories)

/**
 * Seasons a garment's type implies.
 *
 * Only where the type genuinely says something -- sandals are summer, a parka is
 * winter -- and nothing otherwise, so a caller can tell "no opinion" from
 * "all-season". Used to fill the form in for someone who has not picked seasons
 * themselves; an explicit choice is never overwritten.
 */
private val SUBCATEGORY_SEASONS: Map<String, List<Season>> = mapOf(
    "Shorts" to listOf(Season.SUMMER),
    "Tank Top" to listOf(Season.SUMMER),
    "Sandals" to listOf(Season.SUMMER),
    "Sundress" to listOf(Season.SUMMER),
    "Coat" to listOf(Season.WINTER),
    "Parka" to listOf(Season.WINTER),
    "Thermal" to listOf(Season.WINTER),
    "Sweater" to listOf(Season.FALL, Season.WINTER),
    "Hoodie" to listOf(Season.FALL, Season.WINTER),
    "Boots" to listOf(Season.FALL, Season.WINTER),
    "Windbreaker" to listOf(Season.SPRING, Season.FALL),
    "Cardigan" to listOf(Season.SPRING, Season.FALL),
    "Robe" to listOf(Season.FALL, Season.WINTER),
)

/** Seasons implied by the chosen types, in the app's own season order. */
fun seasonsForSubcategories(subcategories: List<String>): List<Season> {
    val implied = subcategories.flatMap { SUBCATEGORY_SEASONS[it] ?: emptyList() }.toSet()

    return Season.entries.filter { it in implied }
}
