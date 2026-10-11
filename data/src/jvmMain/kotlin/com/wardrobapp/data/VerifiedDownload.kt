package com.wardrobapp.data

import java.io.File
import java.security.MessageDigest

/** What checking a finished download found. */
enum class DownloadCheck {
    /** The file is the one expected, and is now at its target. */
    ACCEPTED,

    /** Shorter or longer than expected: a download cut off, or a different file. Deleted. */
    WRONG_SIZE,

    /** The right size and the wrong bytes: not the file chosen. Deleted. */
    WRONG_DIGEST,

    /** The file was right but could not be moved into place. Left for the caller to retry. */
    NOT_MOVED,
}

/**
 * Accept [partial], a finished download, as [target] -- only if it is
 * [expectedBytes] long and its SHA-256 is [sha256].
 *
 * The rule every download of a file this app will run follows: written
 * under another name first, so a download cut short is never taken for the
 * file; checked against the digest it had when it was chosen, so a file
 * swapped at the source is refused rather than run; and only then put where
 * it will be used. A file refused is deleted, since nothing can be done with
 * it but download it again. Here, off the phone, so it can be tested.
 *
 * The digest is read from the file in pieces, never whole: the style model
 * is 85 MB, which a phone's Java heap may not have to spare.
 */
fun acceptDownload(partial: File, expectedBytes: Long, sha256: String, target: File): DownloadCheck {
    if (partial.length() != expectedBytes) {
        partial.delete()
        return DownloadCheck.WRONG_SIZE
    }
    val digest = MessageDigest.getInstance("SHA-256")
    partial.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(sha256, ignoreCase = true)) {
        partial.delete()
        return DownloadCheck.WRONG_DIGEST
    }
    target.delete()
    return if (partial.renameTo(target)) DownloadCheck.ACCEPTED else DownloadCheck.NOT_MOVED
}
