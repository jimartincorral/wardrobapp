package com.wardrobapp.domain

/**
 * A garment's place in the catalogue as a reading found it: a category id,
 * and the stored label of one of its types, or no type when only the category
 * could be told. Not WardrobeCoverage's GarmentType, whose type is never
 * missing.
 */
data class ReadType(val category: String, val subcategory: String?)

/**
 * What something read off a garment -- its photo, or the shop page it was
 * imported from -- suggests about it, before anybody has said otherwise.
 *
 * Every field is optional and each is applied on its own, because the readers
 * are good at different things: the style model on the Home Assistant server
 * can place a photo near "a photo of chinos" but cannot read a label, and
 * Gemini Nano on a phone can read a label but may not be there at all. The form
 * takes whatever came back and fills only what nobody has touched, the way it
 * takes the colours it detects; see GarmentFormState.withReading.
 */
data class GarmentReading(
    val type: ReadType? = null,
    val attributes: GarmentAttributes = GarmentAttributes.NONE,
    /** One of [MATERIALS], kept as a tag: no garment has a material field, and a tag is what a search finds. */
    val material: String? = null,
    val brand: String? = null,
) {
    val isEmpty: Boolean
        get() = type == null && attributes.isEmpty && material == null && brand == null

    /** This, with every field still open taken from [other]: how a page's reading and a photo's are combined. */
    fun orElse(other: GarmentReading?): GarmentReading = if (other == null) this else GarmentReading(
        type = type ?: other.type,
        attributes = attributes.withDefaults(other.attributes),
        material = material ?: other.material,
        brand = brand ?: other.brand,
    )

    companion object {
        val NONE = GarmentReading()
    }
}

/**
 * The materials a reading may name, as the tag each becomes.
 *
 * A closed list rather than whatever a model says, for the same reason the
 * types are: an answer is checked against it, so "100% organic cotton jersey"
 * becomes `cotton` and "probably a blend" becomes nothing, instead of a tag
 * nobody would ever type. Kept to fabrics a person picks clothes by, and short
 * enough to list in a prompt.
 */
val MATERIALS: List<String> = listOf(
    "cotton",
    "linen",
    "wool",
    "cashmere",
    "denim",
    "leather",
    "suede",
    "silk",
    "satin",
    "knit",
    "fleece",
    "polyester",
    "corduroy",
    "velvet",
)

/**
 * A type as it is put to a reader that has never seen this catalogue.
 *
 * The four dress lengths and styles are stored as "Mini", "Midi", "Maxi" and
 * "Cocktail", which the form can show under a "Dresses" heading but which mean
 * nothing on their own in a list of seventy types: asked to pick one, a model
 * calls a short dress a "Skirt" before it calls it a "Mini". So they are put as
 * "Mini Dress" and so on, and [garmentTypeNamed] takes either spelling back.
 */
fun garmentTypePromptName(category: String, subcategory: String): String =
    if (category == "dresses" && !subcategory.contains("dress", ignoreCase = true) && subcategory in DRESS_LENGTHS) {
        "$subcategory Dress"
    } else {
        subcategory
    }

private val DRESS_LENGTHS = setOf("Mini", "Midi", "Maxi", "Cocktail")

/** Every type in the catalogue, by the name a reader is asked to use. */
val GARMENT_TYPE_PROMPT_NAMES: List<String> =
    GARMENT_CATEGORIES.flatMap { category -> category.subcategories.map { garmentTypePromptName(category.id, it) } }

/**
 * Letters and digits only, lowercased: "T-Shirt", "t shirt" and "tshirt." are
 * all `tshirt`, and "Tank Top" is `tanktop`, so the punctuation and spacing a
 * model happens to use cannot make a right answer miss.
 */
private fun normalised(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

private val TYPES_BY_NAME: Map<String, ReadType> = buildMap {
    // Categories first, so that a type spelled the same as a category -- there
    // is none today, but nothing stops one -- wins by being written over it.
    for (category in GARMENT_CATEGORIES) {
        val whole = ReadType(category.id, null)
        put(normalised(category.id), whole)
        put(normalised(category.label), whole)
        // "Loungewear/Pajamas" is one label but either half is an answer.
        category.label.split('/').forEach { put(normalised(it), whole) }
    }
    for (category in GARMENT_CATEGORIES) {
        for (label in category.subcategories) {
            val type = ReadType(category.id, label)
            put(normalised(label), type)
            put(normalised(garmentTypePromptName(category.id, label)), type)
        }
    }
}

/**
 * The catalogue entry [answer] names, or null when it names none.
 *
 * Forgiving about spelling -- case, spaces, hyphens, a full stop, a plural or a
 * singular where the label has the other ("Sneaker", "Jean") -- and about
 * nothing else: a type that is not in the catalogue is not guessed at, because
 * a garment filed under the wrong type is worse than one left for its owner to
 * file, and the form starts with a type picked anyway.
 */
fun garmentTypeNamed(answer: String): ReadType? {
    val name = normalised(answer)
    if (name.isEmpty()) return null
    return TYPES_BY_NAME[name]
        ?: TYPES_BY_NAME[name + "s"]
        ?: TYPES_BY_NAME[name + "es"]
        ?: name.takeIf { it.endsWith("s") }?.let { TYPES_BY_NAME[it.dropLast(1)] }
        ?: name.takeIf { it.endsWith("es") }?.let { TYPES_BY_NAME[it.dropLast(2)] }
}

/** The material [answer] names, by the tag in [MATERIALS], or null. */
fun materialNamed(answer: String): String? {
    val name = normalised(answer)
    if (name.isEmpty()) return null
    return MATERIALS.find { normalised(it) == name }
        // "100% cotton" or "wool blend": a single listed material inside the
        // answer is that material; two of them is a blend nobody can tag.
        ?: MATERIALS.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(answer.lowercase()) }.singleOrNull()
}

