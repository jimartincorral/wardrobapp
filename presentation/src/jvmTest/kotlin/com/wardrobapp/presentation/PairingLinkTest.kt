package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {

    private val code = "ABCDE-FGHIJ-KMNPQ-RSTVW"

    @Test
    fun `a link reads back as what it was made from`() {
        val addresses = listOf(
            "http://homeassistant.local:8100/",
            "http://192.168.1.20:18100/",
            "http://[fd00::20]:8100/",
            "https://wardrobe.example.org/",
        )
        for (address in addresses) {
            assertEquals(PairingOffer(address, code), pairingOfferOf(pairingLinkFor(address, code)), address)
        }
    }

    @Test
    fun `the link is plain ASCII, as a QR code and a camera app both want`() {
        val link = pairingLinkFor("http://[fd00::20]:8100/", code)
        assertEquals(
            "wardrobapp://pair?address=http%3A%2F%2F%5Bfd00%3A%3A20%5D%3A8100%2F&code=ABCDE-FGHIJ-KMNPQ-RSTVW",
            link,
        )
    }

    @Test
    fun `a link is read the forgiving way an address is typed`() {
        // Whatever made the link, the address that comes out is the one the
        // form would have made of it, and the code is in capitals.
        assertEquals(
            PairingOffer("http://homeassistant.local:8100/", code),
            pairingOfferOf("  WardrobApp://pair/?address=homeassistant.local&code=abcde-fghij-kmnpq-rstvw  "),
        )
        assertEquals(
            PairingOffer("http://ha.lan:8100/", "ABCDE"),
            pairingOfferOf("wardrobapp://pair?code=ABCDE&extra=1&address=http%3A%2F%2Fha.lan#fragment"),
        )
    }

    @Test
    fun `anything else is not a pairing link`() {
        val refused = listOf(
            null,
            "",
            "https://homeassistant.local:8100/",
            // URL import's link, which shares the scheme.
            "wardrobapp://import?importUrl=https%3A%2F%2Fshop.example%2Fshirt",
            "wardrobapp://pairing?address=ha.lan&code=ABCDE",
            "wardrobapp://pair",
            "wardrobapp://pair?address=ha.lan",
            "wardrobapp://pair?code=ABCDE",
            // An address the form would refuse is refused here too.
            "wardrobapp://pair?address=ftp%3A%2F%2Fha.lan&code=ABCDE",
            "wardrobapp://pair?address=user%40ha.lan&code=ABCDE",
            // A code is letters, digits and dashes, of a length a screen shows.
            "wardrobapp://pair?address=ha.lan&code=AB",
            "wardrobapp://pair?address=ha.lan&code=ABCDE%20FGHIJ",
            "wardrobapp://pair?address=ha.lan&code=" + "A".repeat(65),
            // A broken escape is not guessed at.
            "wardrobapp://pair?address=ha.lan%2&code=ABCDE",
            "wardrobapp://pair?address=ha.lan%zz&code=ABCDE",
        )
        for (text in refused) assertNull(pairingOfferOf(text), text)
    }

    @Test
    fun `the phone is sent to the host the browser reached Home Assistant at`() {
        assertEquals("http://homeassistant.local:8100/", phoneSyncAddressFor("homeassistant.local", 8100))
        assertEquals("http://192.168.1.20:18100/", phoneSyncAddressFor("192.168.1.20", 18100))
        assertEquals("http://ha.example.org:8100/", phoneSyncAddressFor("HA.Example.org", 8100))
        // As location.hostname gives an IPv6 literal, and as it might not.
        assertEquals("http://[fd00::20]:8100/", phoneSyncAddressFor("[fd00::20]", 8100))
        assertEquals("http://[fd00::20]:8100/", phoneSyncAddressFor("fd00::20", 8100))
    }

    @Test
    fun `but not to a host no phone can sync through`() {
        // Nabu Casa forwards Home Assistant's port and nothing else.
        assertNull(phoneSyncAddressFor("abcdef0123456789.ui.nabu.casa", 8100))
        // The machine itself, which only it can reach.
        assertNull(phoneSyncAddressFor("localhost", 8100))
        assertNull(phoneSyncAddressFor("127.0.0.1", 8100))
        assertNull(phoneSyncAddressFor("[::1]", 8100))
        assertNull(phoneSyncAddressFor("", 8100))
    }
}
