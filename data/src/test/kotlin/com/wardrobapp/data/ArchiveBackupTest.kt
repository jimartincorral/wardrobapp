package com.wardrobapp.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.sql.DriverManager
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Writing the wardrobe out as an archive.
 *
 * The centrepiece is the round trip: write an archive here, then restore it with
 * the real [ArchiveRestore] into an empty tree and check the wardrobe arrives.
 * That is worth more than asserting the bytes of the format, because it drives
 * `classifyArchiveEntries`, `parseArchiveManifest` and `checkArchiveCompleteness`
 * -- the three validators the app this replaced used, which BackupArchiveTest
 * to the TypeScript. An archive that survives them is one the shipping app has
 * no reason to refuse.
 *
 * It is still not proof that the shipping app accepts it: only a phone with both
 * apps installed can show that.
 */
class ArchiveBackupTest {

    private val root: File = File.createTempFile("backup-test", "").let { placeholder ->
        placeholder.delete()
        placeholder.mkdirs()
        placeholder
    }

    private val databasesDir = File(root, "databases").apply { mkdirs() }
    private val documentsDir = File(root, "files").apply { mkdirs() }
    private val workDir = File(root, "cache").apply { mkdirs() }

    private val liveDatabase = File(databasesDir, ARCHIVE_DB_FILENAME)
    private val liveImages = File(documentsDir, GARMENT_IMAGE_DIRNAME)

    private val files = WardrobeFiles(databaseFile = liveDatabase, imagesDir = liveImages)

    /** A fixed clock, so the manifest and the filename are assertable. */
    private val clock = 1_774_000_000_000L

    private fun backup() = ArchiveBackup(files, workDir, now = { clock })

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    // ---- a wardrobe to back up ----------------------------------------------

    private fun givenWardrobe(
        garmentIds: List<String> = listOf("garment-1"),
        photos: List<String> = listOf("photo-1.jpg", "photo-2.jpg"),
    ) {
        writeWardrobeDatabase(liveDatabase, garmentIds)
        liveImages.mkdirs()
        for (photo in photos) File(liveImages, photo).writeText("bytes of $photo")
    }

    private fun writeWardrobeDatabase(target: File, garmentIds: List<String>) {
        target.parentFile.mkdirs()
        target.delete()
        openFileDriver(target).use { driver ->
            WardrobeSchema.applyTo(driver)
            for (id in garmentIds) {
                driver.execute(
                    "INSERT INTO garments (id, image_uri, category, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    listOf(id, "$id.jpg", "tops", "2026-01-01", "2026-01-01"),
                )
            }
        }
    }

