package com.wardrobapp.data

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * What is in an archive, read without unpacking it.
 *
 * A restore replaces a wardrobe with somebody else's idea of one, and until now
 * the only way to find out which idea was to do it. Backups are named by
 * timestamp, several sit in a folder or a Drive at once, and the difference
 * between the right one and the one from after the damage is a date nobody can
 * see from the file name alone.
 *
 * So this reads the archive and reports, and touches nothing: no extraction, no
 * temporary directory, no staging. Every rejection here is free, in the same
 * sense [parseArchiveManifest] is free -- the wardrobe is still whole when it
 * says no.
 *
 * It is deliberately not a second opinion. The version rules, the completeness
 * rules and the sentences that explain them are [BackupArchive]'s, and this calls
 * them rather than repeating them: a preview that said yes where the restore
 * would say no would be worse than no preview at all.
 */

/** What one archive turns out to hold. */
data class ArchivePreview(
    /** The format version, once it is known to be one this build reads. */
    val version: Int,
    /** When the backup was written, as its manifest recorded it. Null if it did not. */
    val createdAt: String? = null,
    /** How many photos the manifest claims. Null if it did not say. */
    val declaredImages: Int? = null,
    /** How many photo entries are actually in the archive. */
    val presentImages: Int,
    /** Whether the wardrobe database is in there at all. */
    val hasDatabase: Boolean,
    /** True for the v1/v2 shape, whose database was base64 inside `backup.json`. */
    val legacy: Boolean = false,
    /**
     * Whether the archive carries how the app was set up.
     *
     * Reported so the choice about restoring them can be offered only when there
     * is something to restore: an archive written before settings existed has
     * none, and asking about them anyway is a question with one answer.
     */
    val hasSettings: Boolean = false,
) {
    /**
     * Whether the archive is short of photos its manifest promised.
     *
     * Restoring one of these is refused, so a preview that did not say would be
     * showing somebody a backup it already knows it will not accept.
     */
    val truncated: Boolean
        get() = declaredImages != null && presentImages < declaredImages
}

/**
 * Read an archive and say what is in it.
 *
 * Throws [UnrestorableArchiveException] for exactly what a restore would throw it
 * for, and for the same reasons -- so a preview that returns is a promise that
 * the archive got past validation, not merely that it was readable.
 *
 * The stream is consumed once, as everywhere else that takes one: an archive
 * arrives as a `content://` stream on Android and those do not rewind, so a
 * caller that wants to preview *and then* restore opens it twice.
 */
fun readArchivePreview(archive: InputStream): ArchivePreview {
    val names = mutableListOf<String>()
    var manifestText: String? = null
    var legacyText: String? = null

    ZipInputStream(archive.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name

            if (!entry.isDirectory) names += name

            // Read the small ones, skip the rest. The database and the photos are
            // the whole archive by size and none of it by information: what a
            // preview says comes out of the manifest and out of which names are
            // present, so there is no reason to pull megabytes through here.
            when (nameWithinArchive(name)) {
                MANIFEST_NAME -> manifestText = zip.readBytes().decodeToString()
                LEGACY_PAYLOAD_NAME -> legacyText = zip.readBytes().decodeToString()
            }

            zip.closeEntry()
        }
    }

    manifestText?.let { return folderPreview(it, names) }
    legacyText?.let { return legacyPreview(it) }

    throw UnrestorableArchiveException(UnrestorableReason.ManifestNotFound(MANIFEST_NAME))
}

/**
 * The current shape: a manifest, a database, and an `images/` folder.
 *
 * Completeness is checked here rather than left to the restore, because being
 * told after the fact that an archive was short of photos is exactly the
 * surprise a preview exists to remove.
 */
private fun folderPreview(manifestText: String, names: List<String>): ArchivePreview {
    val manifest = parseArchiveManifest(manifestText)

    val hasDatabase = names.any { nameWithinArchive(it) == ARCHIVE_DB_FILENAME }
    val presentImages = names.count { isImageEntry(it) }
    val hasSettings = names.any { nameWithinArchive(it) == SETTINGS_NAME }

    checkArchiveCompleteness(manifest, hasDatabase = hasDatabase, imageCount = presentImages)

    return ArchivePreview(
        version = manifest.version,
        createdAt = manifest.createdAt,
        declaredImages = manifest.imageCount,
        presentImages = presentImages,
        hasDatabase = true,
        hasSettings = hasSettings,
    )
}

/**
 * The v1/v2 shape, which said less about itself.
 *
 * There is no image count to promise and no `created_at` in the payload, so a
 * preview of one of these is thinner on purpose rather than by omission -- and
 * saying "1 photo" when the old format never counted them would be inventing a
 * number.
 */
private fun legacyPreview(payloadText: String): ArchivePreview {
    val payload = parseLegacyPayload(payloadText)

    // The same call the restore makes, with the same argument: an absent database
    // parses as an empty string rather than as null.
    checkLegacyPayload(payload.version, hasDatabase = payload.database.isNotEmpty())

    return ArchivePreview(
        version = payload.version,
        presentImages = payload.images.size,
        hasDatabase = true,
        legacy = true,
    )
}

/**
 * An entry's name with one wrapping directory removed.
 *
 * Some zip tools put everything inside a single top-level folder, which
 * [ArchiveLayout.NESTED] exists to cope with on the restore side. A preview that
 * did not cope with it would call a perfectly good archive unreadable.
 */
private fun nameWithinArchive(name: String): String {
    val trimmed = name.trimStart('/')
    val slash = trimmed.indexOf('/')
    if (slash < 0) return trimmed

    val rest = trimmed.substring(slash + 1)
    // Only one level is stripped, and only when what is left is a name rather
    // than another path: `images/a.jpg` must stay `images/a.jpg`.
    return if ('/' in rest) name else rest
}

/** Whether an entry is one of the photos, at either nesting. */
private fun isImageEntry(name: String): Boolean {
    val trimmed = name.trimStart('/')
    val prefix = "$ARCHIVE_IMAGES_DIRNAME/"

    return trimmed.startsWith(prefix) ||
        trimmed.substringAfter('/', missingDelimiterValue = "").startsWith(prefix)
}
