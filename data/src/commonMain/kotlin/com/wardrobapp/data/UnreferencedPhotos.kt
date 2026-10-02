package com.wardrobapp.data

/**
 * Photos on disk that no garment points at.
 *
 * Saving a garment already deletes the file its own edit orphaned -- an original
 * whose cut-out replaced it, a photo removed from the form -- and
 * [orphanedImageRefs] is that rule. This is the one it cannot be: a file left
 * behind by a build that did not collapse cut-outs, by the app this replaced, or
 * by a save that died between writing the row and deleting the file. Nothing has
 * ever swept those, so they sit in the photo directory and go into every backup.
 *
 * Compared as stored *filenames*, for the same reason [orphanedImageRefs] is: the
 * same photo is named three ways across this codebase -- a bare filename in the
 * database, a resolved `file://` URI from a read, an absolute path from an older
 * build -- and a mismatch here deletes a photo a garment is still showing.
 *
 * Only names them. Whether a file is old enough to be safe to delete, and the
 * deleting itself, are the caller's: this module has no clock and no filesystem.
 */
fun unreferencedPhotos(present: List<String>, referenced: List<String>): List<String> {
    // Empty means "no reference", not "a reference to nothing": a garment with one
    // photo has a blank in every other cut-out slot, and a blank that matched the
    // whole directory would be the worst possible bug in this file.
    val live = referenced.filter { it.isNotEmpty() }.map(::toStoredImageRef).toSet()

    return present.filter { it.isNotEmpty() && toStoredImageRef(it) !in live }
}
