package com.wardrobapp.presentation

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [hostOfAddress] against java.net.URI, on the addresses an import is given.
 *
 * URI rather than Android's Uri, which is what the screen used before it moved,
 * because this module has no Android -- and because on every address here the
 * two agree, which is the property worth having: the dialog names the same site
 * it named before.
 */
class AddressHostTest {

    @Test
    fun `names the host the way java net URI does`() {
        for (address in listOf(
            "https://www.example.com/products/jacket?colour=blue#reviews",
            "http://shop.example.co.uk",
            "https://example.com:8443/path",
            "https://user:secret@example.com/",
            "https://Example.COM/Shirt",
            "https://192.0.2.10/item",
            "https://[2001:db8::1]:443/item",
            "https://xn--bcher-kva.example/",
            "https://example.com?no-path",
            "https://example.com#only-a-fragment",
        )) {
            assertEquals(URI(address).host, hostOfAddress(address), address)
        }
    }

    @Test
    fun `has no host for what is not a hierarchical address`() {
        assertNull(hostOfAddress("mailto:someone@example.com"))
        assertNull(hostOfAddress("not an address"))
        assertNull(hostOfAddress("https:///path-only"))
        assertNull(hostOfAddress(""))
    }
}
