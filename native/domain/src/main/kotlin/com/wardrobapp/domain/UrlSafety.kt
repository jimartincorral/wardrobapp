package com.wardrobapp.domain

/**
 * Deciding whether a URL is safe for the app to fetch.
 *
 * URL import fetches whatever it is given, and what it is given does not
 * necessarily come from the user: a deep link carries an address, a share sheet
 * hands one over, and any web page, message or QR code can produce either. The
 * page it fetches then supplies image URLs which get fetched in turn.
 *
 * A phone sits *inside* a home network, so a URL naming a private address turns
 * this app into a way to reach things the page could never reach itself -- a
 * router's admin endpoint, a printer, a NAS, anything on the LAN. That is the
 * hole these checks close: only publicly routable hosts, and the check is
 * re-applied after redirects, since a permitted URL can redirect anywhere.
 *
 * A port of `src/utils/url-safety.ts`, held to it by `url-safety.jsonl` -- 180
 * cases covering every private range on both sides of its edge, the addresses
 * written to look like something else, and what an accepted URL normalizes to.
 * Refusals are compared on the message, because the message is the only thing
 * telling someone whether to fix the address or give up on it.
 */

/** Why a URL was refused, as a value rather than a sentence. */
sealed interface UnsafeUrlReason {

    /** Nothing was entered. */
    data object UrlRequired : UnsafeUrlReason

    /** Not parseable as an address at all. */
    data object NotAWebAddress : UnsafeUrlReason

    /** Parseable, but not a web page: `ftp:`, `file:`, an app's own scheme. */
    data object SchemeNotAllowed : UnsafeUrlReason

    /**
     * Carries a username or password.
     *
     * A phishing shape -- `https://real.example@evil.test` reads as the first host
     * and fetches the second -- and no product page needs one.
     */
    data object CredentialsInUrl : UnsafeUrlReason

    /** Names this device or something on its network. */
    data class HostIsLocal(val host: String) : UnsafeUrlReason

    /** Redirected somewhere that will not parse. */
    data object RedirectUnreadable : UnsafeUrlReason

    /** Redirected onto this device or its network, or off the web entirely. */
    data class RedirectedToLocalHost(val host: String) : UnsafeUrlReason
}

/**
 * A refusal, carrying the reason so a screen can say it in the reader's language.
 *
 * The same shape as `UnrestorableArchiveException` in :data, and for the same
 * reason: the English lives in one place, the fixture compares it, and :app maps
 * the reason to a string resource.
 */
class UnsafeUrlException(val reason: UnsafeUrlReason) : Exception(reason.englishMessage())

/**
 * The sentence this reason has always produced.
 *
 * Byte-for-byte what `url-safety.ts` throws, which is what lets the fixture
 * compare messages rather than just accept-or-reject.
 */
fun UnsafeUrlReason.englishMessage(): String = when (this) {
    UnsafeUrlReason.UrlRequired ->
        "A URL is required."

    UnsafeUrlReason.NotAWebAddress ->
        "That does not look like a web address."

    UnsafeUrlReason.SchemeNotAllowed ->
        "Only http and https addresses can be imported."

    UnsafeUrlReason.CredentialsInUrl ->
        "That address carries a username or password, so it was not opened."

    is UnsafeUrlReason.HostIsLocal ->
        "$host is on this device or its local network, so it was not opened."

    UnsafeUrlReason.RedirectUnreadable ->
        "That address redirected somewhere unreadable."

    is UnsafeUrlReason.RedirectedToLocalHost ->
        "That address redirected to $host, on this device or its local network."
}

/** Schemes the importer will fetch. Anything else is not a web page. */
private val ALLOWED_SCHEMES = setOf("http", "https")

/**
 * Host suffixes that name something on the local network by convention.
 *
 * These resolve differently on every network, which is the point of them -- so a
 * URL using one is asking for whatever the *phone's* network calls that name.
 */
