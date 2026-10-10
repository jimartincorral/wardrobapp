package com.wardrobapp.domain

import kotlinx.serialization.Serializable

/**
 * What a garment is like, beyond its type and colour: how dressed-up it is,
 * whether it is patterned, how it hangs, how heavy it is, and whether it is the
 * piece an outfit is built around.
 *
 * These exist for the suggestion engine. Until it had them, a rating could only
 * teach it about the exact garments it had just shown -- "these two work" --
 * and nothing it could carry to the next outfit. With a few attributes every
 * garment shares, "you rated a patterned top over patterned trousers badly" is
 * a lesson about patterns, and the engine can apply it to garments nobody has
 * rated yet; see OutfitFeatures and TasteModel.
 *
 * Stored as tags with a prefix (`formality:smart`, `pattern:print`), in the
 * tags column every garment already has, rather than as columns of their own.
 * The sync between the phone and Home Assistant carries a garment as one JSON
 * object and refuses one with a field it does not know, so a new column would
 * cut an older phone off from a newer server; a tag is carried by every build
 * there has ever been, and a build that does not know the prefix keeps it as a
 * tag it does not show. The prefix also keeps a value like `formal` from
 * colliding with the legacy occasion tags StructuredTags throws away.
 *
 * Every attribute is optional, because nobody is going to set five of them on
 * two hundred garments: a garment without one falls back to what its type
 * implies, in [defaultAttributesFor], and the server fills in what it can see
 * in the photo. An explicit value is never overwritten by either.
 *
 * Serializable because the garment form carries one to the server as part of
 * its state; every field defaults, so a form from a build without them reads.
 */
@Serializable
data class GarmentAttributes(
    val formality: Formality? = null,
    val pattern: Pattern? = null,
    val fit: Fit? = null,
    val weight: Weight? = null,
    /** Whether this is the piece an outfit is built around, as opposed to a basic. */
    val statement: Boolean? = null,
) {
    /** Whether anything at all has been set. */
    val isEmpty: Boolean
        get() = formality == null && pattern == null && fit == null && weight == null && statement == null

    /** This, with every unset attribute taken from [fallback]. */
    fun withDefaults(fallback: GarmentAttributes): GarmentAttributes = GarmentAttributes(
        formality = formality ?: fallback.formality,
        pattern = pattern ?: fallback.pattern,
        fit = fit ?: fallback.fit,
        weight = weight ?: fallback.weight,
        statement = statement ?: fallback.statement,
    )

    /** The attributes as the tags that carry them, in a fixed order. */
    fun toTags(): List<String> = listOfNotNull(
        formality?.let { "$FORMALITY_PREFIX${it.tag}" },
        pattern?.let { "$PATTERN_PREFIX${it.tag}" },
        fit?.let { "$FIT_PREFIX${it.tag}" },
        weight?.let { "$WEIGHT_PREFIX${it.tag}" },
        statement?.let { "$STATEMENT_PREFIX${if (it) "yes" else "no"}" },
    )

    companion object {
        val NONE = GarmentAttributes()

        const val FORMALITY_PREFIX = "formality:"
        const val PATTERN_PREFIX = "pattern:"
        const val FIT_PREFIX = "fit:"
        const val WEIGHT_PREFIX = "weight:"
        const val STATEMENT_PREFIX = "statement:"

        private val PREFIXES = listOf(FORMALITY_PREFIX, PATTERN_PREFIX, FIT_PREFIX, WEIGHT_PREFIX, STATEMENT_PREFIX)

        /** Whether [tag] (already lowercased) carries an attribute rather than being something somebody typed. */
        fun isAttributeTag(tag: String): Boolean = PREFIXES.any { tag.startsWith(it) }

        /**
         * The attributes among [tags], the rest ignored. A value this build does
         * not know -- a tag from a newer build -- reads as unset rather than
         * failing, and goes the way a legacy tag goes the next time the garment
         * is edited here: mergeStructuredTags writes the attributes it was given
         * and nothing else under these prefixes.
         */
        fun fromTags(tags: List<String>): GarmentAttributes {
            var result = NONE
            for (raw in tags) {
                val tag = raw.trim().lowercase()
                when {
                    tag.startsWith(FORMALITY_PREFIX) ->
                        Formality.fromTag(tag.removePrefix(FORMALITY_PREFIX))?.let { result = result.copy(formality = it) }
                    tag.startsWith(PATTERN_PREFIX) ->
                        Pattern.fromTag(tag.removePrefix(PATTERN_PREFIX))?.let { result = result.copy(pattern = it) }
                    tag.startsWith(FIT_PREFIX) ->
                        Fit.fromTag(tag.removePrefix(FIT_PREFIX))?.let { result = result.copy(fit = it) }
                    tag.startsWith(WEIGHT_PREFIX) ->
                        Weight.fromTag(tag.removePrefix(WEIGHT_PREFIX))?.let { result = result.copy(weight = it) }
                    tag.startsWith(STATEMENT_PREFIX) -> when (tag.removePrefix(STATEMENT_PREFIX)) {
                        "yes" -> result = result.copy(statement = true)
                        "no" -> result = result.copy(statement = false)
                    }
                }
            }
            return result
        }
    }
}

