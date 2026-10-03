package com.wardrobapp.net

import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.MAX_PAGE_CHARS
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What URL import's requests do, against a real server.
 *
 * The server is on loopback, so the tests need a small model of the internet to
 * talk to it through. In that model 127.0.0.1 is the public web and 127.0.0.2
 * stands for somebody's router: `shop.test` resolves to the first, `router.test`
 * to the second, and the rule both address checks apply allows only the first.
 * Everything else -- redirects, the written-address check in :domain, the client
 * -- is the code the app runs.
 *
 * Whether a refused request was ever made is asked of the server, which is the
 * only thing that knows.
 */
class ImportHttpTest {

    private val publicWeb = InetAddress.getByAddress("shop.test", byteArrayOf(127, 0, 0, 1))
    private val router = InetAddress.getByAddress("router.test", byteArrayOf(127, 0, 0, 2))

    /** Every name the client asked to have resolved, in order. */
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val testDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            lookups += hostname
            return when (hostname) {
                "shop.test" -> listOf(publicWeb)
                "router.test" -> listOf(router)
                // A name answered with both, which is not a shop's name.
                "mixed.test" -> listOf(publicWeb, router)
                else -> throw UnknownHostException(hostname)
            }
        }
    }

    private val isPublicHere: (InetAddress) -> Boolean = { it == publicWeb }

    private val server = TestServer()
    private val http = ImportHttp(testDns, isPublicHere)
    private val scratch = File.createTempFile("import-http-", ".bin")

    @AfterTest
    fun tearDown() {
        server.close()
        scratch.delete()
    }

    // What a page reports.

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
    fun `a gzipped page is decompressed, and its compressed length is not passed off as its size`() {
        server.respond(
            "/gzipped",
            headers = mapOf("Content-Type" to "text/html", "Content-Encoding" to "gzip"),
            body = gzip("<p>Camiseta añil</p>".toByteArray()),
        )

        http.pages().use { pages ->
            val page = pages.fetch(server.url("/gzipped"))
            assertNull(page.declaredLength)
            assertEquals("<p>Camiseta añil</p>", page.readText())
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
    fun `a server that does not answer in time is reported as timing out`() {
        val impatient = ImportHttp(testDns, isPublicHere, timeoutMs = 300)
        server.on("/slow") { exchange ->
            Thread.sleep(1_500)
            exchange.sendResponseHeaders(200, -1)
        }

        val failure = assertFailsWith<GarmentImportException> {
            impatient.pages().use { it.fetch(server.url("/slow")) }
        }
        assertEquals(ImportFailureReason.PageTimedOut, failure.reason)
    }

    // Redirects.

    @Test
    fun `a redirect within the public web is followed, and the page says where it ended`() {
        server.redirect("/old", "/new")
        server.respond("/new", headers = mapOf("Content-Type" to "text/html"), body = "here".toByteArray())

        http.pages().use { pages ->
            val page = pages.fetch(server.url("/old"))
            assertEquals(server.url("/new"), page.finalUrl)
            assertEquals("here", page.readText())
        }
    }

    @Test
    fun `redirects are followed five times and no more`() {
        server.redirect("/loop", server.url("/loop"))

        val failure = assertFailsWith<GarmentImportException> {
            http.pages().use { it.fetch(server.url("/loop")) }
        }

        assertEquals(ImportFailureReason.PageNotLoaded(302), failure.reason)
        // The first request and five followed: the sixth redirect is not.
        assertEquals(6, server.hits("/loop"))
    }

    @Test
    fun `a redirect status with no location is returned as the status it is`() {
        server.respond("/broken", status = 302)

        http.pages().use { pages ->
            assertEquals(302, pages.fetch(server.url("/broken")).status)
        }
    }

    @Test
    fun `a redirect written as a local address is refused before it is requested`() {
        server.redirect("/start", server.url("/private", host = "127.0.0.1"))
        server.respond("/private", body = "router admin".toByteArray())

        val refusal = assertFailsWith<UnsafeUrlException> {
            http.pages().use { it.fetch(server.url("/start")) }
        }

        assertEquals(UnsafeUrlReason.HostIsLocal("127.0.0.1"), refusal.reason)
        assertEquals(0, server.hits("/private"))
    }

    @Test
    fun `a relative redirect is checked as the address it resolves to`() {
        // "/private-too" is harmless as written; resolved against a page on a
        // loopback literal it is a loopback literal, and that is what is judged.
        server.redirect("/relative", "/private-too")
        server.respond("/private-too", body = "router admin".toByteArray())

        // The page itself is allowed through, by a client that allows loopback;
        // the point is what happens to the hop.
        val trusting = ImportHttp(testDns, isAllowed = { true })
        assertFailsWith<UnsafeUrlException> {
            trusting.pages().use { it.fetch(server.url("/relative", host = "127.0.0.1")) }
        }
        assertEquals(0, server.hits("/private-too"))
    }

    // Names that are written publicly and resolve privately -- the case this
    // module's address checks exist for.

    @Test
    fun `a redirect to a name that resolves to the local network is refused without connecting`() {
        server.redirect("/go", server.url("/admin", host = "router.test"))

        val refusal = assertFailsWith<UnsafeUrlException> {
            http.pages().use { it.fetch(server.url("/go")) }
        }

        assertEquals(UnsafeUrlReason.HostIsLocal("router.test"), refusal.reason)
        assertEquals(listOf("shop.test", "router.test"), lookups.distinct())
    }

    @Test
    fun `a page whose own name resolves to the local network is refused`() {
        val refusal = assertFailsWith<UnsafeUrlException> {
            http.pages().use { it.fetch(server.url("/anything", host = "router.test")) }
        }
        assertEquals(UnsafeUrlReason.HostIsLocal("router.test"), refusal.reason)
    }

    @Test
    fun `a name that resolves to the public web and the local network at once is refused`() {
        server.respond("/both", body = "either".toByteArray())

        val refusal = assertFailsWith<UnsafeUrlException> {
            http.pages().use { it.fetch(server.url("/both", host = "mixed.test")) }
        }

        assertEquals(UnsafeUrlReason.HostIsLocal("mixed.test"), refusal.reason)
        assertEquals(0, server.hits("/both"))
    }

    @Test
    fun `an address that never passes through a lookup is still checked, at the socket`() {
        // A literal is connected to directly, so the lookup check never sees it.
        // :domain refuses the literals it recognises long before this; this is
        // the check for anything it does not, and it must hold on its own.
        server.respond("/page", body = "hello".toByteArray())
        val refusing = ImportHttp(testDns, isAllowed = { false })

        val refusal = assertFailsWith<UnsafeUrlException> {
            refusing.pages().use { it.fetch(server.url("/page", host = "127.0.0.1")) }
        }

        assertEquals(UnsafeUrlReason.HostIsLocal("127.0.0.1"), refusal.reason)
        // Refused at the socket rather than by the lookup...
        assertTrue(lookups.isEmpty(), "looked up $lookups")
        // ...and before the request was written to it.
        assertEquals(0, server.hits("/page"))
    }

    @Test
    fun `the system proxy is not used, because a proxy would hide where a request goes`() {
        server.respond("/direct", body = "direct".toByteArray())
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            // A proxy on a port nothing listens on: using it would fail.
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        })

        try {
            // Built after the selector is swapped in: a client reads the system's
            // proxy selector when it is built, so the shared one would never see it.
            val built = ImportHttp(testDns, isPublicHere)
            built.pages().use { pages ->
                assertEquals("direct", pages.fetch(server.url("/direct")).readText())
            }
        } finally {
            ProxySelector.setDefault(previous)
        }
    }

    // Images.

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
    fun `an image redirect onto the local network is refused before it is requested`() {
        server.redirect("/cdn.jpg", server.url("/private.jpg", host = "127.0.0.1"))
        server.respond("/private.jpg", body = ByteArray(10))

        assertFailsWith<UnsafeUrlException> { http.download(server.url("/cdn.jpg"), scratch) }
        assertEquals(0, server.hits("/private.jpg"))
    }

    @Test
    fun `an image on a name that resolves to the local network is refused`() {
        val refusal = assertFailsWith<UnsafeUrlException> {
            http.download(server.url("/photo.jpg", host = "router.test"), scratch)
        }
        assertEquals(UnsafeUrlReason.HostIsLocal("router.test"), refusal.reason)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
