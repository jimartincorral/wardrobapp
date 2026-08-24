package com.wardrobapp.presentation

import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.SUBCATEGORY_KEYS
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port's two string tables, held to each other.
 *
 * Here rather than in :app for one reason: :app needs the Android SDK, this
 * machine has none, and so `:app:lint` -- which is where `MissingTranslation`
 * lives -- cannot run until CI. A localization mistake that reaches CI has
 * already cost a round trip, and three of these checks are ones lint does not
 * make at all:
 *
 * - lint compares names, not what a string *substitutes*. A `%1$s` that becomes
 *   `%1$d` in translation is a crash, and one that goes missing drops a value out
 *   of a sentence -- both invisible unless you read the language.
 * - lint has no opinion about a Spanish string left byte-identical to its
 *   English twin. That is how the app this replaced came to have `flats`, `bra` and
 *   `shapewear` untranslated among 24 legitimate loanwords.
 * - lint knows nothing about the vocabulary the *domain* defines. A garment type
 *   added to `GARMENT_CATEGORIES` with no matching resource renders as its raw
 *   stored value, and nothing in the build would say so.
 * - and neither file has to be well-formed XML for a regex to read it. That is
 *   not hypothetical: the first version of this test scraped both files with a
 *   regex and passed on XML that aapt rejected outright.
 *
 * The res directory is handed over as a system property by build.gradle.kts
 * rather than reached for with a relative path, so the coupling to :app's layout
 * is declared where a reader will find it.
 */
class StringResourceParityTest {

    private val english = readStrings("values")
    private val spanish = readStrings("values-es")

    /**
     * Spanish entries that are deliberately the same word as the English.
     *
     * Every one is a loanword Spanish uses as-is, or a name. Kept explicit so
     * that a *new* identical pair is a failure and has to be either translated
     * or added here on purpose -- which is what would have caught the three the
     * shipping app got wrong.
     */
    private val sameInBothLanguages = setOf(
        // A name.
        "app_name",
        // Each language names itself in itself, as the shipping app does.
        "language_english",
        "language_spanish",
        // An em dash.
        "count_unknown",
        // Loanwords Spanish uses unchanged.
        "occasion_casual",
        "occasion_formal",
        "color_beige",
        "color_coral",
        "subcategory_blazer",
        "subcategory_boxers",
        "subcategory_cardigan",
        "subcategory_chinos",
        "subcategory_jeans",
        "subcategory_leggings",
        "subcategory_maxi",
        "subcategory_midi",
        "subcategory_mini",
        "subcategory_parka",
        "subcategory_polo",
        "subcategory_poncho",
        // An alphabet range, not a word.
        "statistics_sort_name",
        // A unit symbol, and the same one in both languages.
        "settings_megabytes",
    )

    @Test
    fun `both languages define the same names`() {
        assertEquals(
            emptySet(),
            english.keys - spanish.keys,
            "these have no Spanish; a Spanish reader silently gets the English",
        )
        assertEquals(
            emptySet(),
            spanish.keys - english.keys,
            "these are Spanish-only, so they are unreachable and probably misspelled",
        )
    }

    @Test
    fun `a translation substitutes the same values, whatever order it says them in`() {
        // Position and type, not sequence. Reordering is the entire reason
        // positional arguments exist: Spanish is free to put the count before the
        // name, and a check that compared appearance order would reject correct
        // translations -- which is what the first version of this did.
        val wrong = english.keys.mapNotNull { name ->
            val here = formatArguments(english.getValue(name))
            val there = formatArguments(spanish.getValue(name))
            if (here == there) null else "$name: en=$here es=$there"
        }

        assertTrue(
            wrong.isEmpty(),
            "these substitute different values:\n  " + wrong.joinToString("\n  "),
        )
    }

    /**
     * Quantities Spanish has and English does not.
     *
     * CLDR gives Spanish one/many/other. `many` is the compact-form category --
     * "1 millon de fotos" -- and lint requires it; English has no equivalent, so
     * adding it there would only earn an UnusedQuantity warning back.
     */
    private val SPANISH_ONLY_QUANTITIES = setOf("many")

    @Test
    fun `both languages define the same plurals, with the quantities each needs`() {
        // Read separately because <plurals> is not <string>: the checks above walk
        // string elements only, so a plural could have gone missing, lost a
        // quantity, or dropped its %d without any of them noticing.
        val here = readPlurals("values")
        val there = readPlurals("values-es")

        assertEquals(here.keys, there.keys, "the set of plurals differs")

        for (name in here.keys) {
            // Not the same set: the quantities a language needs are the
            // language's, not the resource's. English has one/other; Spanish also
            // has `many`, CLDR's category for the compact forms ("1 millon de
            // fotos"), and lint's MissingQuantity fails a Spanish plural without
            // it. So English's quantities have to be there, and anything extra
            // has to be a quantity Spanish actually has.
            assertTrue(
                there.getValue(name).keys.containsAll(here.getValue(name).keys),
                "$name is missing ${here.getValue(name).keys - there.getValue(name).keys} in Spanish",
            )
            assertEquals(
                emptySet(),
                there.getValue(name).keys - here.getValue(name).keys - SPANISH_ONLY_QUANTITIES,
                "$name has a quantity Spanish does not use",
            )

            for ((quantity, value) in there.getValue(name)) {
                // An extra Spanish quantity is compared against the English form
                // it stands in for, which is `other` -- otherwise `many` could
                // substitute anything at all.
                val english = here.getValue(name)[quantity] ?: here.getValue(name).getValue("other")

                assertEquals(
                    formatArguments(english),
                    formatArguments(value),
                    "what $name/$quantity substitutes",
                )
            }
        }
    }

