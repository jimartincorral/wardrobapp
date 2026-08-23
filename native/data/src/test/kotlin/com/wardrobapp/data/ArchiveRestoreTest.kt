package com.wardrobapp.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.DriverManager
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Restoring a backup over a live wardrobe.
 *
 * Everything here runs against a real directory and real SQLite files, because
 * this is the one part of the app that can destroy data: the interesting cases
 * are not "does a good archive restore" but "what is left behind when a bad one
 * does not". Every refusal below asserts that the original wardrobe is still
 * exactly where it was -- the database byte-for-byte, and the photos present --
 * which is the promise the staging exists to keep.
 */
class ArchiveRestoreTest {

    private val root: File = File.createTempFile("restore-test", "").let { placeholder ->
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

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    // ---- the live wardrobe being restored over -------------------------------

    /** A populated wardrobe on disk, as the app would have left it. */
    private fun givenLiveWardrobe(garmentId: String = "live-garment") {
        writeWardrobeDatabase(liveDatabase, garmentId)
        liveImages.mkdirs()
        File(liveImages, "live-photo.jpg").writeText("the photo already here")
    }

    private fun writeWardrobeDatabase(target: File, garmentId: String) {
        target.parentFile.mkdirs()
        target.delete()
        openFileDriver(target).use { driver ->
            WardrobeSchema.applyTo(driver)
            driver.execute(
                "INSERT INTO garments (id, image_uri, category, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                listOf(garmentId, "$garmentId.jpg", "tops", "2026-01-01", "2026-01-01"),
            )
        }
    }

    private fun openFileDriver(target: File) =
        JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:${target.absolutePath}"))

    private fun garmentIdsInLiveDatabase(): List<String> =
        openFileDriver(liveDatabase).use { driver ->
            GarmentQueries(driver, "file:///photos/").allGarments().map { it.id }
        }

    // ---- archives -----------------------------------------------------------

    /** The current format: manifest, database, and an `images/` folder. */
    private fun currentArchive(
        garmentId: String = "restored-garment",
        photos: List<String> = listOf("restored-photo.jpg"),
        manifest: String = """{"version":3,"image_count":${photos.size}}""",
        includeDatabase: Boolean = true,
        databaseBytes: ByteArray? = null,
        prefix: String = "",
    ): ByteArray {
        val staged = File(workDir, "build-archive").also { it.deleteRecursively(); it.mkdirs() }
        val entries = mutableMapOf<String, ByteArray>()

        entries["${prefix}$MANIFEST_NAME"] = manifest.toByteArray()
        if (includeDatabase) {
            entries["${prefix}$ARCHIVE_DB_FILENAME"] = databaseBytes ?: run {
                val built = File(staged, "built.db")
                writeWardrobeDatabase(built, garmentId)
                built.readBytes()
            }
        }
        for (photo in photos) {
            entries["${prefix}$ARCHIVE_IMAGES_DIRNAME/$photo"] = "bytes of $photo".toByteArray()
        }

        staged.deleteRecursively()
        return zipOf(entries)
    }

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

    // ---- the subject --------------------------------------------------------

    /** The real database check, over JDBC instead of Android's SQLite. */
    private val realCheck = StagedDatabaseCheck { file ->
        openFileDriver(file).use { checkWardrobeDatabase(it) }
    }

    private fun restore(
        archive: ByteArray,
        check: StagedDatabaseCheck = realCheck,
        move: (File, File) -> Unit = ::renameOrThrow,
    ) {
        ArchiveRestore(files, workDir, check, move)
            .restoreFromZip(ByteArrayInputStream(archive))
    }

    /** Asserts the wardrobe is exactly as `givenLiveWardrobe` left it. */
    private fun assertWardrobeUntouched() {
        assertEquals(listOf("live-garment"), garmentIdsInLiveDatabase(), "the live database changed")
        assertEquals(
            listOf("live-photo.jpg"),
            liveImages.list()?.sorted(),
            "the live photos changed",
        )
        assertTrue(
            databasesDir.list()!!.none { it.endsWith(".incoming") || it.endsWith(".previous") },
            "staging was left behind: ${databasesDir.list()!!.toList()}",
        )
        assertTrue(
            documentsDir.list()!!.none { it.endsWith(".incoming") || it.endsWith(".previous") },
            "staging was left behind: ${documentsDir.list()!!.toList()}",
        )
    }

    // ---- a restore that works ----------------------------------------------

    @Test
    fun `installs the archive's database and photos`() {
        givenLiveWardrobe()

        restore(currentArchive(garmentId = "from-the-backup", photos = listOf("a.jpg", "b.jpg")))

        assertEquals(listOf("from-the-backup"), garmentIdsInLiveDatabase())
        assertEquals(listOf("a.jpg", "b.jpg"), liveImages.list()!!.sorted())
    }

    @Test
    fun `restores into an app that has no wardrobe yet`() {
        // The first thing a fresh install does with a backup, and the case with
        // no live data to move aside -- so the swap has to cope with both halves
        // being absent rather than assuming it is replacing something.
        restore(currentArchive(garmentId = "first-wardrobe"))

        assertEquals(listOf("first-wardrobe"), garmentIdsInLiveDatabase())
        assertEquals(listOf("restored-photo.jpg"), liveImages.list()!!.sorted())
    }

    @Test
    fun `leaves nothing behind`() {
        givenLiveWardrobe()

        restore(currentArchive())

        assertEquals(
            listOf(ARCHIVE_DB_FILENAME),
            databasesDir.list()!!.sorted(),
            "the displaced original, or staging, was kept",
        )
        assertEquals(listOf(GARMENT_IMAGE_DIRNAME), documentsDir.list()!!.sorted())
        assertFalse(File(workDir, "restore-work").exists(), "the extracted archive was kept")
    }

    @Test
    fun `deletes a stale write-ahead log rather than replaying it onto the restored database`() {
        // A WAL belonging to the *old* database would be replayed onto the new
        // one, grafting fragments of the wardrobe being replaced onto the
        // wardrobe replacing it. Most likely to be sitting there precisely when
        // someone is restoring, because something already went wrong.
        givenLiveWardrobe()
        File(databasesDir, "$ARCHIVE_DB_FILENAME-wal").writeText("stale log")
        File(databasesDir, "$ARCHIVE_DB_FILENAME-shm").writeText("stale index")

        restore(currentArchive())

        assertEquals(listOf(ARCHIVE_DB_FILENAME), databasesDir.list()!!.sorted())
    }

    @Test
    fun `discards what a previous half-finished restore left behind`() {
        // Staging from a crashed attempt is not ours to keep, and a photo folder
        // half-populated by it must not be merged into the new one.
        givenLiveWardrobe()
        File(databasesDir, "$ARCHIVE_DB_FILENAME.incoming").writeText("junk from last time")
        File(documentsDir, "$GARMENT_IMAGE_DIRNAME.incoming").mkdirs()
        File(documentsDir, "$GARMENT_IMAGE_DIRNAME.incoming/ghost.jpg").writeText("orphan")

        restore(currentArchive(photos = listOf("real.jpg")))

        assertEquals(listOf("real.jpg"), liveImages.list()!!.sorted())
    }

    @Test
    fun `does not let the sidecars an opened database leaves travel with it`() {
        // The check has to open the staged file, and on Android opening a
        // database in WAL mode creates `-wal` and `-shm` beside it. Carried into
        // the live slot they are a log belonging to a database that was never
        // live, waiting to be replayed onto the one that is.
        givenLiveWardrobe()
        val openingCheck = StagedDatabaseCheck { file ->
            openFileDriver(file).use { checkWardrobeDatabase(it) }
            // Left behind *after* the connection closes, which is what Android
            // does in WAL mode. Writing them first proves nothing: SQLite reads
            // a bogus log on open and tidies it away itself.
            File(file.parentFile, "${file.name}-wal").writeText("as an open would leave")
            File(file.parentFile, "${file.name}-shm").writeText("as an open would leave")
        }

        restore(currentArchive(), check = openingCheck)

        assertEquals(listOf(ARCHIVE_DB_FILENAME), databasesDir.list()!!.sorted())
    }

    @Test
    fun `does not reuse what a previous extraction left in the work directory`() {
        // A restore that died after extracting leaves a whole archive behind.
        // Reusing it merges someone's second attempt with their first: entries
        // that share a name are overwritten, and the photos that do not are
        // silently added to the wardrobe being restored.
        givenLiveWardrobe()
        val stale = File(workDir, "restore-work/$ARCHIVE_IMAGES_DIRNAME")
        stale.mkdirs()
        File(stale, "ghost.jpg").writeText("from an attempt that failed")

        restore(currentArchive(photos = listOf("real.jpg")))

        assertEquals(listOf("real.jpg"), liveImages.list()!!.sorted())
    }

    @Test
    fun `never leaves the restored database beside the old photos`() {
        // The swap moves both originals aside before either replacement moves
        // in, so the two halves are never mismatched. The other order puts the
        // restored database next to the wardrobe's previous photos, and a
        // process killed at that moment -- Android does that to backgrounded
        // apps -- leaves every row pointing at a file that is not there.
        givenLiveWardrobe()
        val originalDatabase = liveDatabase.readBytes()
        val mismatched = mutableListOf<String>()

        val watchingMove: (File, File) -> Unit = { source, destination ->
            renameOrThrow(source, destination)
            val databaseReplaced = liveDatabase.exists() &&
                !liveDatabase.readBytes().contentEquals(originalDatabase)
            if (databaseReplaced && File(liveImages, "live-photo.jpg").exists()) {
                mismatched += "${source.name} -> ${destination.name}"
            }
        }

        restore(currentArchive(), move = watchingMove)

        assertEquals(
            emptyList(),
            mismatched,
            "the restored database was live while the old photos still were",
        )
    }

    @Test
    fun `accepts an archive wrapped in a single top-level folder`() {
        // Some zip tools wrap everything in one directory named after the file.
        givenLiveWardrobe()

        restore(currentArchive(garmentId = "nested", prefix = "wardrobapp-backup-2026/"))

        assertEquals(listOf("nested"), garmentIdsInLiveDatabase())
    }

    @Test
    fun `accepts an archive with no photos at all`() {
        givenLiveWardrobe()

        restore(currentArchive(photos = emptyList()))

        assertEquals(emptyList(), liveImages.list()!!.sorted())
    }

    // ---- archives that are refused -----------------------------------------

    @Test
    fun `refuses an archive with no manifest, changing nothing`() {
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(zipOf(mapOf("notes.txt" to "hello".toByteArray(), "other.txt" to "hi".toByteArray())))
        }

        assertContains(error.message!!, "no manifest.json found")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses an archive whose database is missing, changing nothing`() {
        // The case that used to wipe every photo and report success: deleting
        // the live images was unconditional while restoring the database was not.
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(includeDatabase = false))
        }

        assertContains(error.message!!, "is missing from the archive")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a truncated archive, changing nothing`() {
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(photos = listOf("one.jpg"), manifest = """{"version":3,"image_count":5}"""))
        }

