package com.wardrobapp.presentation

import com.wardrobapp.data.ArchiveDetail
import com.wardrobapp.data.UnrestorableReason
import com.wardrobapp.data.englishMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The same sentence, in two places, kept the same.
 *
 * There are two Englishes for every archive failure: the one `:data` produces
 * (`UnrestorableReason.englishMessage`) and the one in `values/strings.xml`, which
 * the Spanish is translated from. Neither can be deleted -- the first is the
 * fallback wherever there are no resources, and the second is what a screen shows
 * -- so the risk is that they drift, and a drift is invisible: everything still
 * passes, the app still reads sensibly in English, and only a Spanish reader gets
 * a sentence nobody checked.
 *
 * This closes that. Every reason is formatted from the resource with the values it
 * carries and compared to the sentence `:data` builds. A reason with no resource,
 * a resource with the wrong wording, and a placeholder in the wrong place all
 * fail, here, on a machine with no Android SDK.
 *
 * The resource for a case is found by convention: `archive_` plus the case's own
 * name in snake_case. What this does *not* check is that `:app`'s `when` maps each
 * reason to the resource of the same name -- that mistake would show up in English
 * too, which is the loud kind.
 */
class ArchiveMessageParityTest {

    /**
     * One of every reason, with values that would show up misplaced.
     *
     * Distinct values per slot on purpose: two arguments that both read "x" would
     * pass a comparison that had them the wrong way round.
     */
    private val samples: List<UnrestorableReason> = listOf(
        UnrestorableReason.ManifestUnreadable("manifest.json"),
        UnrestorableReason.ManifestNotABackup("backup.json"),
        UnrestorableReason.ManifestVersionMissing("manifest.json"),
        UnrestorableReason.ManifestNotFound("manifest.json"),
        UnrestorableReason.BackupFromNewerApp(found = 7, supported = 3),
        UnrestorableReason.UnsupportedVersion(found = 1, readable = "1, 2 and 3"),
        UnrestorableReason.DatabaseMissing("wardrobapp.db"),
        UnrestorableReason.DatabaseEmpty("wardrobapp.db"),
        UnrestorableReason.NoDatabase,
        UnrestorableReason.ArchiveTruncated(expected = 12, present = 5),
        UnrestorableReason.NotBase64,
        UnrestorableReason.EntryOutsideArchive("../../etc/passwd"),
        UnrestorableReason.IntegrityCheckFailed("malformed"),
        UnrestorableReason.InvalidBackup(ArchiveDetail.Foreign("disk full")),
        UnrestorableReason.RestoreFailed(ArchiveDetail.Foreign("disk full")),
        UnrestorableReason.RollbackFailed(
            detail = ArchiveDetail.Foreign("disk full"),
            rollbackDetail = ArchiveDetail.Foreign("permission denied"),
            databaseName = "wardrobapp.db.previous",
            imagesName = "garment-images.previous",
        ),
    )

    @Test
    fun `every reason reads the same from the resources as from data`() {
        val english = readStrings()

        for (reason in samples) {
            val name = reason.resourceName()
            val template = english[name]

            assertTrue(template != null, "no string resource called $name")
            assertEquals(
                reason.englishMessage(),
                template!!.asAndroidWouldLoadIt().format(*reason.formatArguments()),
                "$name says something different from :data",
            )
        }
    }

    @Test
    fun `there is a sample for every reason there is`() {
        // Without this, adding a reason and forgetting its resource would leave
        // the check above passing over the ones that were already there.
        val covered = samples.map { it::class }.toSet()
        val all = UnrestorableReason::class.sealedSubclasses.toSet()

        assertEquals(emptySet(), all - covered, "no sample, so never compared")
        assertEquals(emptySet(), covered - all, "sampled but no longer a reason")
    }

    @Test
    fun `a nested reason is spelled out, and foreign text is not`() {
        // The one case where a failure carries another failure rather than
        // somebody else's words -- and the reason ArchiveDetail exists.
        val nested = UnrestorableReason.InvalidBackup(
            ArchiveDetail.Known(UnrestorableReason.IntegrityCheckFailed("malformed"))
        )

        assertEquals(
            "Invalid backup: it failed SQLite's integrity check (malformed). Nothing was changed.",
            nested.englishMessage(),
        )
    }

    /**
     * `ManifestNotABackup` becomes `archive_manifest_not_a_backup`.
     *
     * Two rules, not one. Splitting only where a lowercase meets an uppercase
     * turns that name into `manifest_not_abackup`, because "ABackup" has two
     * capitals in a row -- so a run of capitals also splits before its last one,
     * where the next word starts.
     */
    private fun UnrestorableReason.resourceName(): String {
        val name = this::class.simpleName ?: error("an anonymous reason")
        val snake = name
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
            .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), "_")
            .lowercase()
        return "archive_$snake"
    }

    /** The values a reason substitutes, in the order the resource expects them. */
    private fun UnrestorableReason.formatArguments(): Array<Any> = when (this) {
        is UnrestorableReason.ManifestUnreadable -> arrayOf(name)
        is UnrestorableReason.ManifestNotABackup -> arrayOf(name)
        is UnrestorableReason.ManifestVersionMissing -> arrayOf(name)
        is UnrestorableReason.ManifestNotFound -> arrayOf(name)
        is UnrestorableReason.BackupFromNewerApp -> arrayOf(found, supported)
        is UnrestorableReason.UnsupportedVersion -> arrayOf(found, readable)
        is UnrestorableReason.DatabaseMissing -> arrayOf(name)
        is UnrestorableReason.DatabaseEmpty -> arrayOf(name)
        UnrestorableReason.NoDatabase -> emptyArray()
        is UnrestorableReason.ArchiveTruncated -> arrayOf(expected, present)
        UnrestorableReason.NotBase64 -> emptyArray()
        is UnrestorableReason.EntryOutsideArchive -> arrayOf(entry)
        is UnrestorableReason.IntegrityCheckFailed -> arrayOf(result)
        is UnrestorableReason.InvalidBackup -> arrayOf(detail.text())
        is UnrestorableReason.RestoreFailed -> arrayOf(detail.text())
        is UnrestorableReason.RollbackFailed ->
            arrayOf(detail.text(), rollbackDetail.text(), databaseName, imagesName)
    }

    private fun ArchiveDetail.text(): String = when (this) {
        is ArchiveDetail.Known -> reason.englishMessage()
        is ArchiveDetail.Foreign -> text
    }

    /**
     * A resource value as the app receives it, not as the file spells it.
     *
     * Android requires an apostrophe in a string resource to be escaped, and
     * unescapes it on the way out -- so the file holds `SQLite\'s` where the app
     * shows `SQLite's`. Comparing the raw file text against a Kotlin string fails
     * on exactly that one character, which is how this was found.
     */
    private fun String.asAndroidWouldLoadIt(): String = this
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")

    private fun readStrings(): Map<String, String> {
        val resDir = System.getProperty("appResDir")
            ?: error("appResDir was not set; see presentation/build.gradle.kts")
        val file = File(resDir, "values/strings.xml")
        assertTrue(file.isFile, "expected string resources at $file")

        val strings = DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("string")

        return (0 until strings.length).associate { index ->
            val element = strings.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}
