package com.wardrobapp.data

/**
 * Garment photo references.
 *
 * Photos live in `<documents>/garment-images/`, but the *absolute* path to that
 * directory is not stable across installs, so the database stores only the
 * filename and the directory is re-attached on read. Rows written by older
 * builds still hold absolute paths, so the resolver also re-bases those onto the
 * current directory: reads are correct immediately, whether or not the migration
 * that rewrites them has run.
 *
 * Values that are not local paths -- Android SAF documents, remote URLs, and any
 * inline data -- are passed through untouched in both directions.
 */

const val GARMENT_IMAGE_DIRNAME = "garment-images"

/**
 * References that are already portable and must never be reduced to a filename:
 * Android SAF documents, remote images, and inline data.
 */
private val NON_FILE_REF = Regex("^(data:|blob:|https?:|content:)", RegexOption.IGNORE_CASE)

private fun isNonFileRef(ref: String) = NON_FILE_REF.containsMatchIn(ref)

/** The trailing path segment, or the whole string when there is no separator. */
private fun basename(ref: String): String = ref.substringAfterLast('/')

/**
 * Reduce a reference to the portable form kept in the database.
 *
 * Applied at the write boundary, so callers can go on handing around the full
 * URIs that image pickers and image views need.
 */
fun toStoredImageRef(ref: String): String {
    if (ref.isEmpty()) return ""
    if (isNonFileRef(ref)) return ref
    return basename(ref)
}

/**
 * Expand a stored reference into something the platform can load.
 *
 * `imageDirectory` is passed explicitly by the caller that knows it (and by
 * tests); when it is empty -- before the filesystem is available -- the
 * reference is returned unchanged rather than resolved against nothing.
 */
fun resolveImageRef(ref: String, imageDirectory: String): String {
    if (ref.isEmpty()) return ""
    if (isNonFileRef(ref)) return ref
    if (imageDirectory.isEmpty()) return ref
    return "$imageDirectory${basename(ref)}"
}

/** True when a reference still carries a directory, i.e. predates the rewrite. */
fun isLegacyAbsoluteImageRef(ref: String): Boolean =
    ref.isNotEmpty() && !isNonFileRef(ref) && ref.contains('/')