private val PRIVATE_SUFFIXES = listOf(".local", ".localhost", ".internal", ".home.arpa", ".lan")

/** Whether an address is written as `scheme://`, which decides if one is added. */
private val HAS_SCHEME = Regex("^[a-z][a-z0-9+.\\-]*://", RegexOption.IGNORE_CASE)

private val DECIMAL_OCTET = Regex("^\\d{1,3}$")
private val HEX_LABEL = Regex("^0x[0-9a-f]+$")
private val NUMERIC_LABEL = Regex("^\\d+$")
private val UNIQUE_LOCAL_IPV6 = Regex("^f[cd][0-9a-f]{2}:")
private val LINK_LOCAL_IPV6 = Regex("^fe[89ab][0-9a-f]:")
private val IPV4_MAPPED_IPV6 = Regex("^::ffff:(\\d{1,3}(?:\\.\\d{1,3}){3})$")

/** Decimal octets of an IPv4 address, or null if it is not one. */
private fun ipv4Octets(hostname: String): List<Int>? {
    val parts = hostname.split('.')
    if (parts.size != 4) return null

    val octets = parts.map { part ->
        // Rejecting anything but plain decimal on purpose: 0x7f.0.0.1 and
        // 017700000001 are both 127.0.0.1 to a resolver, and both would sail
        // past a check that only understood decimal.
        if (!DECIMAL_OCTET.matches(part)) return null
        part.toInt()
    }

    if (octets.any { it > 255 }) return null
    return octets
}

/**
 * True for an IPv4 address that is not routable on the public internet.
 *
 * The ranges are named rather than collapsed into arithmetic so each one can be
 * recognised: every one of them is somewhere a phone can reach and a web page
 * cannot.
 */
private fun isPrivateIpv4(octets: List<Int>): Boolean {
    val a = octets[0]
    val b = octets[1]
    return when {
        a == 0 -> true                          // "this network"
        a == 10 -> true                         // private
        a == 127 -> true                        // loopback
        a == 169 && b == 254 -> true            // link-local, incl. cloud metadata
        a == 172 && b in 16..31 -> true         // private
        a == 192 && b == 168 -> true            // private
        a == 100 && b in 64..127 -> true        // carrier NAT
        a == 192 && b == 0 -> true              // protocol assignments
        a == 198 && (b == 18 || b == 19) -> true // benchmarking
        a >= 224 -> true                        // multicast and reserved
        else -> false
    }
}

/** An IPv6 literal as a hostname carries it: bracketed, lowercase. */
private fun isPrivateIpv6(hostname: String): Boolean {
    if (!hostname.startsWith("[") || !hostname.endsWith("]")) return false
    val address = hostname.substring(1, hostname.length - 1).lowercase()

    if (address == "::1" || address == "::") return true
    if (UNIQUE_LOCAL_IPV6.containsMatchIn(address)) return true
    if (LINK_LOCAL_IPV6.containsMatchIn(address)) return true

    // An IPv4 address wearing an IPv6 coat: ::ffff:127.0.0.1.
    val mapped = IPV4_MAPPED_IPV6.find(address)
    if (mapped != null) {
        val octets = ipv4Octets(mapped.groupValues[1])
        return octets == null || isPrivateIpv4(octets)
    }

    return false
}

/**
 * True when a hostname is somewhere on the public internet.
 *
 * Errs towards refusing: a hostname this cannot categorise is refused rather
 * than fetched, because the cost of a false refusal is one import that does not
 * work, and the cost of a false pass is the app acting on the user's network.
 */