/**
 * Words a model uses to mean it found nothing, which would otherwise be read
 * as a brand called "None".
 */
private val NO_ANSWER = setOf("none", "unknown", "na", "notvisible", "nobrand", "nolabel", "notsure", "unbranded", "null")

/**
 * A brand as a reader gave it, or null when it is no brand at all.
 *
 * Kept short and plain: a sentence ("The label appears to say…") is a model
 * explaining itself rather than a brand, and a garment's brand field would be
 * the worse for holding it.
 */
fun brandNamed(answer: String): String? {
    val brand = answer.trim().trim('"', '\'', '*', '`', '.').trim()
    if (brand.length !in 2..40) return null
    if (normalised(brand) in NO_ANSWER) return null
    if (brand.count { it == ' ' } > 3) return null
    return brand
}

/**
 * What a reader's answer says, line by line.
 *
 * The answer is asked for as `key: value` lines -- `type: Chinos`,
 * `pattern: solid` -- and read leniently: a bullet, bold markers or a key in
 * capitals are all things a model adds unasked, and none of them changes what
 * it meant. Each value is then held to the app's own vocabulary, and anything
 * outside it is dropped rather than stored; an unparseable answer is simply an
 * empty reading. The question is asked in English whatever the app's language,
 * because the stored values are English and a small model is surest in it.
 */
fun parseGarmentReading(answer: String): GarmentReading {
    var reading = GarmentReading.NONE
    for (raw in answer.lines()) {
        val line = raw.trim().trimStart('-', '*', '•', ' ').replace("*", "").trim()
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = normalised(line.substring(0, colon))
        val value = line.substring(colon + 1).trim()
        if (value.isEmpty() || normalised(value) in NO_ANSWER) continue
        reading = when (key) {
            "type" -> reading.copy(type = reading.type ?: garmentTypeNamed(value))
            "pattern" -> reading.copy(attributes = reading.attributes.copy(pattern = Pattern.entries.named(value) { it.tag }))
            "formality" -> reading.copy(attributes = reading.attributes.copy(formality = Formality.entries.named(value) { it.tag }))
            "fit" -> reading.copy(attributes = reading.attributes.copy(fit = Fit.entries.named(value) { it.tag }))
            "weight" -> reading.copy(attributes = reading.attributes.copy(weight = Weight.entries.named(value) { it.tag }))
            "material" -> reading.copy(material = materialNamed(value))
            "brand" -> reading.copy(brand = brandNamed(value))
            else -> reading
        }
    }
    return reading
}

private fun <T> List<T>.named(value: String, tag: (T) -> String): T? = find { normalised(tag(it)) == normalised(value) }

/**
 * The question put to Gemini Nano about a garment's photo, asking for exactly
 * the lines [parseGarmentReading] reads, each from the list it will check it
 * against.
 *
 * Here rather than beside the call, so the list it offers and the list the
 * answer is held to are visibly the same lists, and a test can say so. The
 * brand is asked for only when it is printed on the garment: a model asked
 * "what brand is this" of a plain grey T-shirt will name one.
 */
val GARMENT_READING_PROMPT: String = buildString {
    appendLine("Describe the one item of clothing, footwear or accessory in this photo.")
    appendLine("Reply with exactly these seven lines and nothing else:")
    appendLine("type: one of ${GARMENT_TYPE_PROMPT_NAMES.joinToString(", ")}")
    appendLine("pattern: one of ${Pattern.entries.joinToString(", ") { it.tag }}")
    appendLine("formality: one of ${Formality.entries.joinToString(", ") { it.tag }}")
    appendLine("fit: one of ${Fit.entries.joinToString(", ") { it.tag }}")
    appendLine("weight: one of ${Weight.entries.joinToString(", ") { it.tag }}")
    appendLine("material: one of ${MATERIALS.joinToString(", ")}, or none if you cannot tell")
    append("brand: the brand name only if it is clearly printed on the item, otherwise none")
}