    @Test
    fun `a count keeps its number in every quantity`() {
        // "1 garment" reads correctly in English and would be a lint mismatch
        // against "%d garments"; more to the point, a language whose "one" form
        // covers more than one still needs the number.
        for ((name, forms) in readPlurals("values") + readPlurals("values-es")) {
            for ((quantity, value) in forms) {
                assertTrue(
                    formatArguments(value).containsValue('d'),
                    "$name/$quantity has no count in it: \"$value\"",
                )
            }
        }
    }

    @Test
    fun `reading format arguments`() {
        // Pinned directly, because every property the checks above rely on lives
        // in this one function.
        assertEquals(mapOf(1 to 's', 2 to 'd'), formatArguments("%1\$s has %2\$d"))
        assertEquals(emptyMap(), formatArguments("nothing to substitute"))

        // A bare argument takes the next position, so "%d" and "%1\$d" are one
        // string spelled two ways, and a translation may use either.
        assertEquals(formatArguments("%1\$d"), formatArguments("%d"))
        assertEquals(mapOf(1 to 'd', 2 to 's'), formatArguments("%d and %s"))

        // Reordering is allowed; changing a type is not.
        assertEquals(formatArguments("%1\$s (%2\$d)"), formatArguments("%2\$d (%1\$s)"))
        assertTrue(formatArguments("%1\$s") != formatArguments("%1\$d"))
        assertTrue(formatArguments("%1\$s %2\$d") != formatArguments("%1\$s"))

        // An escaped percent substitutes nothing.
        assertEquals(emptyMap(), formatArguments("100%% cotton"))
    }

    @Test
    fun `nothing is left in English by accident`() {
        val untranslated = english.keys
            .filter { it !in sameInBothLanguages }
            .filter { english.getValue(it) == spanish.getValue(it) }

        assertTrue(
            untranslated.isEmpty(),
            "identical in both languages, and not on the deliberate list:\n  " +
                untranslated.joinToString("\n  ") { "$it = ${english.getValue(it)}" },
        )
    }

    @Test
    fun `the allowlist has no stale entries`() {
        // Otherwise it grows into a place where a real mistake can hide.
        val stale = sameInBothLanguages.filter { name ->
            name !in english || english[name] != spanish[name]
        }

        assertEquals(emptyList(), stale, "no longer identical, or gone -- drop from the allowlist")
    }

    @Test
    fun `every category and garment type the domain knows about has a name`() {
        val missing = mutableListOf<String>()

        for (category in GARMENT_CATEGORIES) {
            val name = "category_" + category.id.replace('-', '_')
            if (name !in english) missing += name

            for (type in category.subcategories) {
                // Through SUBCATEGORY_KEYS rather than by slugging the label,
                // because there is no rule to slug it by: "T-Shirt" is `tshirt`
                // while "Tank Top" is `tank_top`. Deriving one here is how the
                // first version of this test passed on a resource name that does
                // not exist.
                val key = SUBCATEGORY_KEYS[type]
                if (key == null) {
                    missing += "$type has no translation key at all"
                } else if ("subcategory_$key" !in english) {
                    missing += "subcategory_$key ($type)"
                }
            }
        }

        assertTrue(missing.isEmpty(), "no string resource for:\n  " + missing.joinToString("\n  "))
    }

    @Test
    fun `every palette colour has a name`() {
        val missing = GARMENT_COLORS
            .map { (key, _) -> "color_" + key.snakeCase() }
            .filterNot { it in english }

        assertTrue(missing.isEmpty(), "no string resource for:\n  " + missing.joinToString("\n  "))
    }

    private fun String.snakeCase(): String =
        replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()

    /** `%1$s` and friends, in order of appearance. */
    /**
     * One resource file, as a document.
     *
     * Parsed as XML rather than scraped with a regex, which is what this did
     * first. The regex read the file happily while it was not well-formed at all
     * -- an em dash written as "--" inside a comment, which XML forbids -- and
     * the whole point of this test is that it runs where aapt cannot. A check
     * that only sees what a regex sees hands that class of mistake to CI.
     */
    private fun parse(directory: String): org.w3c.dom.Document {
        val resDir = System.getProperty("appResDir")
            ?: error("appResDir was not set; see presentation/build.gradle.kts")
        val file = File(resDir, "$directory/strings.xml")
        assertTrue(file.isFile, "expected string resources at $file")

        return DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
    }

    /**
     * What a string substitutes: argument position to conversion type.
     *
     * A bare `%d` takes the next implicit position, which is how Android reads it,
     * so `%d` and `%1$d` come out equal. `%%` is a literal percent and substitutes
     * nothing.
     */
    private fun formatArguments(value: String): Map<Int, Char> {
        val arguments = mutableMapOf<Int, Char>()
        var implicit = 0

        for (match in Regex("%(?:(\\d+)\\$)?([%sdf])").findAll(value)) {
            val type = match.groupValues[2].single()
            if (type == '%') continue

            val position = match.groupValues[1].toIntOrNull() ?: ++implicit
            arguments[position] = type
        }

        return arguments
    }

    /** Plural name to quantity to text. */
    private fun readPlurals(directory: String): Map<String, Map<String, String>> {
        val plurals = parse(directory).getElementsByTagName("plurals")

        return (0 until plurals.length).associate { index ->
            val element = plurals.item(index) as Element
            val items = element.getElementsByTagName("item")

            element.getAttribute("name") to (0 until items.length).associate { item ->
                val quantity = items.item(item) as Element
                quantity.getAttribute("quantity") to quantity.textContent
            }
        }
    }

    private fun readStrings(directory: String): Map<String, String> {
        val strings = parse(directory).getElementsByTagName("string")

        return (0 until strings.length).associate { index ->
            val element = strings.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}
