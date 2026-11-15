package com.wardrobapp.domain

/**
 * What a garment can be made of, as the tag each is stored as.
 *
 * A garment has no material column, and does not get one, for the reason the
 * style attributes do not (see GarmentAttributes): sync carries a garment as
 * one JSON object and refuses a field it does not know, so a new column would
 * cut an older phone off from a newer server. A tag is carried by every build
 * there has been. And materials were already tags before anybody could choose
 * one: reading a photo or a shop's page wrote `cotton` into the tags (see
 * GarmentReading), where a search finds it. So the tag stays exactly what it
 * was -- the bare English word -- and a garment saved by a reading before the
 * form offered materials shows its material in the new field with nothing
 * converted. StructuredTags tells these tags from the ones somebody typed.
 *
 * A closed list, for the same reason the types are: a reader's answer is
 * checked against it, so "100% organic cotton jersey" becomes `cotton` and
 * "probably a blend" becomes nothing, instead of a tag nobody would ever pick
 * again. Kept to what people choose clothes by and find on a care label, in
 * one order that the form, the filter and the readers' prompt all follow.
 * A garment can be several of them -- most are blends -- and no percentages:
 * a list of what it is made of is what a person filling in a form will give.
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
    // The four after the first fourteen were added when the form began to
    // offer materials: each is on a large share of care labels, and a list a
    // person picks from has to have what their labels say.
    "viscose",
    "nylon",
    "elastane",
    "acrylic",
)

/**
 * Other words for a material, as care labels and shop pages write them, by
 * the tag they mean. For reading only: what is stored and offered is always
 * the tag. Each is a name the same fibre goes by in English -- "Lycra" is a
 * brand of elastane, as "rayon" is viscose and "polyamide" nylon -- not a
 * guess at what something is like.
 */
internal val MATERIAL_SYNONYMS: Map<String, String> = mapOf(
    "spandex" to "elastane",
    "lycra" to "elastane",
    "rayon" to "viscose",
    "polyamide" to "nylon",
)

/** The material [tag] stores, by its tag, or null for a tag that is not one. */
fun materialOf(tag: String): String? = tag.trim().lowercase().takeIf { it in MATERIALS }

/**
 * Each material [text] names, by its tag, with where in the text it is first
 * named; a synonym counts as the material it means. Whole words only, so
 * "linen" is not found in "linens closet" -- nor "nylon" in a brand that
 * contains it.
 */
internal fun materialsNamedIn(text: String): List<Pair<String, Int>> {
    val lower = text.lowercase()
    val words = MATERIALS.map { it to it } + MATERIAL_SYNONYMS.toList()
    return words
        .mapNotNull { (word, material) -> Regex("\\b${Regex.escape(word)}\\b").find(lower)?.let { material to it.range.first } }
        .groupBy({ it.first }, { it.second })
        .map { (material, positions) -> material to positions.min() }
}
