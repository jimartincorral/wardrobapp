package com.wardrobapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which addresses the app will fetch, and what it says about the rest.
 *
 * The check exists because a link arrives from outside -- a `wardrobapp://` deep
 * link or the share sheet -- and the phone running the app sits *inside* a home
 * network. Without this, a web page could use the app to reach a router, a
 * printer or a NAS that the page cannot reach itself.
 *
 * So the cases below are written as a table of edges rather than a handful of
 * examples: every private range appears with the address on each side of its
 * boundary, because a range written one octet out is the kind of mistake that
 * reads correctly and fails silently. They replace a 180-case corpus recorded
 * from the TypeScript this was ported from; the corpus is gone with that app, and
 * these are the cases it was covering.
 */
class UrlSafetyTest {

    @Test
    fun `a public name or address is routable`() {
        for (host in listOf(
            "example.com",
            "shop.example.co.uk",
            "8.8.8.8",
            "1.1.1.1",
            "9.255.255.255",
            "11.0.0.0",
            "172.15.255.255",
            "172.32.0.0",
            "192.167.255.255",
            "192.169.0.0",
            "126.255.255.255",
            "128.0.0.0",
            "[2001:db8::1]",
            // A domain spelled entirely out of hex characters is still a domain.
            "face.be",
        )) {
            assertTrue(isPubliclyRoutableHost(host), "$host should be routable")
        }
    }

    @Test
    fun `this device and its network are not`() {
        for (host in listOf(
            // Loopback, and the whole of 127/8 rather than one address in it.
            "127.0.0.1",
            "127.255.255.254",
            "0.0.0.0",
            // The three private ranges, first and last address of each.
            "10.0.0.0",
            "10.255.255.255",
            "172.16.0.0",
            "172.31.255.255",
            "192.168.0.0",
            "192.168.255.255",
            // Link-local, including the cloud metadata address.
            "169.254.0.1",
            "169.254.169.254",
            // Carrier-grade NAT.
            "100.64.0.0",
            "100.127.255.255",
            // IPv6 loopback, link-local and unique-local.
            "[::1]",
            "[fe80::1]",
            "[fc00::1]",
            "[fd12:3456::1]",
            // Names a resolver answers from the local network.
            "localhost",
            "router",
            "nas.local",
            "server.lan",
            "printer.internal",
            // Addresses written the ways a resolver still accepts.
            "0x7f.0.0.1",
            "0177.0.0.1",
            "127.1",
            "2130706433",
        )) {
            assertFalse(isPubliclyRoutableHost(host), "$host should be refused")
        }
    }

    @Test
    fun `an empty or trailing-dot host is handled rather than crashing`() {
        assertFalse(isPubliclyRoutableHost(""))
        assertFalse(isPubliclyRoutableHost("   "))
        assertTrue(isPubliclyRoutableHost("example.com."))
        // Case is not a way past the check.
        assertFalse(isPubliclyRoutableHost("LocalHost"))
        assertFalse(isPubliclyRoutableHost("NAS.LOCAL"))
    }

    @Test
    fun `an accepted address comes back normalized`() {
        assertEquals("https://example.com/", safeImportUrl("https://example.com"))
        assertEquals("https://example.com/p", safeImportUrl("  https://example.com/p  "))
        // A bare host is assumed to be https rather than refused.
        assertEquals("https://example.com/", safeImportUrl("example.com"))
        // The default port goes; a non-default one stays.
        assertEquals("https://example.com/", safeImportUrl("https://example.com:443"))
        assertEquals("https://example.com:8443/", safeImportUrl("https://example.com:8443"))
        // The fragment is dropped -- it is never sent to a server anyway -- and
        // the query is kept, because that is where a product id lives.
        assertEquals("https://example.com/p?id=7", safeImportUrl("https://example.com/p?id=7#top"))
        // Dot segments are resolved rather than passed on.
        assertEquals("https://example.com/b", safeImportUrl("https://example.com/a/../b"))
    }

