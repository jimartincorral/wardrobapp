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

/**
 * References a garment used to hold and no longer does.
 *
 * Compared as stored *filenames* rather than as the references were given, because
 * the same photo can be named either way -- an absolute path from an older build, a
 * resolved `file://` URI from a read, a bare filename from the database -- and a
 * mismatch either way is a quiet failure: report a file still in use and it gets
 * deleted out from under the garment; miss one and it stays on the phone with
 * nothing pointing at it.
 *
 * Only names them, so the caller decides when it is safe to act -- which is after
 * the row is written, never before.
 */
fun orphanedImageRefs(previous: List<String>, kept: List<String>): List<String> {
    val keptNames = kept.filter { it.isNotEmpty() }.map(::toStoredImageRef).toSet()
    val seen = mutableSetOf<String>()

    return previous.filter { ref ->
        if (ref.isEmpty()) return@filter false

        val name = toStoredImageRef(ref)
        if (name in keptNames || name in seen) return@filter false

        seen.add(name)
        true
    }
}

/**
 * True when a reference points at a photo the app already owns.
 *
 * The distinction that keeps a cleanup from deleting live data. A cut-out written
 * for a form that is then abandoned is disposable; one belonging to a garment
 * already in the database is not, and is still referenced by its row until the
 * next save goes through.
 *
 * `imageDirectory` is passed in rather than read, exactly as [resolveImageRef]
 * takes it: this file knows the layout, not where the filesystem is mounted.
 */
fun isStoredGarmentImage(ref: String, imageDirectory: String): Boolean {
    if (ref.isEmpty() || imageDirectory.isEmpty()) return false
    if (!ref.startsWith(imageDirectory)) return false

    // Photos are stored flat, so anything nested is not one of ours -- and the
    // comparison is against the directory rather than a search for the folder name,
    // so a temp path that merely mentions it does not pass.
    return !ref.removePrefix(imageDirectory).contains('/')
}

/** True when a reference still carries a directory, i.e. predates the rewrite. */
fun isLegacyAbsoluteImageRef(ref: String): Boolean =
    ref.isNotEmpty() && !isNonFileRef(ref) && ref.contains('/')
