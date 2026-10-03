package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Whether an archive can be restored, decided before anything is touched.
 *
 * Every refusal here is the app declining to overwrite a wardrobe, so the reason
 * matters as much as the refusal: "unsupported backup version" on its own leaves
 * someone with no idea whether to update the app or give up on the file. The
 * reasons are values rather than sentences so `:app` can say them in the reader's
 * language, and [englishMessage] is what the places with no resources use.
 *
 * These cases replace 41 recorded archives, whose expected answers came from the
 * app this was ported from.
 */
class BackupArchiveTest {

    private fun refusal(text: String): UnrestorableReason =
        assertFailsWith<UnrestorableArchiveException> { parseArchiveManifest(text) }.reason

    @Test
    fun `a manifest this build understands is accepted`() {
        val manifest = parseArchiveManifest(
            """{"version":$BACKUP_VERSION,"created_at":"2026-01-01T00:00:00.000Z","image_count":4}"""
        )

        assertEquals(BACKUP_VERSION, manifest.version)
        assertEquals("2026-01-01T00:00:00.000Z", manifest.createdAt)
        assertEquals(4, manifest.imageCount)
    }

    @Test
    fun `fields this build does not know about are ignored, not refused`() {
        // A newer build may add them, and an archive that carries one is still
        // readable by this build if its version says so.
        val manifest = parseArchiveManifest("""{"version":$BACKUP_VERSION,"somethingNew":true}""")

        assertEquals(BACKUP_VERSION, manifest.version)
        assertEquals(null, manifest.imageCount)
    }

    @Test
    fun `a whole-valued version written as a decimal is accepted`() {
        // JSON has no integers, and JavaScript's Number.isInteger accepts 3.0 --
        // so a manifest written that way is valid in the archives already out
        // there and has to be valid here too.
        val manifest = parseArchiveManifest("""{"version":3.0}""")
        assertTrue(manifest.version == BACKUP_VERSION)

        val fractional = runCatching { parseArchiveManifest("""{"version":3.5}""") }
        assertTrue(fractional.isFailure, "3.5 is not a format version")
    }

    @Test
    fun `something that is not a manifest is refused as such`() {
        assertTrue(refusal("not json at all") is UnrestorableReason.ManifestUnreadable)
        assertTrue(refusal("[]") is UnrestorableReason.ManifestNotABackup)
        assertTrue(refusal("""{"createdAt":"2026-01-01"}""") is UnrestorableReason.ManifestVersionMissing)
    }

    @Test
    fun `a version from a newer build says to update the app`() {
        val reason = refusal("""{"version":${BACKUP_VERSION + 1}}""")

        assertEquals(
            UnrestorableReason.BackupFromNewerApp(found = BACKUP_VERSION + 1, supported = BACKUP_VERSION),
            reason,
        )
        // Both numbers in the sentence, because that is what makes it actionable.
        assertTrue("${BACKUP_VERSION + 1}" in reason.englishMessage())
        assertTrue("$BACKUP_VERSION" in reason.englishMessage())
    }

    @Test
    fun `a version from no build at all is refused differently`() {
        // A distinct reason because the answer differs: updating the app fixes
        // the case above and does nothing for this one.
        val reason = refusal("""{"version":0}""")

        assertTrue(reason is UnrestorableReason.UnsupportedVersion, "got $reason")
        assertTrue("0" in reason.englishMessage())
    }

    @Test
    fun `an archive missing its database is refused rather than applied`() {
        // The failure this check exists for: an archive that lost its database
        // once wiped every photo and reported success, leaving rows pointing at
        // files that were no longer there.
        val thrown = assertFailsWith<UnrestorableArchiveException> {
            checkArchiveCompleteness(
                ArchiveManifest(version = BACKUP_VERSION, imageCount = 3),
                hasDatabase = false,
                imageCount = 3,
            )
        }

        assertEquals(UnrestorableReason.DatabaseMissing(ARCHIVE_DB_FILENAME), thrown.reason)
    }

    @Test
    fun `an archive with fewer photos than it promised is refused`() {
        val thrown = assertFailsWith<UnrestorableArchiveException> {
            checkArchiveCompleteness(
                ArchiveManifest(version = BACKUP_VERSION, imageCount = 10),
                hasDatabase = true,
                imageCount = 7,
            )
        }

        assertEquals(
            UnrestorableReason.ArchiveTruncated(expected = 10, present = 7),
            thrown.reason,
        )
    }