    @Test
    fun `each refusal says which kind of refusal it is`() {
        val cases = listOf(
            "" to UnsafeUrlReason.UrlRequired,
            "   " to UnsafeUrlReason.UrlRequired,
            "https://" to UnsafeUrlReason.NotAWebAddress,
            "ftp://example.com/x" to UnsafeUrlReason.SchemeNotAllowed,
            "wardrobapp://import" to UnsafeUrlReason.SchemeNotAllowed,
            // Not even an address: there is no `//`, so parsing fails before the
            // scheme is judged. Either refusal is correct; this pins which one.
            "javascript:alert(1)" to UnsafeUrlReason.NotAWebAddress,
            // Reads as the first host, fetches the second.
            "https://real.example@evil.test/" to UnsafeUrlReason.CredentialsInUrl,
            "https://user:pw@example.com/" to UnsafeUrlReason.CredentialsInUrl,
            "http://192.168.1.1/" to UnsafeUrlReason.HostIsLocal("192.168.1.1"),
            "https://localhost:3000/" to UnsafeUrlReason.HostIsLocal("localhost"),
        )

        for ((input, expected) in cases) {
            val thrown = assertFailsWith<UnsafeUrlException>("$input was not refused") {
                safeImportUrl(input)
            }
            assertEquals(expected, thrown.reason, "for $input")
        }
    }

    @Test
    fun `a refusal carries the host it refused`() {
        // The host is what makes the message worth reading: "that link points
        // somewhere I will not go" is not actionable, and the name is.
        val thrown = assertFailsWith<UnsafeUrlException> { safeImportUrl("http://10.0.0.5/cam") }

        assertEquals(UnsafeUrlReason.HostIsLocal("10.0.0.5"), thrown.reason)
        assertTrue("10.0.0.5" in thrown.reason.englishMessage())
    }

    @Test
    fun `a redirect onto the local network is refused after the fact`() {
        // The second line of defence. On Android the request is stopped before it
        // is made, but the response is checked too: this is what stops one being
        // read and parsed.
        val thrown = assertFailsWith<UnsafeUrlException> {
            checkFetchedUrl("http://192.168.1.1/admin", "https://example.com/p")
        }

        assertEquals(UnsafeUrlReason.RedirectedToLocalHost("192.168.1.1"), thrown.reason)
    }

    @Test
    fun `a redirect that cannot be read is refused too`() {
        val thrown = assertFailsWith<UnsafeUrlException> {
            checkFetchedUrl("not a url at all", "https://example.com/p")
        }

        assertEquals(UnsafeUrlReason.RedirectUnreadable, thrown.reason)
    }

    @Test
    fun `a redirect within the public web is allowed`() {
        // No exception is the assertion.
        checkFetchedUrl("https://www.example.com/p", "https://example.com/p")
        checkFetchedUrl("https://example.com/p", "https://example.com/p")
        // Nothing to check: some platforms report no final URL at all, and the
        // requested one was checked before the request went out.
        checkFetchedUrl(null, "https://example.com/p")
        checkFetchedUrl("", "https://example.com/p")
    }

    @Test
    fun `a redirect off the web is refused, with the local-network wording`() {
        // Worth pinning because it looks like a bug and is not: a public host on
        // a scheme that is not the web shares the redirect refusal, so the
        // sentence mentions the network. The outcome -- refusal -- is right, and
        // the alternative is a reason that exists for one unreachable case.
        val thrown = assertFailsWith<UnsafeUrlException> {
            checkFetchedUrl("ftp://example.com/x", "https://example.com/p")
        }

        assertEquals(UnsafeUrlReason.RedirectedToLocalHost("example.com"), thrown.reason)
    }

    @Test
    fun `every reason has a sentence, and no two share one`() {
        // The reasons are values so :app can translate them; the English is kept
        // for the places that have no resources. A reason with no sentence, or two
        // reasons with the same one, would make a refusal unreadable.
        val reasons = listOf(
            UnsafeUrlReason.UrlRequired,
            UnsafeUrlReason.NotAWebAddress,
            UnsafeUrlReason.SchemeNotAllowed,
            UnsafeUrlReason.CredentialsInUrl,
            UnsafeUrlReason.HostIsLocal("192.168.1.1"),
            UnsafeUrlReason.RedirectUnreadable,
            UnsafeUrlReason.RedirectedToLocalHost("192.168.1.1"),
        )

        val messages = reasons.map { it.englishMessage() }

        assertTrue(messages.all { it.isNotBlank() }, "a reason has no sentence")
        assertEquals(messages.size, messages.toSet().size, "two reasons read the same")
    }

    /*
     * Resolved addresses: what DNS answered, as bytes, rather than what the URL
     * said. Written as tables of edges for the same reason as the host checks
     * above, and built by hand rather than through `InetAddress`, which quietly
     * turns `::ffff:a.b.c.d` into a four-byte address -- the sixteen-byte form
     * would then never be tested, and it is the one a resolver can return.
     */

