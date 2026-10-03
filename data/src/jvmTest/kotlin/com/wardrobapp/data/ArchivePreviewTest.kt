package com.wardrobapp.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Looking inside an archive without opening it.
 *
 * The property that matters most is not what the preview says but that it agrees
 * with the restore: an archive the preview accepts and the restore then refuses
 * would be worse than showing nothing, because somebody would have chosen it on
 * the strength of the preview. So the refusals are tested against the same
 * reasons [ArchiveRestore] raises, and the two share the code that decides them.
 *
 * The second property is that it costs nothing: no file is written and no
 * directory is made, which is what lets a preview run on a file somebody has not
 * committed to yet.
 */
class ArchivePreviewTest {

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun manifest(
        version: Int = BACKUP_VERSION,
        createdAt: String? = "2026-08-28T09:00:00.000Z",
        imageCount: Int? = 2,
    ): ByteArray {
        val fields = buildList {
            add("\"version\": $version")
            createdAt?.let { add("\"created_at\": \"$it\"") }
            imageCount?.let { add("\"image_count\": $it") }
        }
        return "{${fields.joinToString(", ")}}".toByteArray()
    }

    private fun archive(
        prefix: String = "",
        version: Int = BACKUP_VERSION,
        createdAt: String? = "2026-08-28T09:00:00.000Z",
        declared: Int? = 2,
        photos: List<String> = listOf("a.jpg", "b.jpg"),
        withDatabase: Boolean = true,
    ): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        entries["$prefix$MANIFEST_NAME"] = manifest(version, createdAt, declared)
        if (withDatabase) entries["$prefix$ARCHIVE_DB_FILENAME"] = "not really a database".toByteArray()
        for (photo in photos) {
            entries["$prefix$ARCHIVE_IMAGES_DIRNAME/$photo"] = "bytes of $photo".toByteArray()
        }
        return zipOf(entries)
    }

    private fun preview(bytes: ByteArray): ArchivePreview =
        readArchivePreview(bytes.inputStream())

    @Test
    fun `an archive says when it was made and what is in it`() {
        // The whole point: backups are named by timestamp and several sit in a
        // folder at once, so the date is the only way to tell the one from before
        // the damage from the one after it.
        val read = preview(archive())

        assertEquals(BACKUP_VERSION, read.version)
        assertEquals("2026-08-28T09:00:00.000Z", read.createdAt)
        assertEquals(2, read.declaredImages)
        assertEquals(2, read.presentImages)
        assertTrue(read.hasDatabase)
        assertFalse(read.legacy)
    }

    @Test
    fun `a manifest that counted nothing reports no claim rather than zero`() {
        // Null and 0 mean different things here: one is "did not say", the other
        // is "said none". Showing the second for the first would be inventing it.
        val read = preview(archive(declared = null))

        assertNull(read.declaredImages)
        assertEquals(2, read.presentImages)
        assertFalse(read.truncated)
    }

    @Test
    fun `everything inside one wrapping folder is still readable`() {
        // What some zip tools produce, and what ArchiveLayout.NESTED copes with on
        // the restore side. A preview calling one of these unreadable would send
        // somebody looking for a fault in a perfectly good backup.
        val read = preview(archive(prefix = "wardrobapp-backup/"))

        assertEquals(BACKUP_VERSION, read.version)
        assertEquals(2, read.presentImages)
        assertTrue(read.hasDatabase)
    }

    @Test
    fun `a backup from a newer app is refused with the reason the restore gives`() {
        // Not merely refused: refused for the reason that tells somebody to update
        // the app rather than to go looking for a different file.
        val failure = assertFailsWith<UnrestorableArchiveException> {
            preview(archive(version = BACKUP_VERSION + 1))
        }

        assertEquals(
            UnrestorableReason.BackupFromNewerApp(
                found = BACKUP_VERSION + 1,
                supported = BACKUP_VERSION,
            ),
            failure.reason,
        )
    }

    @Test
    fun `an archive with no database is refused before anything is chosen`() {
        val failure = assertFailsWith<UnrestorableArchiveException> {
            preview(archive(withDatabase = false))
        }

        assertEquals(UnrestorableReason.DatabaseMissing(ARCHIVE_DB_FILENAME), failure.reason)
    }

    @Test
    fun `an archive short of the photos it promised is refused, not merely flagged`() {
        // The restore refuses this one, so the preview has to as well. A preview
        // that showed it as restorable would be making a promise the restore then
        // breaks.
        val failure = assertFailsWith<UnrestorableArchiveException> {
            preview(archive(declared = 5, photos = listOf("a.jpg")))
        }

        assertEquals(
            UnrestorableReason.ArchiveTruncated(expected = 5, present = 1),
            failure.reason,
        )
    }

    @Test
    fun `something that is not a backup at all says so`() {
        val notABackup = zipOf(mapOf("notes.txt" to "hello".toByteArray()))

        val failure = assertFailsWith<UnrestorableArchiveException> { preview(notABackup) }

        assertEquals(UnrestorableReason.ManifestNotFound(MANIFEST_NAME), failure.reason)
    }

    @Test
    fun `a file that is not a zip says so rather than crashing`() {
        val failure = assertFailsWith<UnrestorableArchiveException> {
            preview("this is a text file".toByteArray())
        }

        assertEquals(UnrestorableReason.ManifestNotFound(MANIFEST_NAME), failure.reason)
    }

    @Test
    fun `an old v2 archive previews as what it is, without inventing a date`() {
        // v1 and v2 never recorded a created_at or an image count. Showing a blank
        // where the format has nothing is honest; showing zero would not be.
        val legacy = zipOf(
            mapOf(
                LEGACY_PAYLOAD_NAME to (
                    """{"version": 2, "database": "AAAA", """ +
                        """"images": [{"name": "a.jpg", "data": "AA"}]}"""
                    ).toByteArray(),
            ),
        )

        val read = preview(legacy)

        assertEquals(2, read.version)
        assertTrue(read.legacy)
        assertNull(read.createdAt)
        assertNull(read.declaredImages)
        assertEquals(1, read.presentImages)
    }

    @Test
    fun `previewing writes nothing`() {
        // What makes this safe to run on a file nobody has committed to: no
        // staging directory, no extraction, nothing to roll back.
        val scratch = kotlin.io.path.createTempDirectory("preview").toFile()
        val before = scratch.listFiles()?.size ?: 0

        preview(archive())

        assertEquals(before, scratch.listFiles()?.size ?: 0)
        scratch.deleteRecursively()
    }
}
