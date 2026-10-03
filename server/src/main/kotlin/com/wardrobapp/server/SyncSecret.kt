package com.wardrobapp.server

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The pairing code: the secret a phone has to show to sync.
 *
 * Made the first time anything asks for it, from a secure random source, and
 * kept in the app's data directory so it survives updates and restarts -- and
 * is in Home Assistant's backups with the wardrobe it opens. Readable only by
 * the server's own user.
 *
 * Twenty characters from Crockford's base32 alphabet, in groups of five: a
 * hundred bits, which no amount of guessing over a home network gets through,
 * in letters a person can read off one screen and type into another without
 * telling a 0 from an O. Comparing ignores case, dashes and spaces for the
 * same reason, and compares in constant time, so a guess learns nothing from
 * how long it took to be refused.
 *
 * Resetting it makes a new one, which unpairs every phone at once: the way to
 * shut out a phone that was lost or given away.
 */
class SyncSecret(private val file: File) {

    private val lock = Any()

    /** The code, made now if there is none yet. */
    fun current(): String = synchronized(lock) {
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: write(generate())
    }

    /** Replace the code with a new one; every phone paired with the old one will have to pair again. */
    fun reset(): String = synchronized(lock) { write(generate()) }

    /** Whether [offered] is the code, read the forgiving way a person types it. */
    fun accepts(offered: String): Boolean =
        MessageDigest.isEqual(normalized(offered).toByteArray(), normalized(current()).toByteArray())

    private fun write(code: String): String {
        file.absoluteFile.parentFile?.mkdirs()
        val partial = File(file.absoluteFile.parentFile, "${file.name}.part")
        partial.writeText(code)
        try {
            Files.setPosixFilePermissions(partial.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX filesystem: wherever this runs that is not Home
            // Assistant's container, and the directory's own permissions apply.
        }
        partial.renameTo(file) || error("Could not save the pairing code to $file")
        return code
    }

    private fun generate(): String {
        val random = SecureRandom()
        return (1..LENGTH)
            .map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .chunked(GROUP)
            .joinToString("-") { it.joinToString("") }
    }

    private companion object {
        /** Crockford's base32: no I, L, O or U, so nothing reads as a 1, a 0 or a word. */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val LENGTH = 20
        const val GROUP = 5

        fun normalized(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
    }
}