/** How dressed-up a garment is, from a tracksuit to black tie. */
enum class Formality(val level: Int, val tag: String) {
    LOUNGE(1, "lounge"),
    CASUAL(2, "casual"),
    SMART_CASUAL(3, "smart-casual"),
    SMART(4, "smart"),
    FORMAL(5, "formal");

    companion object {
        fun fromTag(tag: String): Formality? = entries.find { it.tag == tag }
    }
}

enum class Pattern(val tag: String) {
    SOLID("solid"),
    STRIPES("stripes"),
    CHECKS("checks"),
    PRINT("print"),
    TEXTURE("texture");

    companion object {
        fun fromTag(tag: String): Pattern? = entries.find { it.tag == tag }
    }
}

/** How a garment hangs, from close to the body to deliberately big. */
enum class Fit(val tag: String) {
    FITTED("fitted"),
    REGULAR("regular"),
    RELAXED("relaxed"),
    OVERSIZED("oversized");

    companion object {
        fun fromTag(tag: String): Fit? = entries.find { it.tag == tag }
    }
}

/** How much fabric there is, which is what decides whether two layers make sense together. */
enum class Weight(val tag: String) {
    LIGHT("light"),
    MID("mid"),
    HEAVY("heavy");

    companion object {
        fun fromTag(tag: String): Weight? = entries.find { it.tag == tag }
    }
}

/**
 * What a garment's type says about it when nobody has said otherwise.
 *
 * Keyed by the catalogue's stored labels, as SUBCATEGORY_OCCASIONS is, and for
 * the same reason: that is what a row holds. Only formality and weight are
 * implied by a type with any confidence; pattern and fit are the garment's own,
 * and stay unset until somebody or the photo says. A type not listed here gets
 * the category's line, and a category not listed gets casual and mid weight,
 * which is what most of a wardrobe is.
 */
