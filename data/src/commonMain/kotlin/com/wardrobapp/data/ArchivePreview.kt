package com.wardrobapp.data

/**
 * What one archive turns out to hold.
 *
 * Common code, while reading an archive is not: the restore screen shows this on
 * every platform, and only the phone and the server ever open a zip. The reader
 * is `readArchivePreview`, in jvmMain.
 */
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
