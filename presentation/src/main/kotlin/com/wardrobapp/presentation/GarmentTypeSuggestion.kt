package com.wardrobapp.presentation

/**
 * The garment type a photo's labels suggest.
 *
 * An image labeller says what it sees in words of its own choosing -- "Footwear",
 * "Jeans", "Person" -- and this app has a vocabulary of ten categories and their
 * types, which a garment is stored as and which the whole domain keys on. This is
 * the layer between: labels in, a category and possibly a type out, or nothing.
 *
 * Here rather than in :app for the same reason [dominantGarmentColor] is: it is a
 * table and three rules, the kind that is quietly wrong for one garment in ten and
 * looks fine, so it is worth being able to ask it questions without a device. And
 * here rather than in :domain because it is not part of the vocabulary -- it is a
 * guess at it, and the vocabulary must not grow a dependency on who is guessing.
 *
 * The labeller is deliberately not named in this file. It is ML Kit's general
 * image labelling model today, whose labels are the ones the table is written for;
 * anything else that produces (text, confidence) pairs -- a garment-specific
 * classifier, a zero-shot image/text model scored against these words -- can
 * replace it without this file changing.
 */

/** What a labeller saw, and how sure it is. [confidence] is 0..1. */
data class ImageLabel(val text: String, val confidence: Float)

/**
 * A category, and the type within it when the labels were specific enough.
 *
 * A bare category is a real answer and not a failure: "Footwear" narrows a garment
 * to seven types from seventy, and the form's chips do the rest. Guessing Sneakers
 * from it would be inventing detail the model did not report -- the mistake the
 * colour detection used to make with a mean.
 */
data class GarmentTypeSuggestion(val category: String, val subcategory: String?)

/**
 * How sure the labeller has to be before a label is worth reading.
 *
 * ML Kit's own quickstart draws the line here, and the model returns a long tail of
 * low-confidence guesses -- a garment photographed on a bed reliably scores "Room"
 * and "Furniture" a little. A suggestion nobody asked twice for should be quiet
 * rather than eager: below this, the label is not looked up at all.
 */
private const val LABEL_CONFIDENCE_FLOOR = 0.5f

/**
 * What each label means in this app's vocabulary.
 *
 * Keyed on the label's text with case and punctuation removed ([normalizedLabel]),
 * so "T-Shirt", "T-shirt" and "t shirt" are one entry, and a model that words
 * something slightly differently does not need a new row. English, because that is
 * what an image labeller returns whatever language the phone is in -- the answer is
 * a vocabulary key, and :app translates it for display like every other one.
 *
 * Synonyms are generous on purpose: a wrong-looking key costs nothing if no model
 * ever emits it, and a missing one costs a suggestion. What is deliberately absent
 * is the other kind of guess -- "Clothing", "Fashion", "Textile", "Sleeve",
 * "Person" and the rest of the labels that a photograph of any garment scores
 * highly. They map to nothing, so they are skipped and a more specific label lower
 * down the list is used instead.
 *
 * Swimwear is absent for a different reason: the vocabulary has no entry for it, so
 * "Bikini" and "Swimsuit" have nothing honest to map to. Better no suggestion than
 * a garment filed under Underwear because the table needed somewhere to put it.
 *
 * "Vest" alone is absent too, and that one is a genuine ambiguity rather than a
 * gap: in American English it is a waistcoat and in British English it is a tank
 * top, which are two different categories here. The unambiguous words for both are
 * in the table, so a labeller that says either of those is understood and one that
 * says only "Vest" is not answered wrongly.
 */
