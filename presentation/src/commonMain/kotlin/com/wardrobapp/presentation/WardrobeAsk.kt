package com.wardrobapp.presentation

import com.wardrobapp.domain.GARMENT_TYPE_PROMPT_NAMES
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.garmentTypeNamed
import com.wardrobapp.domain.readPageText

/*
 * Searching the wardrobe in plain words: "something warm for a wedding",
 * "my navy chinos", "zapatillas blancas".
 *
 * The search box matches what somebody typed against a garment's type, brand,
 * size and tags, word for word (GarmentSearch.kt), so a sentence finds nothing
 * -- no garment is tagged "something warm". The wardrobe already has a way to
 * say every part of that sentence, though: its filters. So a sentence is not
 * searched for; it is turned into filters, which then show on the panel as
 * chips the reader can see and undo -- what was understood is never a hidden
 * guess about what was meant.
 *
 * Gemini Nano does the turning on a phone that has it, since it can read
 * "warm" as winter and "a wedding" as formal; plain matching of the words
 * against the app's own vocabulary does it everywhere else, and fills in what
 * Nano left open. Either way every value is one the filters already offer, so
 * the worst a misreading can do is narrow by the wrong chip.
 */

/** A request for clothes, as the filters can say it. Every part optional. */
data class WardrobeAsk(
    val category: String? = null,
    val subcategory: String? = null,
    /** A palette hex, as a garment's colours are stored. */
    val color: String? = null,
    val season: Season? = null,
    val occasion: Occasion? = null,
    /** As the wardrobe has it written, never as the request spelled it. */
    val brand: String? = null,
) {
    val isEmpty: Boolean
        get() = category == null && color == null && season == null && occasion == null && brand == null

    /** This, with every part still open taken from [other]. A type comes with its category or not at all. */
    fun orElse(other: WardrobeAsk): WardrobeAsk = WardrobeAsk(
        category = category ?: other.category,
        subcategory = if (category != null) subcategory else other.subcategory,
        color = color ?: other.color,
        season = season ?: other.season,
        occasion = occasion ?: other.occasion,
        brand = brand ?: other.brand,
    )

    /**
     * Only a brand the wardrobe has, written as the wardrobe writes it: the
     * brand filter is an exact match, so "zara" must become "Zara", and a brand
     * nobody owns -- a model's guess, or a shop named in passing -- would show
     * an empty wardrobe rather than the rest of what was asked for.
     */
    fun withBrandFrom(known: List<String>): WardrobeAsk =
        copy(brand = brand?.let { asked -> known.firstOrNull { it.equals(asked.trim(), ignoreCase = true) } })
}

/**
 * The wardrobe filtered as [ask] says, from scratch: the search box emptied,
 * since what it held is now the chips, and every other filter dropped, since
 * a new sentence is a new question. The sort and whether retired garments are
 * shown stay, being how the reader likes the list rather than what is in it.
 */
fun WardrobeQuery.answering(ask: WardrobeAsk): WardrobeQuery = WardrobeQuery(
    sort = sort,
    includeRetired = includeRetired,
    category = ask.category,
    subcategory = ask.subcategory?.takeIf { ask.category != null },
    color = ask.color,
    season = ask.season,
    occasion = ask.occasion,
    brand = ask.brand.orEmpty(),
)

/** What Gemini Nano is asked, in English whatever the app's language, with each answer from a list it is checked against. */
fun wardrobeAskPrompt(request: String): String = buildString {
    appendLine("Someone is searching their own wardrobe with this request:")
    appendLine("\"${request.trim().take(MAX_REQUEST_LENGTH)}\"")
    appendLine("Turn it into search filters. Write one line for each filter the request asks for, and nothing for the rest:")
    appendLine("type: one of ${GARMENT_TYPE_PROMPT_NAMES.joinToString(", ")}")
    appendLine("category: one of ${CATEGORY_WORDS.values.distinct().joinToString(", ")}, if no single type fits")
    appendLine("colour: one of ${GARMENT_COLORS.filter { it.first != "multi" }.joinToString(", ") { colorWords(it.first) }}")
    appendLine("season: one of ${Season.entries.filter { it != Season.ALL_SEASON }.joinToString(", ") { it.tag }}")
    appendLine("occasion: one of ${Occasion.entries.joinToString(", ") { it.id }}")
    appendLine("brand: the brand named, if one is")
    append("Write nothing else.")
}

