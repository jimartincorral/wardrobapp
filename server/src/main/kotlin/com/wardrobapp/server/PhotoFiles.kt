package com.wardrobapp.server

import com.wardrobapp.data.toStoredImageRef
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The server's garment photos: one flat directory of files, as on the phone.
 *
 * The phone decodes every photo it is given and writes it back out as a JPEG of
 * a known size, so every file in its directory is one it made. A server cannot
 * be that sure of what it is sent -- the bytes arrive over a network, from
 * whatever reached the port -- so it keeps the opposite promise: it decodes
 * nothing and serves back exactly what it stored, and it stores only what
 * starts like an image a browser can draw. The type is read from the bytes,
 * never from what the request says it is, and the name is the server's own.
 * Resizing is the browser's job before it uploads, which is where the phone
 * does it too: on the device that picked the photo.
 *
 * Names are checked on the way in as well as made safe on the way out. A
 * garment row says which photo it means by name, a request names which photo
 * to serve or delete, and a name with a path in it would reach outside this
 * directory.
 */
class PhotoFiles(val directory: File) {

    /** Store [bytes] as a new photo; its name. Throws [PhotoRejected] for anything else. */
    fun store(bytes: ByteArray): String {
        if (bytes.size > MAX_PHOTO_BYTES) throw PhotoRejected.TooLarge()
        val type = PhotoType.of(bytes) ?: throw PhotoRejected.NotAPhoto()

        directory.mkdirs()
        val name = "${UUID.randomUUID()}.${type.extension}"

        // Written beside and then moved into place, so a request that dies
        // halfway leaves a stray temporary file rather than a garment photo that
        // is half a JPEG.
        val partial = File.createTempFile("upload-", ".part", directory)
        try {
            partial.writeBytes(bytes)
            Files.move(partial.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            partial.delete()
        }
        return name
    }

    /**
     * Store [bytes] under [name], which a phone chose: how sync moves a photo,
     * since a garment row already refers to it by that name.
     *
     * Held to the same checks as [store] and one more: the name must be one
     * this server or the phone writes, and its extension must say what the
     * bytes are, or a `.jpg` that is really something else would be served as
     * a JPEG. A name already stored is left alone -- a name is given to one
     * photo once and never reused, so the same name is the same photo.
     */
    fun storeAs(name: String, bytes: ByteArray) {
        if (!isPhotoName(name)) throw PhotoRejected.NotAPhoto()
        if (bytes.size > MAX_PHOTO_BYTES) throw PhotoRejected.TooLarge()
        val type = PhotoType.of(bytes) ?: throw PhotoRejected.NotAPhoto()
        if (PhotoType.ofName(name) != type) throw PhotoRejected.NotAPhoto()

        val target = File(directory, name)
        if (target.isFile) return

        directory.mkdirs()
        val partial = File.createTempFile("upload-", ".part", directory)
        try {
            partial.writeBytes(bytes)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            partial.delete()
        }
    }

    /** The stored photo called [name], or null if there is none or the name is not one of ours. */
    fun file(name: String): File? {
        if (!isPhotoName(name)) return null
        return File(directory, name).takeIf { it.isFile }
    }

    /**
     * Delete a stored photo, given any form of its reference -- a bare name, or
     * the `photos/` form a garment row is read as. Nothing to do for a reference
     * that is not one of this directory's names.
     */
    fun delete(ref: String) {
        val name = toStoredImageRef(ref)
        if (isPhotoName(name)) File(directory, name).delete()
    }

    /**
     * The shape of every name this server or the phone writes: a UUID, perhaps
     * with the phone's `_nobg` suffix, and an extension. Letters, digits, dots,
     * dashes and underscores, starting with a letter or digit -- which rules
     * out separators, `..`, and a hidden file.
     */
    companion object {
        /** Whether [name] is the shape of a stored photo's name; see [PHOTO_NAME]. */
        fun isPhotoName(name: String) = PHOTO_NAME.matches(name) && ".." !in name

        /**
         * Twenty megabytes. A phone camera's JPEG is a few; this leaves room for
         * a large PNG while refusing a request that would fill the disk Home
         * Assistant's backups live on.
         */
        const val MAX_PHOTO_BYTES = 20 * 1024 * 1024

        private val PHOTO_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}""")
    }
}

/** The kinds of image a browser can draw that the server will keep, by their first bytes. */
enum class PhotoType(val extension: String, val contentType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    ;

    companion object {
        fun of(bytes: ByteArray): PhotoType? = when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
            // "RIFF", four bytes of length, then "WEBP".
            bytes.size >= 12 &&
                bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) -> WEBP
            else -> null
        }

        /** The type a stored photo is served as, by the extension it was stored with. */
        fun ofName(name: String): PhotoType? = entries.firstOrNull { name.endsWith(".${it.extension}") }

        private fun ByteArray.startsWith(vararg prefix: Int) =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}

/** Why an upload was not stored. */
sealed class PhotoRejected(message: String) : Exception(message) {
    /** Too many bytes to store, or -- with a message saying so -- too many pixels to cut out. */
    class TooLarge(message: String = "That photo is too large to store.") : PhotoRejected(message)

    class NotAPhoto : PhotoRejected("That file is not a JPEG, PNG or WebP photo.")
}
