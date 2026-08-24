package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * Deciding whether a backup archive can be restored.
 *
 * Pure, and called before anything is overwritten, so every rejection here is
 * free: the wardrobe is still untouched. That is the whole point of separating
 * it from the extraction and swapping, which are the parts that can do damage.
 */

/** The format this build writes and reads. */
const val BACKUP_VERSION = 3

/** Formats this build can still read, beyond the current one. */
val LEGACY_BACKUP_VERSIONS = listOf(1, 2)

const val MANIFEST_NAME = "manifest.json"
const val LEGACY_PAYLOAD_NAME = "backup.json"
const val ARCHIVE_DB_FILENAME = "wardrobapp.db"
const val ARCHIVE_IMAGES_DIRNAME = "images"

/** What a manifest says about the archive it belongs to. */
data class ArchiveManifest(
    val version: Int,
    val createdAt: String? = null,
    val imageCount: Int? = null,
)

/**
 * Thrown when an archive cannot be restored.
 *
 * A distinct type so callers can tell "this file is not restorable" from a
 * filesystem or zip failure, and report it as the user's problem to solve
 * rather than as a crash.
 *
 * Carries the [reason] as well as the sentence, so a screen can say the same
 * thing in the reader's language. The message is derived from the reason rather
 * than passed in: one English source, and `archive-validation.jsonl` keeps
 * comparing it.
 */
class UnrestorableArchiveException(
    val reason: UnrestorableReason,
) : Exception(reason.englishMessage())

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Read a manifest and decide whether this build can restore it.
 *
 * Messages name both versions, because "Unsupported backup version" on its own
 * leaves someone with no idea whether to update the app or give up on the file.
 */
fun parseArchiveManifest(text: String): ArchiveManifest {
    val root = try {
        lenientJson.parseToJsonElement(text)
    } catch (_: Exception) {
        throw UnrestorableArchiveException(
            UnrestorableReason.ManifestUnreadable(MANIFEST_NAME)
        )
    }

    if (root !is JsonObject) {
        throw UnrestorableArchiveException(
            UnrestorableReason.ManifestNotABackup(MANIFEST_NAME)
        )
    }

    // An integer, not merely a number: 3.5 is not a format version, and
    // accepting it would mean choosing a branch on nonsense.
    //
    // Whole-valued rather than integer-typed, because JSON has no integers and
    // JavaScript's Number.isInteger accepts 3.0 -- so a manifest written as
    // "version": 3.0 is valid there and has to be valid here too.
    val version = (root["version"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() && floor(it) == it }
        ?.toInt()
        ?: throw UnrestorableArchiveException(
            UnrestorableReason.ManifestVersionMissing(MANIFEST_NAME)
        )

    if (version > BACKUP_VERSION) {
        throw UnrestorableArchiveException(
            UnrestorableReason.BackupFromNewerApp(found = version, supported = BACKUP_VERSION)
        )
    }
    if (version < BACKUP_VERSION) {
        throw UnrestorableArchiveException(
            UnrestorableReason.UnsupportedVersion(
                found = version,
                readable = BACKUP_VERSION.toString(),
            )
        )
    }

    return ArchiveManifest(
        version = version,
        createdAt = (root["created_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        imageCount = (root["image_count"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?.toInt(),
    )
}

/**
 * Reject an archive that is missing pieces, before the live data is touched.
 *
 * The database check is the important one. Deleting the photo directory used to
 * be unconditional while restoring the database was not, so an archive that had
 * lost its database wiped every photo and reported success -- leaving rows that
 * all pointed at files no longer there.
 */
fun checkArchiveCompleteness(
    manifest: ArchiveManifest,
    hasDatabase: Boolean,
    imageCount: Int,
) {
    if (!hasDatabase) {
        throw UnrestorableArchiveException(
            UnrestorableReason.DatabaseMissing(ARCHIVE_DB_FILENAME)
        )
    }

    val expected = manifest.imageCount
    if (expected != null && imageCount < expected) {
        throw UnrestorableArchiveException(
            UnrestorableReason.ArchiveTruncated(expected = expected, present = imageCount)
        )
    }
}

/**
 * Check a legacy v1/v2 payload before it is applied.
 *
 * Same contract as [parseArchiveManifest]: reject while the wardrobe is still
 * untouched.
 */
fun checkLegacyPayload(version: Int, hasDatabase: Boolean) {
    if (version !in LEGACY_BACKUP_VERSIONS) {
        throw UnrestorableArchiveException(
            UnrestorableReason.UnsupportedVersion(
                found = version,
                readable = "${LEGACY_BACKUP_VERSIONS.joinToString(", ")} and $BACKUP_VERSION",
            )
        )
    }
    if (!hasDatabase) {
        throw UnrestorableArchiveException(UnrestorableReason.NoDatabase)
    }
}
