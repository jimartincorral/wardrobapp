package com.wardrobapp.api

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photos as files in [directory], by the names garment rows hold: the phone's
 * photo directory, as a sync sees it.
 *
 * Every name is checked before it becomes a path. The names come from the
 * server -- a server the phone was paired with, but a name like
 * `../shared_prefs/wardrobapp_sync.xml` would still be a write anywhere in the
 * app's data, and the cost of refusing it is nothing: no name this app or the
 * server writes has a separator in it, or starts with a dot. A name refused
 * reads as a photo that is not there, so the garment shows without it.
 *
 * Written to a temporary file and renamed into place, so a sync killed halfway
 * through a download leaves no half a photo under the real name -- which [has]
 * would answer yes to, and nothing would ever fetch again.
 */
class DirectoryPhotoFolder(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PhotoFolder {

    override suspend fun has(name: String): Boolean = withContext(io) { fileFor(name)?.isFile == true }

    override suspend fun read(name: String): ByteArray? = withContext(io) {
        fileFor(name)?.takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(io) {
        val file = fileFor(name) ?: return@withContext
        directory.mkdirs()
        val partial = File(directory, ".$name.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) {
            partial.delete()
            error("Could not store the photo $name")
        }
    }

    override suspend fun delete(name: String) {
        withContext(io) { fileFor(name)?.delete() }
    }

    private fun fileFor(name: String): File? = File(directory, name).takeIf { isPlainName(name) }

    companion object {
        /** A file name and nothing more: no directory, no parent, nothing hidden. */
        fun isPlainName(name: String): Boolean =
            name.isNotEmpty() &&
                !name.startsWith('.') &&
                '/' !in name &&
                '\\' !in name &&
                name.none { it.isISOControl() }
    }
}