fun isPubliclyRoutableHost(hostname: String): Boolean {
    val host = hostname.trim().lowercase().removeSuffix(".")
    if (host.isEmpty()) return false

    if (PRIVATE_SUFFIXES.any { host.endsWith(it) }) return false

    if (isPrivateIpv6(host)) return false
    // Any other IPv6 literal: allowed, having failed the private tests above.
    if (host.startsWith("[")) return true

    val octets = ipv4Octets(host)
    if (octets != null) return !isPrivateIpv4(octets)

    // A bare name with no dot is an intranet name -- "router", "nas",
    // "localhost" -- resolved by whatever the phone's network says it is. A real
    // product page has a domain. (This is what refuses "localhost"; an explicit
    // check for it would be a branch no test could reach.)
    if (!host.contains('.')) return false

    // An address written in a form a resolver accepts and the check above does
    // not: 0x7f.0.0.1, 0177.0.0.1, 127.1. Recognised by every label being
    // numeric or hex-prefixed rather than by the characters used, since a real
    // domain can be spelled entirely out of a-f -- face.be is a domain, not an
    // address.
    if (host.split('.').all { NUMERIC_LABEL.matches(it) || HEX_LABEL.matches(it) }) {
        return false
    }

    return true
}

/**
 * Normalize a URL for import, refusing anything the app should not fetch.
 *
 * Throws [UnsafeUrlException] with something worth showing, since every rejection
 * here is a URL somebody typed, shared or linked.
 */
fun safeImportUrl(input: String): String {
    val trimmed = input.jsTrim()
    if (trimmed.isEmpty()) throw UnsafeUrlException(UnsafeUrlReason.UrlRequired)

    val withScheme = if (HAS_SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

    val url = parseWebAddress(withScheme)
        ?: throw UnsafeUrlException(UnsafeUrlReason.NotAWebAddress)

    if (url.scheme !in ALLOWED_SCHEMES) {
        throw UnsafeUrlException(UnsafeUrlReason.SchemeNotAllowed)
    }

    if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw UnsafeUrlException(UnsafeUrlReason.CredentialsInUrl)
    }

    if (!isPubliclyRoutableHost(url.host)) {
        throw UnsafeUrlException(UnsafeUrlReason.HostIsLocal(url.host))
    }

    // The fragment was dropped by the parser, which is what clearing `url.hash`
    // does on the other side.
    return url.serialize()
}

/**
 * Check where a request actually ended up.
 *
 * A permitted URL can redirect to a private one, so this is what stops the
 * *response* being read and parsed. On Android the request can also be stopped
 * before it is made -- the client is told not to follow redirects -- which is one
 * thing the port does better than the app it came from, where React Native's
 * fetch offers no way to interrupt a redirect and one request therefore reaches
 * the target. Here nothing does; this is the second line rather than the only one.
 *
 * Note what the last branch does with a public host on a scheme that is not the
 * web: `ftp://example.com/` is refused with the local-network sentence, because
 * the two conditions share one message. Faithful to the TypeScript, which the
 * fixture pins -- and the outcome, a refusal, is right either way.
 */
fun checkFetchedUrl(finalUrl: String?, requestedUrl: String) {
    // Some platforms leave the final URL empty; there is nothing to check then,
    // and the requested URL was already checked.
    if (finalUrl.isNullOrEmpty() || finalUrl == requestedUrl) return

    val url = parseWebAddress(finalUrl)
        ?: throw UnsafeUrlException(UnsafeUrlReason.RedirectUnreadable)

    if (url.scheme !in ALLOWED_SCHEMES || !isPubliclyRoutableHost(url.host)) {
        throw UnsafeUrlException(UnsafeUrlReason.RedirectedToLocalHost(url.host))
    }
}

/**
 * Trim the way JavaScript trims.
 *
 * `String.trim()` in Kotlin uses `Char.isWhitespace`, which says no to a no-break
 * space and to a byte-order mark; JavaScript's says yes to both. They arrive: a
 * share sheet hands over text copied from a page, and a BOM leads a file read as
 * UTF-8. Without this the port refuses addresses the app that ships accepts.
 */
private fun String.jsTrim(): String = trim { char ->
    char.isWhitespace() || Character.isSpaceChar(char) || char == '\uFEFF'
}
