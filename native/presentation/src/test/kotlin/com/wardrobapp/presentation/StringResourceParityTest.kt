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
 * - lint compares names, not *placeholders*. A `%1$s` that becomes `%2$s` in
 *   translation swaps two values in a sentence, which is invisible unless you
 *   read the language.
 * - lint has no opinion about a Spanish string left byte-identical to its
 *   English twin. That is how the app that ships came to have `flats`, `bra` and
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
    fun `a translation uses the same placeholders in the same order`() {
        val wrong = english.keys.mapNotNull { name ->
            val here = placeholders(english.getValue(name))
            val there = placeholders(spanish.getValue(name))
            if (here == there) null else "$name: en=$here es=$there"
        }

        assertTrue(wrong.isEmpty(), "placeholders differ:\n  " + wrong.joinToString("\n  "))
    }

    @Test
    fun `the placeholder check can tell a reordering from a match`() {
        // Nothing in either file carries a placeholder yet, so the check above
        // currently passes over an empty corpus -- the shape of test that quietly
        // means nothing. This pins the comparison itself, so the check is known to
        // work before the first formatted string arrives and needs it.
        assertEquals(listOf("%1\$s", "%2\$d"), placeholders("%1\$s has %2\$d"))
        assertEquals(emptyList(), placeholders("nothing to substitute"))
        assertTrue(
            placeholders("%1\$s (%2\$d)") != placeholders("%2\$d (%1\$s)"),
            "a reordered pair has to compare unequal, or the check cannot fire",
        )
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
    private fun placeholders(value: String): List<String> =
        Regex("%\\d+\\$[sd]").findAll(value).map { it.value }.toList()

    private fun readStrings(directory: String): Map<String, String> {
        val resDir = System.getProperty("appResDir")
            ?: error("appResDir was not set; see presentation/build.gradle.kts")
        val file = File(resDir, "$directory/strings.xml")
        assertTrue(file.isFile, "expected string resources at $file")

        // Parsed as XML rather than scraped with a regex, which is what this did
        // first. The regex read the file happily while it was not well-formed at
        // all -- an em dash written as "--" inside a comment, which XML forbids
        // -- and the whole point of this test is that it runs where aapt cannot.
        // A test that only checks what a regex can see hands that class of
        // mistake straight to CI.
        val document = DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)

        val strings = document.getElementsByTagName("string")

        return (0 until strings.length).associate { index ->
            val element = strings.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}