    private fun openFileDriver(target: File) =
        JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}"))

    /** Write an archive of the live wardrobe the way the app would. */
    private fun archiveBytes(
        onImageCopied: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<ByteArray, BackupSummary> {
        val backup = backup()
        val staged = backup.stageDatabase()
        val out = ByteArrayOutputStream()
        val summary = backup.writeArchive({ out }, staged, onImageCopied)
        backup.discardStaging()
        return out.toByteArray() to summary
    }

    private fun entriesOf(archive: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    // ---- the round trip -----------------------------------------------------

    @Test
    fun `an archive it writes is one the restorer accepts`() {
        givenWardrobe(garmentIds = listOf("kept-garment"), photos = listOf("kept.jpg"))
        val (archive, _) = archiveBytes()

        // A different wardrobe entirely, so nothing can pass by having been
        // there already.
        writeWardrobeDatabase(liveDatabase, listOf("about-to-be-replaced"))
        liveImages.deleteRecursively()
        liveImages.mkdirs()
        File(liveImages, "stale.jpg").writeText("stale")

        ArchiveRestore(
            files,
            workDir,
            StagedDatabaseCheck { file -> openFileDriver(file).use { checkWardrobeDatabase(it) } },
        ).restoreFromZip(ByteArrayInputStream(archive))

        val restored = openFileDriver(liveDatabase).use { driver ->
            GarmentQueries(driver, "file:///photos/").allGarments().map { it.id }
        }
        assertEquals(listOf("kept-garment"), restored, "the archived garment did not come back")
        assertEquals(listOf("kept.jpg"), liveImages.list()?.sorted(), "the archived photo did not come back")
    }

    @Test
    fun `a wardrobe with no photos still round-trips`() {
        givenWardrobe(garmentIds = listOf("photoless"), photos = emptyList())
        val (archive, summary) = archiveBytes()
        assertEquals(0, summary.images)

        liveDatabase.delete()
        liveImages.deleteRecursively()

        ArchiveRestore(
            files,
            workDir,
            StagedDatabaseCheck { file -> openFileDriver(file).use { checkWardrobeDatabase(it) } },
        ).restoreFromZip(ByteArrayInputStream(archive))

        val restored = openFileDriver(liveDatabase).use { driver ->
            GarmentQueries(driver, "file:///photos/").allGarments().map { it.id }
        }
        assertEquals(listOf("photoless"), restored)
    }

    // ---- what the archive contains -------------------------------------------

    @Test
    fun `it holds the manifest, the database and every photo`() {
        givenWardrobe(photos = listOf("a.jpg", "b.jpg"))
        val (archive, summary) = archiveBytes()

        val entries = entriesOf(archive)
        assertEquals(
            listOf(
                ARCHIVE_DB_FILENAME,
                MANIFEST_NAME,
                "$ARCHIVE_IMAGES_DIRNAME/a.jpg",
                "$ARCHIVE_IMAGES_DIRNAME/b.jpg",
            ).sorted(),
            entries.keys.sorted(),
        )
        assertEquals("bytes of a.jpg", entries.getValue("$ARCHIVE_IMAGES_DIRNAME/a.jpg").decodeToString())
        assertEquals(2, summary.images)
        assertEquals(0, summary.skipped)
    }

    @Test
    fun `the manifest is one the validators accept`() {
        givenWardrobe(photos = listOf("a.jpg", "b.jpg", "c.jpg"))
        val (archive, _) = archiveBytes()

        val manifest = parseArchiveManifest(entriesOf(archive).getValue(MANIFEST_NAME).decodeToString())

        assertEquals(BACKUP_VERSION, manifest.version)
        assertEquals(3, manifest.imageCount)
        assertEquals(isoTimestamp(clock), manifest.createdAt)
        // The check the restorer runs, so a manifest this writer produces cannot
        // fail the completeness rule it is subject to.
        checkArchiveCompleteness(manifest, hasDatabase = true, imageCount = 3)
    }

    @Test
    fun `the manifest counts the photos that made it in, not the ones it found`() {
        givenWardrobe(photos = listOf("a.jpg", "vanishes.jpg", "c.jpg"))

        val backup = backup()
        val staged = backup.stageDatabase()
        // A photo removed between being listed and being read -- the wardrobe is
        // live while this runs, so this is ordinary rather than exceptional.
        val vanishing = File(liveImages, "vanishes.jpg")
        val out = ByteArrayOutputStream()
        val summary = backup.writeArchive({ out }, staged) { copied, _ ->
            if (copied == 1) vanishing.delete()
        }

        assertEquals(2, summary.images)
        assertEquals(1, summary.skipped)

        val manifest = parseArchiveManifest(
            entriesOf(out.toByteArray()).getValue(MANIFEST_NAME).decodeToString()
        )
        assertEquals(2, manifest.imageCount, "the manifest over-claimed")
        // The point of counting what was copied: had it claimed 3, this would
        // throw and the archive would be unrestorable.
        checkArchiveCompleteness(manifest, hasDatabase = true, imageCount = 2)
    }

    @Test
    fun `the manifest is written last, after the photos it counts`() {
        givenWardrobe(photos = listOf("b.jpg", "a.jpg"))
        val (archive, _) = archiveBytes()

        val order = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                order += entry.name
                zip.closeEntry()
            }
        }

        assertEquals(
            listOf(
                ARCHIVE_DB_FILENAME,
                "$ARCHIVE_IMAGES_DIRNAME/a.jpg",
                "$ARCHIVE_IMAGES_DIRNAME/b.jpg",
                MANIFEST_NAME,
            ),
            order,
            "the manifest has to come after the photos, since it states how many there are",
        )
    }

    @Test
    fun `it reports the bytes it wrote`() {
        givenWardrobe(photos = listOf("a.jpg"))
        val (archive, summary) = archiveBytes()
        assertEquals(archive.size.toLong(), summary.bytes)
    }

    @Test
    fun `it reports progress over the photos`() {
        givenWardrobe(photos = listOf("a.jpg", "b.jpg", "c.jpg"))
        val seen = mutableListOf<Pair<Int, Int>>()
        archiveBytes { copied, total -> seen += copied to total }
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun `a subdirectory among the photos is not archived`() {
        givenWardrobe(photos = listOf("a.jpg"))
        File(liveImages, "thumbnails").mkdirs()

        val progress = mutableListOf<Pair<Int, Int>>()
        val (archive, summary) = archiveBytes { copied, total -> progress += copied to total }

        assertEquals(1, summary.images)
        assertEquals(
            listOf("$ARCHIVE_IMAGES_DIRNAME/a.jpg"),
            entriesOf(archive).keys.filter { it.startsWith(ARCHIVE_IMAGES_DIRNAME) },
        )
        // Not just absent from the archive: it must not be counted either. A
        // directory that failed to open like an unreadable photo would report a
        // photo lost and inflate the total the progress bar counts towards.
        assertEquals(0, summary.skipped, "the subdirectory was reported as a lost photo")
        assertEquals(listOf(0 to 1, 1 to 1), progress)
    }

    @Test
    fun `archiving without staging first refuses rather than writing a databaseless archive`() {
        givenWardrobe()
        val error = assertFailsWith<IOException> {
            // Named without the word this asserts on: a missing file throws an
            // IOException of its own, so a filename containing "staged" would
            // satisfy the assertion whether the guard exists or not.
            backup().writeArchive({ ByteArrayOutputStream() }, File(workDir, "absent.db"))
        }
        assertContains(error.message ?: "", "staged")
    }

    // ---- the destination's lifetime -----------------------------------------

    /** An output stream that can be asked to fail, and remembers being closed. */
    private class FlakyStream(private val failAfterBytes: Int = Int.MAX_VALUE) : OutputStream() {
        var closed = false
            private set
        private var written = 0

        override fun write(b: Int) {
            written += 1
            if (written > failAfterBytes) throw IOException("the card was removed")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written += len
            if (written > failAfterBytes) throw IOException("the card was removed")
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `it does not open the destination when there is nothing to write`() {
        givenWardrobe()
        var opened = false

        assertFailsWith<IOException> {
            backup().writeArchive(
                { opened = true; ByteArrayOutputStream() },
                File(workDir, "absent.db"),
            )
        }

        // The destination on Android is a document the picker has already
        // created. Opening one this is not going to write would leave an empty
        // file behind looking like a backup.
        assertFalse(opened, "the destination was opened for a write that could not happen")
    }

    @Test
    fun `it closes the destination when it finishes`() {
        givenWardrobe(photos = listOf("a.jpg"))
        val destination = FlakyStream()

        val backup = backup()
        backup.writeArchive({ destination }, backup.stageDatabase())

        assertTrue(destination.closed, "the destination was left open")
    }

    @Test
    fun `it closes the destination when the write fails part way`() {
        givenWardrobe(photos = listOf("a.jpg", "b.jpg", "c.jpg"))
        val destination = FlakyStream(failAfterBytes = 64)

        val backup = backup()
        val staged = backup.stageDatabase()
        assertFailsWith<IOException> { backup.writeArchive({ destination }, staged) }

        assertTrue(destination.closed, "a failed write left the destination open")
    }

    // ---- staging ------------------------------------------------------------

    @Test
    fun `staging copies the database rather than moving it`() {
        givenWardrobe()
        val before = liveDatabase.readBytes()

        val staged = backup().stageDatabase()

        assertTrue(staged.isFile)
        assertEquals(before.toList(), staged.readBytes().toList())
        assertEquals(before.toList(), liveDatabase.readBytes().toList(), "the live database moved")
    }

    @Test
    fun `discarding staging leaves nothing behind`() {
        givenWardrobe()
        val backup = backup()
        val staged = backup.stageDatabase()
        assertTrue(staged.isFile)

        backup.discardStaging()

        assertTrue(!staged.exists(), "the staged database survived")
        assertTrue(workDir.list()?.none { it.startsWith("backup-work") } ?: true)
    }

    @Test
    fun `discarding staging is safe when nothing was staged`() {
        backup().discardStaging()
    }

    @Test
    fun `staging twice starts from a clean directory`() {
        givenWardrobe()
        val backup = backup()
        val first = backup.stageDatabase()
        File(first.parentFile, "left-over.tmp").writeText("junk")

        backup.stageDatabase()

        assertEquals(
            listOf(ARCHIVE_DB_FILENAME),
            first.parentFile.list()?.sorted(),
            "the previous attempt's leftovers were archived",
        )
    }

    // ---- the filename -------------------------------------------------------

    @Test
    fun `the filename carries the prefix the shipping app lists on`() {
        val name = backupFilename(clock)
        assertTrue(name.startsWith("wardrobapp-backup-"), name)
        assertTrue(name.endsWith(".zip"), name)
    }

    @Test
    fun `the filename holds no character a filesystem might refuse`() {
        val name = backupFilename(clock)
        assertTrue(name.none { it in ":*?\"<>|/\\" }, name)
        // The timestamp is still legible, which is the reason for having it.
        assertContains(name, isoTimestamp(clock).substringBefore('T'))
    }

    @Test
    fun `later backups sort after earlier ones`() {
        val earlier = backupFilename(clock)
        val later = backupFilename(clock + 60_000)
        assertTrue(earlier < later, "$earlier should sort before $later")
    }
}
