package com.wardrobapp.data

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A downloaded file is used only when it is the one chosen, whole. */
class VerifiedDownloadTest {

    private val directory = Files.createTempDirectory("download").toFile()
    private val partial = File(directory, "model.onnx.part")
    private val target = File(directory, "model.onnx")
    private val bytes = ByteArray(200_000) { (it * 7).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun `the file chosen is moved into place`() {
        partial.writeBytes(bytes)
        assertEquals(DownloadCheck.ACCEPTED, acceptDownload(partial, bytes.size.toLong(), sha256, target))
        assertContentEquals(bytes, target.readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun `a download cut short is refused and deleted`() {
        partial.writeBytes(bytes.copyOf(1000))
        assertEquals(DownloadCheck.WRONG_SIZE, acceptDownload(partial, bytes.size.toLong(), sha256, target))
        assertFalse(partial.exists())
        assertFalse(target.exists())
    }

    @Test
    fun `a different file of the right size is refused and deleted, and an older good one is kept`() {
        target.writeBytes(byteArrayOf(1, 2, 3))
        partial.writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertEquals(DownloadCheck.WRONG_DIGEST, acceptDownload(partial, bytes.size.toLong(), sha256, target))
        assertFalse(partial.exists())
        assertTrue(target.exists(), "a refused download replaced what was there")
    }

    @Test
    fun `the digest is compared without regard to case`() {
        partial.writeBytes(bytes)
        assertEquals(DownloadCheck.ACCEPTED, acceptDownload(partial, bytes.size.toLong(), sha256.uppercase(), target))
    }
}