internal val LABEL_VOCABULARY: Map<String, GarmentTypeSuggestion> = buildMap {
    fun put(subcategory: String?, category: String, vararg labels: String) {
        for (label in labels) put(normalizedLabel(label), GarmentTypeSuggestion(category, subcategory))
    }

    put("T-Shirt", "tops", "t-shirt", "tshirt", "tee")
    put("Shirt", "tops", "shirt", "dress shirt", "button-down")
    put("Blouse", "tops", "blouse")
    put("Tank Top", "tops", "tank top", "camisole", "vest top")
    put("Sweater", "tops", "sweater", "jumper", "pullover", "knitwear", "turtleneck")
    put("Hoodie", "tops", "hoodie", "hood", "sweatshirt")
    put("Crop Top", "tops", "crop top")
    put("Polo", "tops", "polo", "polo shirt")

    put("Jeans", "bottoms", "jeans", "denim", "jean")
    put("Pants", "bottoms", "trousers", "trouser", "pants", "slacks")
    put("Shorts", "bottoms", "shorts")
    put("Skirt", "bottoms", "skirt", "miniskirt")
    put("Leggings", "bottoms", "leggings", "legging")
    put("Sweatpants", "bottoms", "sweatpants", "joggers", "track pants")
    put("Chinos", "bottoms", "chinos", "chino")

    put(null, "dresses", "dress", "gown", "evening gown", "wedding dress")
    put("Sundress", "dresses", "sundress")
    put("Cocktail", "dresses", "cocktail dress")
    put("Jumpsuit", "dresses", "jumpsuit", "overall", "overalls", "dungarees")
    put("Romper", "dresses", "romper", "playsuit")

    put("Blazer", "midlayer", "blazer", "suit", "tuxedo")
    put("Overshirt", "midlayer", "overshirt", "shacket")
    put("Vest", "midlayer", "waistcoat", "gilet")
    put("Poncho", "midlayer", "poncho")
    put("Cape", "midlayer", "cape", "cloak")

    put("Jacket", "outerwear", "jacket", "leather jacket", "denim jacket", "bomber jacket")
    put("Coat", "outerwear", "coat", "overcoat", "trench coat", "fur coat")
    put("Cardigan", "outerwear", "cardigan")
    put("Windbreaker", "outerwear", "windbreaker", "raincoat", "rain jacket", "anorak")
    put("Parka", "outerwear", "parka")
    put(null, "outerwear", "outerwear")

    put(null, "shoes", "shoe", "shoes", "footwear")
    put("Sneakers", "shoes", "sneakers", "sneaker", "trainers", "running shoe", "running shoes")
    put("Boots", "shoes", "boot", "boots")
    put("Sandals", "shoes", "sandal", "sandals", "flip-flops", "slipper", "slippers", "clog")
    put("Heels", "shoes", "high heels", "heel", "heels", "stiletto", "pump")
    put("Flats", "shoes", "flats", "ballet flat", "ballet shoe")
    put("Loafers", "shoes", "loafer", "loafers", "moccasin", "oxford shoe")

    put("Hat", "accessories", "hat", "cap", "beanie", "baseball cap", "sun hat", "fedora", "headgear")
    put("Scarf", "accessories", "scarf", "shawl")
    put("Foulard", "accessories", "foulard", "bandana")
    put("Belt", "accessories", "belt")
    put("Bag", "accessories", "bag", "handbag", "backpack", "purse", "tote bag", "luggage", "satchel")
    put("Wallet", "accessories", "wallet")
    put("Gloves", "accessories", "glove", "gloves", "mitten", "mittens")
    put(
        "Jewelry", "accessories",
        "jewelry", "jewellery", "necklace", "bracelet", "earring", "earrings", "ring", "brooch",
    )
    put("Watch", "accessories", "watch", "wristwatch")
    put("Sunglasses", "accessories", "sunglasses", "glasses", "eyewear", "spectacles", "goggles")
    put("Tie", "accessories", "tie", "necktie", "bow tie")

    put("Sports Bra", "activewear", "sports bra")
    put("Workout Top", "activewear", "workout top")
    put("Workout Shorts", "activewear", "workout shorts")
    put("Yoga Pants", "activewear", "yoga pants")
    put("Track Suit", "activewear", "tracksuit", "track suit")
    put(null, "activewear", "sportswear", "activewear")

    put("Bra", "underwear", "bra", "brassiere")
    put("Briefs", "underwear", "briefs", "underpants", "panties")
    put("Boxers", "underwear", "boxers", "boxer shorts")
    put("Bodysuit", "underwear", "bodysuit", "leotard")
    put("Socks", "underwear", "sock", "socks")
    put("Tights", "underwear", "tights", "stockings", "pantyhose")
    put("Thermal", "underwear", "thermal", "long johns")
    put(null, "underwear", "underwear", "undergarment", "lingerie")

    put("Pajama Set", "loungewear", "pajamas", "pyjamas", "pajama", "nightwear", "sleepwear")
    put("Nightgown", "loungewear", "nightgown", "nightdress")
    put("Robe", "loungewear", "robe", "bathrobe", "dressing gown")
    put("Lounge Set", "loungewear", "loungewear")
}

/**
 * The category and type a photo's labels come to, or null.
 *
 * Three rules. Labels the model is not sure of are dropped; of the rest, the most
 * confident one this app has a meaning for wins; and if that meaning is a bare
 * category, a less confident label naming a type *in that category* fills it in --
 * which is the common shape of a real answer, because a labeller that says
 * "Footwear" at 0.9 often says "Sneakers" at 0.6 in the same breath.
 *
 * A less confident label never moves the answer to another category, and nothing
 * outside the vocabulary moves it at all. Null when no label meant anything here:
 * the caller says so rather than filling the form in with a guess.
 *
 * Order is kept where confidences tie, so the same photo always gives the same
 * answer -- [labels] arrive most-confident-first from ML Kit, and this does not
 * depend on that.
 */
fun suggestGarmentType(labels: List<ImageLabel>): GarmentTypeSuggestion? {
    val meanings = labels
        .filter { it.confidence >= LABEL_CONFIDENCE_FLOOR }
        .sortedByDescending { it.confidence }
        .mapNotNull { LABEL_VOCABULARY[normalizedLabel(it.text)] }

    val best = meanings.firstOrNull() ?: return null
    if (best.subcategory != null) return best

    return meanings.firstOrNull { it.category == best.category && it.subcategory != null } ?: best
}

/**
 * A label as the table is keyed: lower case, letters and digits only.
 *
 * Punctuation and spacing are where labellers differ and where nothing is meant --
 * "T-Shirt", "T shirt" and "tshirt" are one word. Not stemmed: dropping a trailing
 * "s" would turn "dress" into "dres" and is a rule with more exceptions than uses,
 * so plurals are listed in the table instead.
 */
private fun normalizedLabel(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }
