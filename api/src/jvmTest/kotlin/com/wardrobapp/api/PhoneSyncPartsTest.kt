package com.wardrobapp.api

import com.wardrobapp.presentation.SyncFailure
import io.ktor.client.request.get
import java.io.File
import java.io.FileNotFoundException
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException

/** The pieces of the phone's sync that need no server: its photo folder, and what failures mean. */
class PhoneSyncPartsTest {

    private fun folderTest(block: suspend (File, DirectoryPhotoFolder) -> Unit) {
        val root = Files.createTempDirectory("phone-photos").toFile()
        try {
            val directory = File(root, "garment-images")
            runBlocking { block(directory, DirectoryPhotoFolder(directory)) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a photo is written, read, and deleted by name`() = folderTest { directory, folder ->
        assertFalse(folder.has("a.jpg"))
        folder.write("a.jpg", byteArrayOf(1, 2, 3))

        assertTrue(folder.has("a.jpg"))
        assertContentEquals(byteArrayOf(1, 2, 3), folder.read("a.jpg"))
        assertEquals(listOf("a.jpg"), directory.list()?.toList(), "a partial file was left behind")

        folder.delete("a.jpg")
        assertFalse(folder.has("a.jpg"))
        assertNull(folder.read("a.jpg"))
    }

    @Test
    fun `a name that would leave the folder is never a path`() = folderTest { directory, folder ->
        val outside = File(directory.parentFile, "shared_prefs.xml").apply { writeText("keep") }
        for (name in listOf("../shared_prefs.xml", "..", ".", "", ".hidden", "a/b.jpg", "a\\b.jpg", "a\u0000.jpg")) {
            folder.write(name, byteArrayOf(9))
            folder.delete(name)
            assertFalse(folder.has(name), name)
            assertNull(folder.read(name), name)
        }
        assertEquals("keep", outside.readText())
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun `failures read as what somebody can do about them`() {
        assertEquals(SyncFailure.NotPaired, syncFailureOf(ServerException("Pair this phone first", 401)))
        // Something that is not the sync port: Home Assistant's own, say.
        assertEquals(SyncFailure.Unreachable, syncFailureOf(ServerException("The server answered 404 Not Found", 404)))
        assertEquals(SyncFailure.Unreachable, syncFailureOf(NotFoundException()))
        assertEquals(SyncFailure.Unreachable, syncFailureOf(SerializationException("not JSON")))
        assertEquals(SyncFailure.Other("Database is locked"), syncFailureOf(ServerException("Database is locked", 500)))
        // A local file is not the network, though it is an IOException.
        assertIs<SyncFailure.Other>(syncFailureOf(FileNotFoundException("/data/garment-images/a.jpg (No space left on device)")))
    }

    @Test
    fun `nothing listening at the address is unreachable`() = runBlocking {
        // A port that was free a moment ago, so nothing is on it.
        val port = ServerSocket(0).use { it.localPort }
        val failure = try {
            syncHttpClient("http://127.0.0.1:$port/", "code").use { it.get(SyncRoutes.STATUS) }
            null
        } catch (e: Exception) {
            syncFailureOf(e)
        }
        assertEquals(SyncFailure.Unreachable, failure)
    }

    @Test
    fun `a name that does not resolve is unreachable`() = runBlocking {
        val failure = try {
            syncHttpClient("http://wardrobapp-sync.invalid:8100/", "code").use { it.get(SyncRoutes.STATUS) }
            null
        } catch (e: Exception) {
            syncFailureOf(e)
        }
        assertEquals(SyncFailure.Unreachable, failure)
    }
}
