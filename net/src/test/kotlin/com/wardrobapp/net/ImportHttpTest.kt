package com.wardrobapp.net

import com.wardrobapp.domain.MAX_PAGE_CHARS
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What URL import's requests do, against a real server.
 *
 * The page and image halves of what used to be :app's `AndroidImportFetchers`,
 * which until it moved could only be compiled in CI and was tested nowhere. The
 * server is on loopback, which the fetcher's own redirect check refuses -- so
 * every redirect here that *should* be followed cannot be, and the redirect tests
 * are the ones about refusing.
 */
class ImportHttpTest {

    private val server = TestServer()
    private val http = ImportHttp()
    private val scratch = File.createTempFile("import-http-", ".bin")

    @AfterTest
    fun tearDown() {
        server.close()
        scratch.delete()
    }

    @Test
    fun `a page reports where it came from, its status and what it declared`() {
        val body = "<html><title>Shirt</title></html>".toByteArray()
        server.respond("/page", headers = mapOf("Content-Type" to "text/html; charset=utf-8"), body = body)

        http.pages().use { pages ->
            val page = pages.fetch(server.url("/page"))

            assertEquals(server.url("/page"), page.finalUrl)
            assertEquals(200, page.status)
            assertEquals("text/html; charset=utf-8", page.contentType)
            assertEquals(body.size.toLong(), page.declaredLength)
            assertEquals("<html><title>Shirt</title></html>", page.readText())
        }
    }

    @Test
    fun `a status that is not success is handed back for domain to judge`() {
        server.respond("/gone", status = 404, body = "nope".toByteArray())

        http.pages().use { pages ->
            assertEquals(404, pages.fetch(server.url("/gone")).status)
        }
    }

    @Test
    fun `a redirect onto this device is refused before it is requested`() {
        server.redirect("/start", server.url("/private"))
        server.respond("/private", body = "router admin".toByteArray())

        val refusal = assertFailsWith<UnsafeUrlException> {
            http.pages().use { it.fetch(server.url("/start")) }
        }

        assertEquals(UnsafeUrlReason.HostIsLocal("127.0.0.1"), refusal.reason)
        assertEquals(0, server.hits("/private"))
    }

    @Test
    fun `a relative redirect is checked as the address it resolves to`() {
        // "/private" is harmless as written; resolved against a loopback page it
        // is a loopback address, and that is what has to be judged.
        server.redirect("/relative", "/private-too")
        server.respond("/private-too", body = "router admin".toByteArray())

        assertFailsWith<UnsafeUrlException> {
            http.pages().use { it.fetch(server.url("/relative")) }
        }
        assertEquals(0, server.hits("/private-too"))
    }

    @Test
    fun `a redirect status with no location is returned as the status it is`() {
        server.respond("/broken", status = 302)

        http.pages().use { pages ->
            assertEquals(302, pages.fetch(server.url("/broken")).status)
        }
    }

    @Test
    fun `a page is read to just past what domain will accept, and no further`() {
        val body = "a".repeat(MAX_PAGE_CHARS + 100_000).toByteArray()
        server.respond("/huge", headers = mapOf("Content-Type" to "text/html"), body = body)

        http.pages().use { pages ->
            val text = pages.fetch(server.url("/huge")).readText()

            // Past the bound, so domain sees an over-long page as over-long...
            assertTrue(text.length > MAX_PAGE_CHARS)
            // ...but by at most one read buffer, not by the rest of the body.
            assertTrue(text.length <= MAX_PAGE_CHARS + 8 * 1024, "read ${text.length}")
        }
    }

    @Test
    fun `a gzipped page is decompressed`() {
        server.respond(
            "/gzipped",
            headers = mapOf("Content-Type" to "text/html", "Content-Encoding" to "gzip"),
            body = gzip("<p>Camiseta añil</p>".toByteArray()),
        )

        http.pages().use { pages ->
            assertEquals("<p>Camiseta añil</p>", pages.fetch(server.url("/gzipped")).readText())
        }
    }

    @Test
    fun `a page that declares no charset is read as UTF-8`() {
        server.respond("/undeclared", headers = mapOf("Content-Type" to "text/html"), body = "Añil".toByteArray())

        http.pages().use { pages ->
            assertEquals("Añil", pages.fetch(server.url("/undeclared")).readText())
        }
    }

    @Test
    fun `a declared charset is honoured`() {
        server.respond(
            "/latin1",
            headers = mapOf("Content-Type" to "text/html; charset=ISO-8859-1"),
            body = "Añil".toByteArray(Charsets.ISO_8859_1),
        )

        http.pages().use { pages ->
            assertEquals("Añil", pages.fetch(server.url("/latin1")).readText())
        }
    }

    @Test
    fun `a charset the JDK does not know falls back to UTF-8 rather than failing`() {
        server.respond(
            "/nonsense",
            headers = mapOf("Content-Type" to "text/html; charset=not-a-charset"),
            body = "Añil".toByteArray(),
        )

        http.pages().use { pages ->
            assertEquals("Añil", pages.fetch(server.url("/nonsense")).readText())
        }
    }

    @Test
    fun `an image is written to the destination as it arrived`() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        server.respond("/photo.jpg", headers = mapOf("Content-Type" to "image/jpeg"), body = bytes)

        http.download(server.url("/photo.jpg"), scratch)

        assertContentEquals(bytes, scratch.readBytes())
    }

    @Test
    fun `an image that is not there is an error, not an empty photo`() {
        server.respond("/missing.jpg", status = 404, body = "not found".toByteArray())

        assertFailsWith<ImageRefused> { http.download(server.url("/missing.jpg"), scratch) }
        assertEquals(0, scratch.length())
    }

    @Test
    fun `an image larger than the app stores is refused`() {
        server.respond("/enormous.jpg", body = ByteArray(MAX_IMAGE_BYTES + 1))

        assertFailsWith<ImageTooLarge> { http.download(server.url("/enormous.jpg"), scratch) }
    }

    @Test
    fun `an image redirect onto this device is refused before it is requested`() {
        server.redirect("/cdn.jpg", server.url("/private.jpg"))
        server.respond("/private.jpg", body = ByteArray(10))

        assertFailsWith<UnsafeUrlException> { http.download(server.url("/cdn.jpg"), scratch) }
        assertEquals(0, server.hits("/private.jpg"))
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
