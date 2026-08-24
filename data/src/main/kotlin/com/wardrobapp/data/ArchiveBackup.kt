package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writing the wardrobe out as a backup archive.
 *
 * The mirror of [ArchiveRestore], and written the same way: `java.io.File` and
 * `java.util.zip` rather than anything from the Android SDK, so the format this
 * app emits is exercised by ordinary JVM tests. The test that matters most is
 * the round trip -- write an archive here, restore it with [ArchiveRestore], and
 * see the wardrobe come back -- because that drives the same validators the
 * app this replaced used, and BackupArchiveTest covers.
 *
 * Why this exists at all: this app could already read the archives written by
 * archives but not write any, which made it a one-way door. Anything done in the
 * port had no way back to the app this replaced.
 */

/** Where a backup's staged pieces are built. */
private const val WORK_DIRNAME = "backup-work"

/** The prefix the React Native app's backup list filters on. */
internal const val BACKUP_PREFIX = "wardrobapp-backup-"

/**
 * The name to offer for a new archive.
 *
 * Deliberately the same shape the React Native app produces, prefix included:
 * its Settings screen lists backups by matching that prefix, so an archive
 * written here into the same folder appears there rather than being invisible to
 * the app that still ships.
 *
 * The colons and dots of an ISO timestamp become dashes because a colon is not a
 * legal filename character on every filesystem an SD card might be formatted as.
 */
fun backupFilename(epochMillis: Long): String =
    BACKUP_PREFIX + isoTimestamp(epochMillis).replace(':', '-').replace('.', '-') + ".zip"

/** What a finished backup turned out to hold. */
data class BackupSummary(
    val bytes: Long,
    val images: Int,
    /** Photos that vanished between being listed and being read. */
    val skipped: Int,
)

class ArchiveBackup(
    private val files: WardrobeFiles,
    /** A scratch directory; cache is right. */
    private val workRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Copy the live database aside, ready to be archived.
     *
     * Separate from [writeArchive] because this is the only step that needs the
     * database connection closed, and holding it closed for the whole archive
     * write -- which is as slow as the wardrobe is large -- would freeze the app
     * for no reason. The caller closes, stages, reopens, and then archives.
     *
     * Closing is also what makes copying the `.db` alone sufficient: SQLite
     * checkpoints the write-ahead log on close, so the sidecars hold nothing the
     * database file does not. It is the same reason [ArchiveRestore] deletes them
     * on the way back in.
     */
    fun stageDatabase(): File {
        val work = resetWork()
        val staged = File(work, ARCHIVE_DB_FILENAME)
        files.databaseFile.copyTo(staged, overwrite = true)
        return staged
    }

    /**
     * Write the archive, closing the destination on every path out of here.
     *
     * Takes a way to open the destination rather than an open one so that there
     * is no path where a stream is opened and then abandoned. On Android the
     * destination is a document the file picker has already created, and an
     * abandoned handle leaves an empty file sitting in the user's Downloads
     * looking like a backup.
     *
     * [onImageCopied] is called with how many photos are done and how many there
     * are, which is the only part slow enough to be worth reporting.
     */
    fun writeArchive(
        openDestination: () -> OutputStream,
        stagedDatabase: File,
        onImageCopied: (copied: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupSummary {
        // Before anything is opened, so a caller that skipped staging has
        // nothing to clean up.
        if (!stagedDatabase.isFile) {
            throw IOException("The database was not staged before archiving.")
        }

        val images = files.imagesDir.listFiles()
            ?.filter { it.isFile }
            // Sorted so two backups of the same wardrobe differ only where the
            // wardrobe does, which makes an archive worth diffing.
            ?.sortedBy { it.name }
            .orEmpty()

        var copied = 0
        var skipped = 0

        // The destination is closed here rather than left to the zip stream,
        // which does not manage it reliably: DeflaterOutputStream.close calls
        // finish() first, and if that throws -- which is exactly what a failing
        // destination does -- it never reaches the close underneath. On Android
        // that leaves the picker's document open and empty.
        return openDestination().use { destination ->
            val counting = CountingOutputStream(destination)

            ZipOutputStream(counting).use { zip ->
                // The React Native app zips without compressing, on the grounds
                // that a JPEG does not compress twice. Set on the stream rather
                // than per entry: ZipEntry.STORED would be the literal
                // equivalent, but it requires the size and CRC of every entry to
                // be known before the entry is opened, which means buffering each
                // photo in memory to learn what the filesystem already knows.
                zip.setLevel(Deflater.NO_COMPRESSION)

                zip.putNextEntry(ZipEntry(ARCHIVE_DB_FILENAME))
                stagedDatabase.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                onImageCopied(0, images.size)
                for (image in images) {
                    // Opened before the entry is created: a photo deleted between
                    // being listed and being read is ordinary -- the wardrobe is
                    // live -- and skipping it leaves the archive valid. A failure
                    // part way through a photo is not ordinary, and is left to
                    // propagate rather than committing a truncated file.
                    val source = try {
                        image.inputStream()
                    } catch (_: IOException) {
                        skipped++
                        continue
                    }
                    source.use {
                        zip.putNextEntry(ZipEntry("$ARCHIVE_IMAGES_DIRNAME/${image.name}"))
                        it.copyTo(zip)
                        zip.closeEntry()
                    }
                    copied++
                    onImageCopied(copied, images.size)
                }

                // Last, because it has to state how many photos are actually in
                // here, and that is not known until they are.
                // checkArchiveCompleteness rejects an archive holding fewer than
                // its manifest claims, so a count taken up front would turn every
                // skipped photo into an archive that refuses to restore.
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest(copied).toByteArray())
                zip.closeEntry()
            }

            BackupSummary(bytes = counting.written, images = copied, skipped = skipped)
        }
    }

    /** Drop the staged copy. Safe to call whether or not anything was staged. */
    fun discardStaging() {
        File(workRoot, WORK_DIRNAME).deleteRecursively()
    }

    private fun manifest(imageCount: Int): String {
        val fields = JsonObject(
            mapOf(
                "version" to JsonPrimitive(BACKUP_VERSION),
                "created_at" to JsonPrimitive(isoTimestamp(now())),
                "image_count" to JsonPrimitive(imageCount),
            )
        )
        return Json.encodeToString(JsonObject.serializer(), fields)
    }

    private fun resetWork(): File = File(workRoot, WORK_DIRNAME).apply {
        deleteRecursively()
        mkdirs()
    }
}

/**
 * Counts what goes through it.
 *
 * The destination is a `content://` stream on Android, which cannot be asked how
 * big it turned out; and the size is worth reporting, because "backup saved" with
 * no number is indistinguishable from having saved nothing.
 */
private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
    var written: Long = 0
        private set

    override fun write(b: Int) {
        target.write(b)
        written += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target.write(b, off, len)
        written += len
    }

    override fun flush() = target.flush()

    // Deliberately not closing the target: writeArchive owns it, for the reason
    // documented there.
    override fun close() = flush()
}
