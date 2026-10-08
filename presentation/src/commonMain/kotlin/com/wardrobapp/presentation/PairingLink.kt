package com.wardrobapp.presentation

/*
 * Pairing a phone with one scan instead of two things typed.
 *
 * Settings in the browser shows a QR code; the phone reads it, from its own
 * Settings or from its camera app, and finds the address and the code filled
 * in. What the code holds is a link in the app's own scheme --
 * `wardrobapp://pair?address=...&code=...` -- rather than the two values in a
 * format of this app's own, because a link is what a camera app knows how to
 * hand to an app: pointed at one, the phone's camera offers to open Wardrobapp
 * with it, and the manifest already claims the scheme for URL import.
 *
 * Built and read here, in common code, so the browser that writes it and the
 * phone that reads it cannot disagree, and so both halves are tested without
 * either.
 */

/** What a pairing link carries: where to sync, as [syncAddressOf] writes it, and the code. */
data class PairingOffer(val address: String, val code: String)

/** The link a QR code in the browser's Settings holds, for a phone to pair from. */
fun pairingLinkFor(address: String, code: String): String =
    "$PAIRING_LINK_PREFIX?$ADDRESS_PARAMETER=${percentEncoded(address)}&$CODE_PARAMETER=${percentEncoded(code)}"

/**
 * The offer in [text], if it is a pairing link this app made; null for
 * anything else -- an import link, a shop's QR code, a link made by hand that
 * names something that is not an address.
 *
 * The address goes through [syncAddressOf], as a typed one does, so nothing
 * reaches the form that the form would not have accepted from a keyboard. The
 * code is held to what a code is made of, and its length to something a
 * screen can show; the server is what says whether it is the right one.
 *
 * Accepting an offer only fills the form in. A link can be opened by any web
 * page, and a phone that paired with whatever a page named would send its
 * whole wardrobe there, so pairing is still the person pressing Connect with
 * the address in front of them -- the rule URL import follows for the same
 * reason.
 */
fun pairingOfferOf(text: String?): PairingOffer? {
    val trimmed = text?.trim() ?: return null
    if (!trimmed.startsWith(PAIRING_LINK_PREFIX, ignoreCase = true)) return null
    val rest = trimmed.substring(PAIRING_LINK_PREFIX.length)
    // Exactly the prefix, and then a query: `wardrobapp://pairing` is not this.
    if (!rest.startsWith('?') && !rest.startsWith("/?")) return null

    val parameters = rest.substringAfter('?').substringBefore('#').split('&')
        .mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = percentDecoded(pair.substringAfter('=', "")) ?: return@mapNotNull null
            name to value
        }
        .toMap()

    val address = parameters[ADDRESS_PARAMETER]?.let(::syncAddressOf) ?: return null
    val code = parameters[CODE_PARAMETER]?.trim()?.takeIf { CODE.matches(it) } ?: return null
    return PairingOffer(address = address, code = code.uppercase())
}

/**
 * The address a phone should sync with, from the host the browser reached
 * Home Assistant at and the host port the sync port is published on; null
 * when that host is no use to a phone.
 *
 * The browser's host is the best guess there is. The server knows its port
 * mapping -- Home Assistant tells it -- but not which of its names or
 * addresses the household uses, and the one in the reader's address bar is,
 * by definition, one that works from a device in their home.
 *
 * Except where it plainly does not:
 *
 *  - Nabu Casa's remote address forwards Home Assistant's own port, and no
 *    other; a phone sent to the sync port there finds nothing.
 *  - A browser on the Home Assistant machine itself, at localhost, names a
 *    host only that machine can reach.
 *
 * In both the browser shows the code and says what address to use instead of
 * a QR code that would fail.
 */
fun phoneSyncAddressFor(browserHost: String, hostPort: Int): String? {
    val host = browserHost.trim().lowercase()
    if (host.isEmpty()) return null
    if (host.endsWith(".ui.nabu.casa")) return null
    if (host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "[::1]") return null
    val bracketed = if (':' in host && !host.startsWith('[')) "[$host]" else host
    return syncAddressOf("http://$bracketed:$hostPort")
}

private const val PAIRING_LINK_PREFIX = "wardrobapp://pair"
private const val ADDRESS_PARAMETER = "address"
private const val CODE_PARAMETER = "code"

/** What SyncSecret makes codes from, with the dashes it groups them by; generous about length. */
private val CODE = Regex("""^[0-9A-Za-z\-]{4,64}$""")

/** Everything but RFC 3986's unreserved characters, as UTF-8 percent escapes. */
private fun percentEncoded(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c.isLetterOrDigit() && b < 0x80 || c in "-._~") {
            append(c)
        } else {
            append('%')
            append(HEX[b shr 4])
            append(HEX[b and 0x0F])
        }
    }
}

/** [value] with its percent escapes, and a `+` as a space, undone; null if an escape is malformed. */
private fun percentDecoded(value: String): String? {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '%' -> {
                if (i + 3 > value.length) return null
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.add(byte.toByte())
                i += 3
            }
            c == '+' -> {
                bytes.add(' '.code.toByte())
                i++
            }
            else -> {
                bytes.addAll(c.toString().encodeToByteArray().toList())
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"
