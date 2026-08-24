package com.wardrobapp.presentation

import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportWarning
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.domain.englishMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The same sentence, in two places, kept the same -- for URL import this time.
 *
 * [ArchiveMessageParityTest] does this for the archive failures and the reasoning
 * is identical: there are two Englishes for every refusal, the one :domain
 * produces and the one in `values/strings.xml` that the Spanish is translated
 * from. Neither can go -- the first is the fallback wherever there are no
 * resources, the second is what a screen shows -- so the risk is drift, and drift
 * is invisible: everything still passes, the app still reads sensibly in English,
 * and only a Spanish reader gets a sentence nobody checked.
 *
 * Three families here, all found by the same convention -- the case's own name in
 * snake_case, under a prefix per family.
 */
class ImportMessageParityTest {

    /** One of every reason, with values that would show up misplaced. */
    private val unsafeUrls: List<UnsafeUrlReason> = listOf(
        UnsafeUrlReason.UrlRequired,
        UnsafeUrlReason.NotAWebAddress,
        UnsafeUrlReason.SchemeNotAllowed,
        UnsafeUrlReason.CredentialsInUrl,
        UnsafeUrlReason.HostIsLocal("192.168.1.1"),
        UnsafeUrlReason.RedirectUnreadable,
        UnsafeUrlReason.RedirectedToLocalHost("169.254.169.254"),
    )

    private val failures: List<ImportFailureReason> = listOf(
        ImportFailureReason.PageTimedOut,
        ImportFailureReason.PageTooLarge,
        ImportFailureReason.PageNotLoaded(503),
        ImportFailureReason.NotAWebPage,
        ImportFailureReason.NoImagesFound,
        ImportFailureReason.NoFetchableImages,
        ImportFailureReason.NoImagesDownloaded,
    )

    /**
     * The warnings, in both plural forms where they have one.
     *
     * Two of these are `<plurals>` in the resources rather than plain strings,
     * which is the whole reason :domain spells out its own singular and plural: a
     * count of one has to read as one image in both places.
     */
    private val warnings: List<ImportWarning> = listOf(
        ImportWarning.StructuredDataUnreadable,
        // Distinct values per slot: two arguments both reading "3" would pass a
        // comparison that had them the wrong way round.
        ImportWarning.ImagesCapped(listed = 12, used = 8),
        ImportWarning.ImagesBlocked(1),
        ImportWarning.ImagesBlocked(4),
        ImportWarning.ImagesFailed(1),
        ImportWarning.ImagesFailed(3),
    )

    @Test
    fun `every refused address reads the same from the resources as from domain`() {
        val english = readStrings()

        for (reason in unsafeUrls) {
            val name = "unsafe_" + reason.caseName().snakeCase()
            val template = english[name]

            assertTrue(template != null, "no string resource called $name")
            assertEquals(
                reason.englishMessage(),
                template!!.asAndroidWouldLoadIt().format(*reason.formatArguments()),
                "$name says something different from :domain",
            )
        }
    }

    @Test
    fun `every failed import reads the same from the resources as from domain`() {
        val english = readStrings()

        for (reason in failures) {
            val name = "import_" + reason.caseName().snakeCase()
            val template = english[name]

            assertTrue(template != null, "no string resource called $name")
            assertEquals(
                reason.englishMessage(),
                template!!.asAndroidWouldLoadIt().format(*reason.formatArguments()),
                "$name says something different from :domain",
            )
        }
    }

    @Test
    fun `every warning reads the same from the resources as from domain`() {
        val english = readStrings()
        val plurals = readPlurals()

        for (warning in warnings) {
            val name = "import_warning_" + warning.caseName().snakeCase()
            val count = warning.pluralCount()
            val template = if (count == null) {
                english[name]
            } else {
                plurals[name]?.get(if (count == 1) "one" else "other")
            }

            assertTrue(template != null, "no resource called $name for a count of $count")
            assertEquals(
                warning.englishMessage(),
                template!!.asAndroidWouldLoadIt().format(*warning.formatArguments()),
                "$name says something different from :domain",
            )
        }
    }

