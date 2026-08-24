package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Installing a backup archive over the live wardrobe.
 *
 * Written against `java.io.File` and `java.util.zip` rather than anything from
 * the Android SDK, which is not a stylistic choice: those are the same APIs
 * Android offers, so the one piece of code in this app that can destroy a
 * wardrobe runs in an ordinary JVM test against a real directory. The rollback
 * path in particular is only worth trusting if it has actually been made to run,
 * and on a device the way to make it run is to break a restore for real.
 *
 * The property being defended is that a failed restore leaves the user exactly
 * where they started -- not with neither their old wardrobe nor a complete new
 * one. Everything is built beside the live data, verified, and only then swapped
 * in by rename; any failure before the swap leaves the wardrobe untouched, and
 * any failure during it is rolled back.
 *
 * The cost is disk: the staged copy exists alongside the live one, so a restore
 * needs roughly the archive's size free. That is the trade the TypeScript makes
 * too, and it buys the only property that matters here.
 */

/** Where the live wardrobe's files are. */
data class WardrobeFiles(
    val databaseFile: File,
    val imagesDir: File,
)

/**
 * Checks that a staged database is readable before it replaces the live one.
 *
 * An interface because this module cannot open a database -- on Android that is
 * SupportSQLite, in the tests JDBC -- while *what* to check is the same either
 * way and lives here, in [checkWardrobeDatabase].
 */
fun interface StagedDatabaseCheck {
    /** Throws if the file is not a usable wardrobe database. */
    fun check(file: File)
}

/**
 * What makes a staged database acceptable.
 *
 * A truncated or garbage file inside an otherwise valid archive would otherwise
 * be installed happily and only fail on the next query -- by which point the
 * original is gone.
 */
fun checkWardrobeDatabase(driver: SqlDriver) {
    val integrity = driver.query("PRAGMA integrity_check;")
        .firstOrNull()?.values?.firstOrNull()?.toString()

    if (integrity != "ok") {
        throw UnrestorableArchiveException(
            UnrestorableReason.IntegrityCheckFailed(integrity ?: "no result")
        )
    }

    // A readable database with no garments table is some other app's file.
    driver.query("SELECT count(*) AS count FROM garments;")
}

/** What an extracted archive turns out to contain. */
enum class ArchiveLayout {
    /** The current layout, and the older folder backups: a `manifest.json`. */
    FOLDER,

    /** The v1/v2 zip, whose database lived base64-encoded inside `backup.json`. */
    LEGACY,

    /**
     * Everything wrapped in a single top-level directory, as some zip
     * implementations produce. The caller descends and classifies again rather
     * than failing.
     */
    NESTED,

    UNKNOWN,
}

/** Work out what an extracted archive contains from the names at one level. */
fun classifyArchiveEntries(fileNames: List<String>, directoryNames: List<String>): ArchiveLayout =
    when {
        MANIFEST_NAME in fileNames -> ArchiveLayout.FOLDER
        LEGACY_PAYLOAD_NAME in fileNames -> ArchiveLayout.LEGACY
        directoryNames.size == 1 -> ArchiveLayout.NESTED
        else -> ArchiveLayout.UNKNOWN
    }

/** Suffixes of the files SQLite keeps beside a database in WAL mode. */
private val SIDECAR_SUFFIXES = listOf("-wal", "-shm")

private const val WORK_DIRNAME = "restore-work"