    @Test
    fun `more photos than promised is not a truncated archive`() {
        // Only fewer is evidence of a truncated download. More can happen
        // legitimately, and refusing it would reject a good backup.
        checkArchiveCompleteness(
            ArchiveManifest(version = BACKUP_VERSION, imageCount = 2),
            hasDatabase = true,
            imageCount = 5,
        )

        // And a manifest that never said how many is not asserting anything.
        checkArchiveCompleteness(
            ArchiveManifest(version = BACKUP_VERSION, imageCount = null),
            hasDatabase = true,
            imageCount = 0,
        )
    }

    @Test
    fun `an old archive is restorable, and an old archive with no database is not`() {
        for (version in LEGACY_BACKUP_VERSIONS) {
            checkLegacyPayload(version, hasDatabase = true)
        }

        assertEquals(
            UnrestorableReason.NoDatabase,
            assertFailsWith<UnrestorableArchiveException> {
                checkLegacyPayload(LEGACY_BACKUP_VERSIONS.first(), hasDatabase = false)
            }.reason,
        )

        val unknown = assertFailsWith<UnrestorableArchiveException> {
            checkLegacyPayload(99, hasDatabase = true)
        }.reason
        assertTrue(unknown is UnrestorableReason.UnsupportedVersion)
        // The sentence lists what this build can read, so someone with an old
        // file knows whether it is worth keeping.
        assertTrue("$BACKUP_VERSION" in unknown.englishMessage())
    }

    @Test
    fun `every reason has a sentence, and no two share one`() {
        val reasons = listOf(
            UnrestorableReason.ManifestUnreadable("manifest.json"),
            UnrestorableReason.ManifestNotABackup("manifest.json"),
            UnrestorableReason.ManifestVersionMissing("manifest.json"),
            UnrestorableReason.ManifestNotFound("manifest.json"),
            UnrestorableReason.BackupFromNewerApp(found = 4, supported = 3),
            UnrestorableReason.UnsupportedVersion(found = 0, readable = "1, 2 and 3"),
            UnrestorableReason.DatabaseMissing("wardrobe.db"),
            UnrestorableReason.DatabaseEmpty("wardrobe.db"),
            UnrestorableReason.NoDatabase,
            UnrestorableReason.ArchiveTruncated(expected = 10, present = 7),
            UnrestorableReason.NotBase64,
            UnrestorableReason.EntryOutsideArchive("../../etc/passwd"),
            UnrestorableReason.IntegrityCheckFailed("malformed"),
            UnrestorableReason.InvalidBackup(ArchiveDetail.Foreign("disk full")),
            UnrestorableReason.RestoreFailed(ArchiveDetail.Foreign("disk full")),
            UnrestorableReason.RollbackFailed(
                detail = ArchiveDetail.Foreign("disk full"),
                rollbackDetail = ArchiveDetail.Foreign("also disk full"),
                databaseName = "wardrobe.db.bak",
                imagesName = "images.bak",
            ),
        )

        val messages = reasons.map { it.englishMessage() }

        assertTrue(messages.all { it.isNotBlank() }, "a reason with no sentence")
        assertEquals(messages.size, messages.toSet().size, "two reasons read the same")
    }

    @Test
    fun `the worst failure names where the data still is`() {
        // A restore that failed and could not be put back is the only case where
        // the user has to go and find files themselves, so the sentence has to
        // name them.
        val reason = UnrestorableReason.RollbackFailed(
            detail = ArchiveDetail.Foreign("disk full"),
            rollbackDetail = ArchiveDetail.Known(UnrestorableReason.NotBase64),
            databaseName = "wardrobe.db.bak",
            imagesName = "images.bak",
        )

        val message = reason.englishMessage()

        assertTrue("wardrobe.db.bak" in message, message)
        assertTrue("images.bak" in message, message)
    }

    @Test
    fun `someone else's words are kept, and marked as theirs`() {
        // SQLite's and the JDK's messages are the only diagnostic there is when
        // something outside the app fails, so they are carried through -- while
        // staying distinguishable from the sentences the app wrote itself.
        val foreign = ArchiveDetail.Foreign("attempt to write a readonly database")
        val known = ArchiveDetail.Known(UnrestorableReason.NotBase64)

        assertTrue("readonly database" in foreign.englishText())
        assertEquals(UnrestorableReason.NotBase64.englishMessage(), known.englishText())
    }
}