    @Test
    fun `there is a sample for every reason there is`() {
        // Without this, adding a reason and forgetting its resource would leave
        // the checks above passing over the ones that were already there.
        assertEquals(
            emptySet(),
            UnsafeUrlReason::class.sealedSubclasses.toSet() - unsafeUrls.map { it::class }.toSet(),
            "no sample, so never compared",
        )
        assertEquals(
            emptySet(),
            ImportFailureReason::class.sealedSubclasses.toSet() - failures.map { it::class }.toSet(),
            "no sample, so never compared",
        )
        assertEquals(
            emptySet(),
            ImportWarning::class.sealedSubclasses.toSet() - warnings.map { it::class }.toSet(),
            "no sample, so never compared",
        )
    }

    @Test
    fun `the Spanish says something of its own`() {
        // `MissingTranslation` in :app lint catches an absent translation, and
        // `StringResourceParityTest` catches a Spanish string left as its English
        // twin across the file. These strings are the ones a reader meets when
        // something has gone wrong, so they are worth checking here too -- the
        // whole point of carrying reasons instead of sentences.
        val english = readStrings()
        val spanish = readStrings(locale = "values-es")

        val names = (unsafeUrls.map { "unsafe_" + it.caseName().snakeCase() } +
            failures.map { "import_" + it.caseName().snakeCase() })
            .distinct()

        for (name in names) {
            val translated = spanish[name]
            assertTrue(translated != null, "no Spanish for $name")
            assertTrue(
                translated != english[name],
                "$name is still the English sentence in values-es",
            )
        }
    }

    private fun Any.caseName(): String =
        this::class.simpleName ?: error("an anonymous reason")

    /**
     * `NotAWebAddress` becomes `not_a_web_address`.
     *
     * Two rules, not one, exactly as [ArchiveMessageParityTest] needs: splitting
     * only where a lowercase meets an uppercase turns `ImagesCapped` into
     * `images_capped` correctly but `NotABackup` into `not_abackup`, so a run of
     * capitals also splits before its last one.
     */
    private fun String.snakeCase(): String = this
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), "_")
        .lowercase()

    private fun UnsafeUrlReason.formatArguments(): Array<Any> = when (this) {
        is UnsafeUrlReason.HostIsLocal -> arrayOf(host)
        is UnsafeUrlReason.RedirectedToLocalHost -> arrayOf(host)
        else -> emptyArray()
    }

    private fun ImportFailureReason.formatArguments(): Array<Any> = when (this) {
        is ImportFailureReason.PageNotLoaded -> arrayOf(status)
        else -> emptyArray()
    }

    private fun ImportWarning.formatArguments(): Array<Any> = when (this) {
        ImportWarning.StructuredDataUnreadable -> emptyArray()
        is ImportWarning.ImagesCapped -> arrayOf(listed, used)
        is ImportWarning.ImagesBlocked -> arrayOf(count)
        is ImportWarning.ImagesFailed -> arrayOf(count)
    }

    /** The count a warning is pluralized on, or null if it is not. */
    private fun ImportWarning.pluralCount(): Int? = when (this) {
        is ImportWarning.ImagesBlocked -> count
        is ImportWarning.ImagesFailed -> count
        else -> null
    }

    /**
     * A resource value as the app receives it, not as the file spells it.
     *
     * Android requires an apostrophe in a string resource to be escaped and
     * unescapes it on the way out, so comparing raw file text against a Kotlin
     * string fails on exactly that character.
     */
    private fun String.asAndroidWouldLoadIt(): String = this
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")

    private fun readStrings(locale: String = "values"): Map<String, String> {
        val elements = parse(locale).getElementsByTagName("string")

        return (0 until elements.length).associate { index ->
            val element = elements.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    /** Each `<plurals>` as its quantity-to-text map. */
    private fun readPlurals(locale: String = "values"): Map<String, Map<String, String>> {
        val elements = parse(locale).getElementsByTagName("plurals")

        return (0 until elements.length).associate { index ->
            val element = elements.item(index) as Element
            val items = element.getElementsByTagName("item")
            val byQuantity = (0 until items.length).associate { item ->
                val quantity = items.item(item) as Element
                quantity.getAttribute("quantity") to quantity.textContent
            }
            element.getAttribute("name") to byQuantity
        }
    }

    private fun parse(locale: String): org.w3c.dom.Document {
        val resDir = System.getProperty("appResDir")
            ?: error("appResDir was not set; see presentation/build.gradle.kts")
        val file = File(File(resDir, locale), "strings.xml")
        assertTrue(file.isFile, "expected string resources at $file")

        return DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
    }
}