class ArchiveRestore(
    private val files: WardrobeFiles,
    /** A scratch directory on the same volume as the wardrobe; cache is right. */
    private val workRoot: File,
    private val databaseCheck: StagedDatabaseCheck,
    /**
     * How a staged file is moved into its final place.
     *
     * The one seam in here, and it exists for a specific reason: the rollback is
     * the code that decides whether a failed restore costs someone their
     * wardrobe, and there is no way to make a rename fail on demand from
     * outside. A test that cannot make the swap fail cannot show that it
     * recovers -- and an untested rollback is a rollback nobody should rely on.
     */
    private val move: (File, File) -> Unit = ::renameOrThrow,
) {

    private val databasesDir: File = files.databaseFile.parentFile
        ?: error("The wardrobe database needs a containing directory")

    /**
     * Names used while a restore is in flight.
     *
     * The incoming copies are built beside the files they replace -- same
     * volume, so the final swap is a rename rather than another copy -- and the
     * displaced originals are kept under `.previous` until the swap has fully
     * succeeded.
     */
    private val stagedDatabase = File(databasesDir, "${files.databaseFile.name}.incoming")
    private val previousDatabase = File(databasesDir, "${files.databaseFile.name}.previous")
    private val stagedImages = File(files.imagesDir.parentFile, "${files.imagesDir.name}.incoming")
    private val previousImages = File(files.imagesDir.parentFile, "${files.imagesDir.name}.previous")

    /**
     * Restore from a `.zip` archive.
     *
     * The stream is consumed once, so callers hand over a freshly opened one --
     * on Android, whatever the document picker's `content://` URI resolves to.
     */
    fun restoreFromZip(archive: InputStream) {
        val work = resetDirectory(File(workRoot, WORK_DIRNAME))
        try {
            extractZip(archive, work)

            var root = work
            var layout = classify(root)
            if (layout == ArchiveLayout.NESTED) {
                root = root.directories().single()
                layout = classify(root)
            }

            when (layout) {
                ArchiveLayout.FOLDER -> restoreCurrentFormat(root)
                ArchiveLayout.LEGACY -> restoreLegacyFormat(root)
                else -> throw UnrestorableArchiveException(
                    UnrestorableReason.ManifestNotFound(MANIFEST_NAME)
                )
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Restore from a single legacy `.json` document: everything base64 inside.
     *
     * Read as a string, which is safe for the reason the format could exist at
     * all: the database stores image *paths*, not the images themselves.
     */
    fun restoreFromLegacyJson(document: File) {
        applyLegacyPayload(parseLegacyPayload(document.readText()))
    }

    private fun classify(dir: File): ArchiveLayout =
        classifyArchiveEntries(dir.fileNames(), dir.directoryNames())

    /** The current format: a manifest, the database, and an `images/` folder. */
    private fun restoreCurrentFormat(root: File) {
        val manifest = parseArchiveManifest(File(root, MANIFEST_NAME).readText())

        val database = File(root, ARCHIVE_DB_FILENAME).takeIf { it.isFile }
        val images = File(root, ARCHIVE_IMAGES_DIRNAME).takeIf { it.isDirectory }
        val imageCount = images?.files()?.size ?: 0

        checkArchiveCompleteness(manifest, hasDatabase = database != null, imageCount = imageCount)

        commit(
            writeDatabase = { destination -> relocate(database!!, destination) },
            // One rename for the whole folder rather than a copy per photo: the
            // extracted images *are* the staged images, and a wardrobe can run
            // to hundreds of megabytes.
            writeImages = { destination ->
                if (images == null) destination.mkdirs() else relocate(images, destination)
            },
        )
    }

    /** The v1/v2 zip: `backup.json` with the database inside, photos alongside. */
    private fun restoreLegacyFormat(root: File) {
        val payload = parseLegacyPayload(File(root, LEGACY_PAYLOAD_NAME).readText())
        val images = File(root, ARCHIVE_IMAGES_DIRNAME).takeIf { it.isDirectory }

        applyLegacyPayload(
            payload,
            writeImages = { destination ->
                if (images == null) destination.mkdirs() else relocate(images, destination)
            },
        )
    }

    private fun applyLegacyPayload(
        payload: LegacyPayload,
        writeImages: (File) -> Unit = { destination ->
            destination.mkdirs()
            for (image in payload.images) {
                File(destination, image.name).writeBytes(decodeBase64(image.data))
            }
        },
    ) {
        checkLegacyPayload(payload.version, hasDatabase = payload.database.isNotEmpty())

        commit(
            writeDatabase = { destination -> destination.writeBytes(decodeBase64(payload.database)) },
            writeImages = writeImages,
        )
    }

    /**
     * Build the replacement, verify it, then swap.
     *
     * `writeImages` is handed a path that does not exist rather than an empty
     * directory, so a writer that has a whole folder to move can do it with one
     * rename instead of a copy per file.
     */
    private fun commit(writeDatabase: (File) -> Unit, writeImages: (File) -> Unit) {
        databasesDir.mkdirs()

        // An earlier restore may have died midway; its leftovers are not ours.
        discardStaged()

        try {
            writeDatabase(stagedDatabase)
            if (!stagedDatabase.isFile || stagedDatabase.length() == 0L) {
                throw UnrestorableArchiveException(
                    UnrestorableReason.DatabaseEmpty(ARCHIVE_DB_FILENAME)
                )
            }

            writeImages(stagedImages)

            try {
                databaseCheck.check(stagedDatabase)
            } catch (e: Exception) {
                throw UnrestorableArchiveException(
                    UnrestorableReason.InvalidBackup(detailOf(e))
                )
            } finally {
                // Opening the file may have created sidecars; they must not
                // travel with it into the live slot.
                deleteSidecars(stagedDatabase)
            }
        } catch (e: Throwable) {
            discardStaged()
            throw e
        }

        swapInStaged()
    }

    /**
     * Move the staged data into place, putting everything back if any step fails.
     *
     * Ordering matters: both originals are moved aside before either replacement
     * moves in, so at every intermediate point the user's data still exists
     * under a known name and the rollback can find it.
     */
    private fun swapInStaged() {
        var databaseMovedAside = false
        var imagesMovedAside = false
        var databaseSwappedIn = false

        try {
            // A stale WAL would be replayed onto the *restored* database,
            // grafting fragments of the old wardrobe onto it. Most likely to
            // exist precisely when someone is restoring, because something has
            // already gone wrong.
            deleteSidecars(files.databaseFile)

            if (files.databaseFile.exists()) {
                move(files.databaseFile, previousDatabase)
                databaseMovedAside = true
            }
            if (files.imagesDir.exists()) {
                move(files.imagesDir, previousImages)
                imagesMovedAside = true
            }

            move(stagedDatabase, files.databaseFile)
            databaseSwappedIn = true

            move(stagedImages, files.imagesDir)
        } catch (e: Throwable) {
            try {
                if (databaseSwappedIn) move(files.databaseFile, stagedDatabase)
                if (databaseMovedAside) move(previousDatabase, files.databaseFile)
                if (imagesMovedAside) {
                    files.imagesDir.deleteRecursively()
                    move(previousImages, files.imagesDir)
                }
            } catch (rollbackFailure: Throwable) {
                // Say exactly where the data is rather than pretending it is lost.
                throw UnrestorableArchiveException(
                    UnrestorableReason.RollbackFailed(
                        detail = detailOf(e),
                        rollbackDetail = detailOf(rollbackFailure),
                        databaseName = previousDatabase.name,
                        imagesName = previousImages.name,
                    )
                )
            }

            discardStaged()
            throw UnrestorableArchiveException(UnrestorableReason.RestoreFailed(detailOf(e)))
        }

        // Only now is the displaced copy expendable.
        previousDatabase.delete()
        deleteSidecars(previousDatabase)
        previousImages.deleteRecursively()
    }

    /** Throw away a half-built restore. Safe to call at any point. */
    private fun discardStaged() {
        stagedDatabase.delete()
        deleteSidecars(stagedDatabase)
        stagedImages.deleteRecursively()
    }

    private fun deleteSidecars(database: File) {
        for (suffix in SIDECAR_SUFFIXES) {
            File(database.parentFile, "${database.name}$suffix").delete()
        }
    }
}

/**
 * A v1/v2 payload: the database base64-encoded inside a JSON document.
 *
 * Parsed by hand rather than deserialized into a class because the field that
 * decides whether the file is restorable at all -- `version` -- has to be read
 * out of documents that may be nothing like this shape.
 */
internal class LegacyPayload(
    val version: Int,
    val database: String,
    val images: List<LegacyImage>,
)

internal class LegacyImage(val name: String, val data: String)

private val lenient = Json { ignoreUnknownKeys = true }

internal fun parseLegacyPayload(text: String): LegacyPayload {
    val root = try {
        lenient.parseToJsonElement(text)
    } catch (_: Exception) {
        throw UnrestorableArchiveException(
            UnrestorableReason.ManifestUnreadable(LEGACY_PAYLOAD_NAME)
        )
    }

    if (root !is JsonObject) {
        throw UnrestorableArchiveException(
            UnrestorableReason.ManifestNotABackup(LEGACY_PAYLOAD_NAME)
        )
    }

    val version = (root["version"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.toInt()
        ?: throw UnrestorableArchiveException(
            UnrestorableReason.ManifestVersionMissing(LEGACY_PAYLOAD_NAME)
        )

    val images = (root["images"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val image = entry as? JsonObject ?: return@mapNotNull null
        val name = (image["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val data = (image["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name == null || data == null) null else LegacyImage(name, data)
    }

    return LegacyPayload(
        version = version,
        database = (root["database"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
        images = images,
    )
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

private fun decodeBase64(text: String): ByteArray = try {
    Base64.getMimeDecoder().decode(text)
} catch (_: IllegalArgumentException) {
    throw UnrestorableArchiveException(UnrestorableReason.NotBase64)
}

/**
 * Unpack a zip into a directory.
 *
 * Entry names are checked rather than trusted. An archive arrives from wherever
 * the user got it -- another phone, a download, a messaging app -- and an entry
 * named `../databases/some-other.db` would otherwise write outside the work
 * directory entirely, using a restore to reach the rest of the app's files.
 * Nothing in a real backup needs a path like that, so refusing them costs
 * nothing.
 */
internal fun extractZip(archive: InputStream, destination: File) {
    destination.mkdirs()
    val root = destination.canonicalFile

    ZipInputStream(archive.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val target = File(root, entry.name).canonicalFile

            if (!target.path.startsWith(root.path + File.separator)) {
                throw UnrestorableArchiveException(
                    UnrestorableReason.EntryOutsideArchive(entry.name)
                )
            }

            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> zip.copyTo(out) }
            }
            zip.closeEntry()
        }
    }
}

/** Replace a working directory with an empty one. */
private fun resetDirectory(dir: File): File {
    dir.deleteRecursively()
    dir.mkdirs()
    return dir
}

private fun File.entries(): List<File> = listFiles()?.toList() ?: emptyList()
private fun File.files(): List<File> = entries().filter { it.isFile }
private fun File.directories(): List<File> = entries().filter { it.isDirectory }
private fun File.fileNames(): List<String> = files().map { it.name }
private fun File.directoryNames(): List<String> = directories().map { it.name }

/**
 * Move a file or directory, falling back to a copy across volumes.
 *
 * `renameTo` is the whole reason staging works -- it makes installing a
 * restored photo folder O(1) rather than a second copy of every photo -- but it
 * returns false rather than throwing when it cannot, and it cannot across
 * filesystems. The fallback keeps that from being a failed restore.
 */
private fun relocate(source: File, destination: File) {
    if (source.renameTo(destination)) return

    if (source.isDirectory) {
        source.copyRecursively(destination, overwrite = true)
        source.deleteRecursively()
    } else {
        source.copyTo(destination, overwrite = true)
        source.delete()
    }
}

/** A move that must succeed, used where a silent failure would lose data. */
internal fun renameOrThrow(source: File, destination: File) {
    if (source.renameTo(destination)) return
    throw IllegalStateException("could not move ${source.name} to ${destination.name}")
}

/**
 * What a caught failure was, for a wrapping reason.
 *
 * A failure this module already described keeps its reason, so the whole sentence
 * can be translated -- that is the case where a staged database failed its
 * integrity check. Anything else is someone else's words, kept because they are
 * the only diagnostic there is and marked as untranslatable rather than pretended
 * otherwise.
 */
private fun detailOf(error: Throwable): ArchiveDetail =
    if (error is UnrestorableArchiveException) {
        ArchiveDetail.Known(error.reason)
    } else {
        ArchiveDetail.Foreign(describe(error))
    }

private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