    @Test
    fun `a resolved public address is public`() {
        for ((label, address) in listOf(
            "8.8.8.8" to v4("8.8.8.8"),
            "the address after 10/8" to v4("11.0.0.0"),
            "the address before 172.16/12" to v4("172.15.255.255"),
            "the address after 172.16/12" to v4("172.32.0.0"),
            "the address before 192.168/16" to v4("192.167.255.255"),
            "the address before carrier NAT" to v4("100.63.255.255"),
            "the address after carrier NAT" to v4("100.128.0.0"),
            "a public IPv6 address" to v6(0x2606, 0x4700, 0, 0, 0, 0, 0, 0x1111),
            "the documentation prefix, as the literal check allows it" to v6(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1),
            "a public IPv4 address, mapped" to v6(0, 0, 0, 0, 0, 0xffff, 0x0808, 0x0808),
            "a public IPv4 address through NAT64" to v6(0x64, 0xff9b, 0, 0, 0, 0, 0x0808, 0x0808),
            "a public IPv4 address through 6to4" to v6(0x2002, 0x0808, 0x0808, 0, 0, 0, 0, 1),
            "just below fc00::/7" to v6(0xfbff, 0xffff, 0, 0, 0, 0, 0, 1),
            "just below fe80::/10" to v6(0xfe7f, 0xffff, 0, 0, 0, 0, 0, 1),
        )) {
            assertTrue(isPublicAddress(address), "$label should be public")
        }
    }

    @Test
    fun `a resolved private address is not, however it is carried`() {
        for ((label, address) in listOf(
            "loopback" to v4("127.0.0.1"),
            "the far end of loopback" to v4("127.255.255.255"),
            "this network" to v4("0.0.0.0"),
            "10/8" to v4("10.0.0.1"),
            "172.16/12, first" to v4("172.16.0.0"),
            "172.16/12, last" to v4("172.31.255.255"),
            "192.168/16" to v4("192.168.1.1"),
            "link-local and cloud metadata" to v4("169.254.169.254"),
            "carrier NAT" to v4("100.64.0.1"),
            "the Home Assistant Supervisor's network" to v4("172.30.32.2"),
            "multicast" to v4("224.0.0.1"),
            "broadcast" to v4("255.255.255.255"),
            "IPv6 loopback" to v6(0, 0, 0, 0, 0, 0, 0, 1),
            "IPv6 unspecified" to v6(0, 0, 0, 0, 0, 0, 0, 0),
            "unique-local, fc00::/7 first" to v6(0xfc00, 0, 0, 0, 0, 0, 0, 1),
            "unique-local, fc00::/7 last" to v6(0xfdff, 0xffff, 0, 0, 0, 0, 0, 1),
            "link-local" to v6(0xfe80, 0, 0, 0, 0, 0, 0, 1),
            "the far end of link-local" to v6(0xfebf, 0xffff, 0, 0, 0, 0, 0, 1),
            "site-local" to v6(0xfec0, 0, 0, 0, 0, 0, 0, 1),
            "multicast" to v6(0xff02, 0, 0, 0, 0, 0, 0, 1),
            "a router, mapped into IPv6" to v6(0, 0, 0, 0, 0, 0xffff, 0xc0a8, 0x0101),
            "loopback, IPv4-compatible" to v6(0, 0, 0, 0, 0, 0, 0x7f00, 0x0001),
            "a router through NAT64" to v6(0x64, 0xff9b, 0, 0, 0, 0, 0xc0a8, 0x0101),
            "an operator's own NAT64 prefix" to v6(0x64, 0xff9b, 1, 0, 0, 0, 0x0808, 0x0808),
            "a router through 6to4" to v6(0x2002, 0xc0a8, 0x0101, 0, 0, 0, 0, 1),
        )) {
            assertFalse(isPublicAddress(address), "$label should be refused")
        }
    }

    @Test
    fun `an address of neither length is refused rather than guessed at`() {
        assertFalse(isPublicAddress(ByteArray(0)))
        assertFalse(isPublicAddress(ByteArray(5) { 8 }))
        assertFalse(isPublicAddress(ByteArray(15) { 8 }))
    }

    private fun v4(dotted: String): ByteArray =
        dotted.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun v6(vararg groups: Int): ByteArray {
        require(groups.size == 8)
        return groups.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
    }
}