/** Longer than anybody types into a search box; what is past it is not a search. */
private const val MAX_REQUEST_LENGTH = 200

private fun colorWords(key: String): String =
    key.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }.lowercase()

/** The words for a whole category, as the catalogue's ids: "shoes" is a category, not a type. */
private val CATEGORY_WORDS: Map<String, String> = mapOf(
    "tops" to "tops",
    "top" to "tops",
    "bottoms" to "bottoms",
    "bottom" to "bottoms",
    "dresses" to "dresses",
    "outerwear" to "outerwear",
    "shoes" to "shoes",
    "shoe" to "shoes",
    "footwear" to "shoes",
    "accessories" to "accessories",
    "accessory" to "accessories",
    "activewear" to "activewear",
    "sportswear" to "activewear",
    "loungewear" to "loungewear",
)

private val SEASON_WORDS: Map<String, Season> = mapOf(
    "spring" to Season.SPRING,
    "summer" to Season.SUMMER,
    "fall" to Season.FALL,
    "autumn" to Season.FALL,
    "winter" to Season.WINTER,
)

private val OCCASION_WORDS: Map<String, Occasion> = buildMap {
    for (occasion in Occasion.entries) put(occasion.id, occasion)
    put("office", Occasion.WORK)
    put("gym", Occasion.SPORT)
    put("sports", Occasion.SPORT)
    put("home", Occasion.LOUNGE)
}

/**
 * Gemini Nano's answer, read line by line: a label, a colon, a value from the
 * list the prompt gave. A line it cannot place is skipped; a value not on its
 * list is too, so a model that answers in Spanish or invents a season fills
 * in nothing rather than something wrong. The brand is taken as written and
 * held to the wardrobe's own afterwards (WardrobeAsk.withBrandFrom).
 */
fun parseWardrobeAsk(answer: String): WardrobeAsk {
    var ask = WardrobeAsk()
    for (raw in answer.lines()) {
        val line = raw.trim().trimStart('-', '*', '•', ' ').replace("*", "")
        val label = line.substringBefore(':', "").trim().lowercase()
        val value = line.substringAfter(':', "").trim().trim('"', '.', ' ')
        if (label.isEmpty() || value.isEmpty() || value.equals("none", ignoreCase = true)) continue
        ask = when (label) {
            "type" -> garmentTypeNamed(value)?.let { ask.copy(category = it.category, subcategory = it.subcategory) } ?: ask
            "category" -> if (ask.category == null) ask.copy(category = CATEGORY_WORDS[value.lowercase()]) else ask
            "colour", "color" -> ask.copy(color = paletteColorNamed(value) ?: ask.color)
            "season" -> ask.copy(season = SEASON_WORDS[value.lowercase()] ?: ask.season)
            "occasion" -> ask.copy(occasion = OCCASION_WORDS[value.lowercase()] ?: ask.occasion)
            "brand" -> ask.copy(brand = value)
            else -> ask
        }
    }
    return ask
}

/**
 * [request] read by plain matching: a type named anywhere in it, the way a
 * shop page's title is read (readPageText), or failing that a category's
 * word; a palette colour, two words before one so "light blue" is not blue;
 * a season's or an occasion's word; and any of the wardrobe's [brands] as a
 * whole word. English only, which is why Nano is asked first where it is.
 */
fun readWardrobeAsk(request: String, brands: List<String>): WardrobeAsk {
    val words = Regex("[\\p{L}\\p{N}&'-]+").findAll(request.lowercase()).map { it.value }.toList()
    val type = readPageText(request, null).type
    val category = type?.category ?: words.firstNotNullOfOrNull { CATEGORY_WORDS[it] }
    val color = words.zipWithNext { a, b -> "$a $b" }.firstNotNullOfOrNull { paletteColorNamed(it) }
        ?: words.firstNotNullOfOrNull { word -> paletteColorNamed(word)?.takeIf { word != "multi" } }
    val brand = brands.firstOrNull { brand ->
        Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(brand.lowercase())}($|[^\\p{L}\\p{N}])").containsMatchIn(request.lowercase())
    }
    return WardrobeAsk(
        category = category,
        subcategory = type?.subcategory,
        color = color,
        season = words.firstNotNullOfOrNull { SEASON_WORDS[it] },
        occasion = words.firstNotNullOfOrNull { OCCASION_WORDS[it] },
        brand = brand,
    )
}
