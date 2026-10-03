package com.wardrobapp.server

import com.wardrobapp.api.NotFoundException
import com.wardrobapp.api.ServerException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoRoutesTest {

    @Test
    fun `each kind of photo is stored and served as what its bytes say it is`() = serverTest {
        for ((bytes, type) in listOf(jpeg() to ContentType.Image.JPEG, png() to ContentType.Image.PNG, webp() to ContentType("image", "webp"))) {
            // Labelled as something else on purpose: the label is the
            // sender's claim, and the server goes by the bytes.
            val ref = photos.upload(bytes, ContentType.Application.OctetStream)

            val served = http.get(ref)
            assertEquals(type, served.contentType()?.withoutParameters(), ref)
            assertContentEquals(bytes, served.readRawBytes())
            assertEquals("nosniff", served.headers["X-Content-Type-Options"])
        }
    }

    @Test
    fun `an upload that is not a photo is refused and nothing is kept`() = serverTest {
        val refused = assertFailsWith<ServerException> {
            photos.upload("<script>alert(1)</script>".encodeToByteArray(), ContentType.Image.JPEG)
        }

        assertEquals(415, refused.status)
        assertTrue(photoDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an upload past the limit is refused`() = serverTest {
        val tooLarge = jpeg() + ByteArray(PhotoFiles.MAX_PHOTO_BYTES)

        val refused = assertFailsWith<ServerException> { photos.upload(tooLarge, ContentType.Image.JPEG) }

        assertEquals(413, refused.status)
        assertTrue(photoDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a photo is deleted by any form of its reference`() = serverTest {
        val ref = uploadPhoto()
        val file = File(photoDirectory, ref.removePrefix("photos/"))
        assertTrue(file.isFile)

        photos.delete(ref)

        assertFalse(file.exists())
        assertFailsWith<NotFoundException> { http.get(ref) }
    }

    @Test
    fun `a name that reaches out of the photo directory reaches nothing`() = serverTest {
        // A file beside the photo directory, named like a photo, so the only
        // thing between a request and it is the name check -- not the
        // extension, not the database's own name.
        addGarment()
        val outside = File(dataDirectory, "outside.jpg").apply { writeBytes(jpeg()) }

        for (name in listOf("..%2Foutside.jpg", "%2E%2E%2Foutside.jpg", "..%5Coutside.jpg")) {
            assertFailsWith<NotFoundException>(name) { http.get("photos/$name") }
        }
        for (name in listOf("..%2Foutside.jpg", "%2E%2E%2Foutside.jpg", "..")) {
            http.delete("api/photos/$name")
        }

        assertTrue(outside.isFile)
        assertTrue(File(dataDirectory, "SQLite/wardrobapp.db").isFile)
    }

    @Test
    fun `only names shaped like a stored photo are served`() = serverTest {
        // Inside the directory, but not a name this server or the phone
        // writes: a dotfile, say, left by something else.
        photoDirectory.mkdirs()
        File(photoDirectory, ".hidden.jpg").writeBytes(jpeg())

        assertFailsWith<NotFoundException> { http.get("photos/.hidden.jpg") }
    }
}