private val SUBCATEGORY_ATTRIBUTES: Map<String, GarmentAttributes> = mapOf(
    // Tops
    "T-Shirt" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Blouse" to GarmentAttributes(formality = Formality.SMART, weight = Weight.LIGHT),
    "Shirt" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Tank Top" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Sweater" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.HEAVY),
    "Hoodie" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.HEAVY, fit = Fit.RELAXED),
    "Crop Top" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT, fit = Fit.FITTED),
    "Polo" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),

    // Bottoms
    "Jeans" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.MID),
    "Pants" to GarmentAttributes(formality = Formality.SMART, weight = Weight.MID),
    "Shorts" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Skirt" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.MID),
    "Leggings" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT, fit = Fit.FITTED),
    "Sweatpants" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.MID, fit = Fit.RELAXED),
    "Chinos" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.MID),

    // Dresses
    "Mini" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Midi" to GarmentAttributes(formality = Formality.SMART, weight = Weight.MID),
    "Maxi" to GarmentAttributes(formality = Formality.SMART, weight = Weight.MID),
    "Cocktail" to GarmentAttributes(formality = Formality.FORMAL, weight = Weight.MID, statement = true),
    "Sundress" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Jumpsuit" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.MID),
    "Romper" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),

    // Mid-layer
    "Blazer" to GarmentAttributes(formality = Formality.SMART, weight = Weight.MID),
    "Overshirt" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.MID, fit = Fit.RELAXED),
    "Vest" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.MID),
    "Poncho" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.HEAVY, fit = Fit.OVERSIZED),
    "Cape" to GarmentAttributes(formality = Formality.SMART, weight = Weight.MID, statement = true),

    // Outerwear
    "Jacket" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.MID),
    "Coat" to GarmentAttributes(formality = Formality.SMART, weight = Weight.HEAVY),
    "Cardigan" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.MID),
    "Windbreaker" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Parka" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.HEAVY, fit = Fit.RELAXED),

    // Shoes
    "Sneakers" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.MID),
    "Boots" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.HEAVY),
    "Sandals" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Heels" to GarmentAttributes(formality = Formality.FORMAL, weight = Weight.LIGHT),
    "Flats" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Loafers" to GarmentAttributes(formality = Formality.SMART, weight = Weight.MID),
    "Athletic" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.MID),

    // Accessories: light, and mostly as dressed as the rest of the outfit.
    "Hat" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Scarf" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.MID),
    "Foulard" to GarmentAttributes(formality = Formality.SMART, weight = Weight.LIGHT),
    "Belt" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Bag" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Wallet" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Gloves" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Earrings" to GarmentAttributes(formality = Formality.SMART, weight = Weight.LIGHT),
    "Necklaces" to GarmentAttributes(formality = Formality.SMART, weight = Weight.LIGHT),
    "Bracelets" to GarmentAttributes(formality = Formality.SMART, weight = Weight.LIGHT),
    "Rings" to GarmentAttributes(formality = Formality.SMART, weight = Weight.LIGHT),
    "Jewelry" to GarmentAttributes(formality = Formality.FORMAL, weight = Weight.LIGHT, statement = true),
    "Watch" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Eyewear" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "Sunglasses" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.LIGHT),
    "Tie" to GarmentAttributes(formality = Formality.FORMAL, weight = Weight.LIGHT),

    // Activewear
    "Sports Bra" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT, fit = Fit.FITTED),
    "Workout Top" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT),
    "Workout Shorts" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT),
    "Yoga Pants" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT, fit = Fit.FITTED),
    "Track Suit" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.MID, fit = Fit.RELAXED),

    // Loungewear
    "Pajama Set" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT, fit = Fit.RELAXED),
    "Pajama Top" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT, fit = Fit.RELAXED),
    "Pajama Bottoms" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT, fit = Fit.RELAXED),
    "Nightgown" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT),
    "Robe" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.HEAVY, fit = Fit.RELAXED),
    "Lounge Set" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.MID, fit = Fit.RELAXED),
)

private val CATEGORY_ATTRIBUTES: Map<String, GarmentAttributes> = mapOf(
    "activewear" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT),
    "loungewear" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT),
    "underwear" to GarmentAttributes(formality = Formality.LOUNGE, weight = Weight.LIGHT),
    "shoes" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.MID),
    "accessories" to GarmentAttributes(formality = Formality.SMART_CASUAL, weight = Weight.LIGHT),
    "outerwear" to GarmentAttributes(formality = Formality.CASUAL, weight = Weight.HEAVY),
)

private val DEFAULT_ATTRIBUTES = GarmentAttributes(
    formality = Formality.CASUAL,
    pattern = Pattern.SOLID,
    fit = Fit.REGULAR,
    weight = Weight.MID,
    statement = false,
)

/**
 * The attributes a garment of this type has until somebody says otherwise.
 *
 * Every field is set in the answer: the engine wants a number for every
 * garment, and "unknown" would have to be a number anyway. The first listed
 * type that is known decides; after the type and the category, the defaults
 * fill whatever is still open.
 */
fun defaultAttributesFor(category: String, subcategories: List<String>): GarmentAttributes {
    val byType = subcategories.firstNotNullOfOrNull { SUBCATEGORY_ATTRIBUTES[it] } ?: GarmentAttributes.NONE
    val byCategory = CATEGORY_ATTRIBUTES[category] ?: GarmentAttributes.NONE
    return byType.withDefaults(byCategory).withDefaults(DEFAULT_ATTRIBUTES)
}

/** What was set on this garment, in its tags, and nothing guessed. */
val Garment.explicitAttributes: GarmentAttributes
    get() = GarmentAttributes.fromTags(tags)

/** What the engine scores: what was set, and the type's defaults for the rest. Every field is non-null. */
val Garment.attributes: GarmentAttributes
    get() = explicitAttributes.withDefaults(defaultAttributesFor(category, effectiveSubcategories))