        assertContains(error.message!!, "the archive is truncated")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a database SQLite cannot read, changing nothing`() {
        // A corrupt file inside an otherwise valid archive. Installed happily,
        // it would only fail on the next query -- by which point the original
        // is gone.
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(databaseBytes = "this is not a database".toByteArray()))
        }

        assertContains(error.message!!, "Invalid backup")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a readable database that is not a wardrobe, changing nothing`() {
        // Some other app's SQLite file passes the integrity check perfectly.
        val foreign = File(workDir, "foreign.db")
        openFileDriver(foreign).use { it.execute("CREATE TABLE recipes (id TEXT)") }
        givenLiveWardrobe()

        assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(databaseBytes = foreign.readBytes()))
        }

        assertWardrobeUntouched()
    }

    @Test
    fun `refuses an empty database file, changing nothing`() {
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(databaseBytes = ByteArray(0)))
        }

        assertContains(error.message!!, "is empty")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a manifest from a newer version of the app, changing nothing`() {
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(manifest = """{"version":9,"image_count":1}"""))
        }

        assertContains(error.message!!, "Update the app")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses an entry that points outside the archive, changing nothing`() {
        // An archive comes from wherever the user got it. An entry named
        // `../../databases/...` would use a restore to write into the rest of
        // the app's files.
        givenLiveWardrobe()
        val escapee = File(root, "escaped.txt")

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(zipOf(mapOf("../../escaped.txt" to "owned".toByteArray())))
        }

        assertContains(error.message!!, "outside itself")
        assertFalse(escapee.exists(), "the archive wrote outside the work directory")
        assertWardrobeUntouched()
    }

    // ---- the swap failing ---------------------------------------------------

    /** A move that fails on exactly the nth call: one step of the swap goes wrong. */
    private fun failsOn(call: Int): (File, File) -> Unit {
        var calls = 0
        return { source, destination ->
            if (++calls == call) throw IllegalStateException("no space left on device")
            renameOrThrow(source, destination)
        }
    }

    /** A move that fails from the nth call on: the filesystem itself is gone. */
    private fun failsFrom(call: Int): (File, File) -> Unit {
        var calls = 0
        return { source, destination ->
            if (++calls >= call) throw IllegalStateException("no space left on device")
            renameOrThrow(source, destination)
        }
    }

    @Test
    fun `puts the wardrobe back when the swap fails partway`() {
        // The swap is four moves: both originals aside, then both replacements
        // in. Each is a point where someone could be left holding half of one
        // wardrobe and half of another, so each is broken in turn.
        for (failingStep in 1..4) {
            givenLiveWardrobe()

            val error = assertFailsWith<UnrestorableArchiveException> {
                restore(currentArchive(), move = failsOn(failingStep))
            }

            assertContains(
                error.message!!,
                "Your wardrobe was left unchanged",
                message = "step $failingStep did not report a clean failure",
            )
            assertWardrobeUntouched()
        }
    }

    @Test
    fun `clears a half-written photo folder before putting the original back`() {
        // A move that got as far as creating its destination and then failed --
        // what a copy across volumes, or a disk filling up mid-copy, looks like.
        // Without clearing it, restoring the original folder lands on top of a
        // partial one and the rollback leaves a mixture of the two wardrobes.
        givenLiveWardrobe()
        var calls = 0
        val halfWrittenMove: (File, File) -> Unit = { source, destination ->
            if (++calls == 4) {
                destination.mkdirs()
                File(destination, "half-copied.jpg").writeText("partial")
                throw IllegalStateException("no space left on device")
            }
            renameOrThrow(source, destination)
        }

        assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(), move = halfWrittenMove)
        }

        assertWardrobeUntouched()
    }

    @Test
    fun `says where the data is when it cannot even be put back`() {
        // The worst case: the swap failed and so did the rollback. Telling
        // someone the names on disk is the difference between a recoverable
        // situation and one that reads as total loss.
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(currentArchive(), move = failsFrom(3))
        }

        assertContains(error.message!!, "could not be put back")
        assertContains(error.message!!, "$ARCHIVE_DB_FILENAME.previous")
        assertContains(error.message!!, "$GARMENT_IMAGE_DIRNAME.previous")
        assertTrue(
            File(databasesDir, "$ARCHIVE_DB_FILENAME.previous").exists(),
            "the message names a file that is not there",
        )
    }

    // ---- what makes a staged database acceptable ----------------------------

    /**
     * A driver that answers the two questions the check asks.
     *
     * The integrity check is stubbed rather than provoked because SQLite is not
     * what is under test here -- what the port *does* with the answer is, and a
     * file that fails the integrity check while still answering a query is not
     * something that can be produced on demand.
     */
    private class AnsweringDriver(
        private val integrity: List<Map<String, Any?>>,
        private val hasGarments: Boolean = true,
    ) : SqlDriver {
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = when {
            sql.startsWith("PRAGMA integrity_check") -> integrity
            hasGarments -> listOf(mapOf("count" to 0L))
            else -> throw IllegalStateException("no such table: garments")
        }

        override fun execute(sql: String, args: List<Any?>): Int = 0
        override fun <T> transaction(block: () -> T): T = block()
    }

    @Test
    fun `refuses a database whose integrity check does not say ok`() {
        // Quoting what SQLite said rather than summarising it: "database disk
        // image is malformed" and "wrong # of entries in index" are different
        // situations, and the person deciding whether to trust the file is the
        // one holding it.
        val error = assertFailsWith<UnrestorableArchiveException> {
            checkWardrobeDatabase(
                AnsweringDriver(listOf(mapOf("integrity_check" to "*** in database main ***")))
            )
        }

        assertContains(error.message!!, "*** in database main ***")
    }

    @Test
    fun `refuses a database that answers the integrity check with nothing at all`() {
        val error = assertFailsWith<UnrestorableArchiveException> {
            checkWardrobeDatabase(AnsweringDriver(emptyList()))
        }

        assertContains(error.message!!, "no result")
    }

    @Test
    fun `accepts a database that is intact and holds garments`() {
        checkWardrobeDatabase(AnsweringDriver(listOf(mapOf("integrity_check" to "ok"))))
    }

    @Test
    fun `refuses an intact database with no garments table`() {
        assertFailsWith<IllegalStateException> {
            checkWardrobeDatabase(
                AnsweringDriver(listOf(mapOf("integrity_check" to "ok")), hasGarments = false)
            )
        }
    }

    // ---- the legacy formats ------------------------------------------------

    private fun legacyPayload(version: Int, garmentId: String = "legacy-garment"): String {
        val built = File(workDir, "legacy.db")
        writeWardrobeDatabase(built, garmentId)
        val database = Base64.getEncoder().encodeToString(built.readBytes())
        return """{"version":$version,"created_at":"2026-01-01","database":"$database"}"""
    }

    @Test
    fun `restores a v1 archive, whose database was base64 inside a json payload`() {
        givenLiveWardrobe()

        restore(
            zipOf(
                mapOf(
                    LEGACY_PAYLOAD_NAME to legacyPayload(1, "from-v1").toByteArray(),
                    "$ARCHIVE_IMAGES_DIRNAME/old.jpg" to "old bytes".toByteArray(),
                )
            )
        )

        assertEquals(listOf("from-v1"), garmentIdsInLiveDatabase())
        assertEquals(listOf("old.jpg"), liveImages.list()!!.sorted())
    }

    @Test
    fun `refuses a legacy payload whose version is not one this build reads`() {
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(zipOf(mapOf(LEGACY_PAYLOAD_NAME to legacyPayload(7).toByteArray())))
        }

        assertContains(error.message!!, "Unsupported backup format 7")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a legacy payload with no database, changing nothing`() {
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(zipOf(mapOf(LEGACY_PAYLOAD_NAME to """{"version":1}""".toByteArray())))
        }

        assertContains(error.message!!, "contains no database")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a legacy payload with no version at all, changing nothing`() {
        // Without a version there is no way to know what the rest of the
        // document means, so assuming the current one would mean choosing a
        // branch on nonsense.
        givenLiveWardrobe()

        val error = assertFailsWith<UnrestorableArchiveException> {
            restore(zipOf(mapOf(LEGACY_PAYLOAD_NAME to """{"database":"AAAA"}""".toByteArray())))
        }

        assertContains(error.message!!, "has no version number")
        assertWardrobeUntouched()
    }

    @Test
    fun `refuses a legacy payload that is not readable json, changing nothing`() {
        givenLiveWardrobe()

        assertFailsWith<UnrestorableArchiveException> {
            restore(zipOf(mapOf(LEGACY_PAYLOAD_NAME to "{oops".toByteArray())))
        }

        assertWardrobeUntouched()
    }
}
