package com.wardrobapp.domain

import java.net.IDN

/**
 * A web address, parsed the way a browser parses one.
 *
 * This exists because `java.net.URI` cannot be used here. The TypeScript
 * side reaches for `URL`, which is WHATWG's parser, and the two disagree about
 * nearly every case that matters to a safety check:
 *
 *   - `URI` leaves `http://0177.0.0.1/` alone; WHATWG rewrites it to
 *     `127.0.0.1`, which is the address that will actually be dialled. A check
 *     reading the un-normalized form is checking a string, not a destination.
 *   - `URI` keeps `:443` and an empty path; WHATWG drops the default port and
 *     writes `/`. The normalized string is what gets fetched and stored, so it
 *     has to match.
 *   - `URI` rejects a space outright; WHATWG percent-encodes it.
 *   - `URI` has no opinion on `1.2.3.4.5`; WHATWG calls it a parse failure,
 *     which is the difference between refusing an address and fetching one.
 *
 * So this implements the subset of the URL Standard the importer depends on, and
 * `url-safety.jsonl` holds it to `URL` case by case -- including the awkward
 * ones: numeric hosts, the two percent-encode sets, dot segments, a trailing dot,
 * and the characters JavaScript trims that Java does not.
 *
 * Two things are deliberately not modelled, because nothing here can produce
 * them and guessing would be worse than declining:
 *
 *   - IPv6 addresses are lower-cased but not re-serialized, so an uncompressed
 *     literal stays as written. WHATWG would compress it. Every IPv6 literal that
 *     matters is refused by [isPubliclyRoutableHost] before its spelling is used.
 *   - Internationalized hostnames go through [IDN], which implements IDNA2003
 *     rather than the UTS-46 that WHATWG specifies. They agree on the ordinary
 *     case -- an accented label -- and the fixture pins that; they can differ on
 *     deprecated code points, which no product page uses.
 */
internal data class WebAddress(
    val scheme: String,
    val username: String,
    val password: String,
    /** Lower-cased; an IPv6 literal keeps its brackets. Empty is possible. */
    val host: String,
    /** Absent, or absent because it was the scheme's default. */
    val port: Int?,
    val path: String,
    /** Null when the address had no `?` at all, which is not the same as empty. */
    val query: String?,
) {
    /**
     * The address as a browser would write it.
     *
     * No fragment: the parser drops it, since the importer has no use for one and
     * `safeImportUrl` clears it explicitly.
     */
    fun serialize(): String = buildString {
        append(scheme)
        append("://")
        if (username.isNotEmpty() || password.isNotEmpty()) {
            append(username)
            if (password.isNotEmpty()) {
                append(':')
                append(password)
            }
            append('@')
        }
        append(host)
        if (port != null) {
            append(':')
            append(port)
        }
        append(path)
        if (query != null) {
            append('?')
            append(query)
        }
    }
}

/** Schemes the URL Standard gives special parsing rules, with their default port. */
private val DEFAULT_PORTS = mapOf(
    "http" to 80,
    "https" to 443,
    "ws" to 80,
    "wss" to 443,
    "ftp" to 21,
)

private const val FILE_SCHEME = "file"

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

/**
 * Characters a hostname cannot contain.
 *
 * The URL Standard's forbidden host code points, plus `%`: a percent escape in a
 * domain is forbidden separately, and letting one through would mean a host that
 * reads as one name and resolves as another.
 */
private val FORBIDDEN_IN_HOST = charArrayOf(
    ' ', '\t', '\n', '\r', '#', '/', ':', '<', '>', '?', '@',
    '[', ']', '^', '|', '"', '%', '\\',
)

/**
 * Parse an address, or return null if it is not one.
 *
 * Null is WHATWG's "parse failure", which the callers turn into a refusal. The
 * distinction that matters is between a failure and an address this app happens
 * not to like: the first is unreadable, the second is readable and refused, and
 * they produce different sentences.
 */
internal fun parseWebAddress(input: String): WebAddress? {
    // What the standard does before parsing: trim leading and trailing C0
    // control or space, then remove every tab and newline wherever they are.
    val cleaned = input
        .trim { it <= ' ' }
        .filter { it != '\t' && it != '\n' && it != '\r' }

    // The fragment is dropped here rather than carried and ignored. Nothing in
    // the importer wants one, and a '#' cannot appear in a scheme, so taking it
    // off first costs nothing.
    val withoutFragment = cleaned.substringBefore('#')

    val schemeMatch = SCHEME.find(withoutFragment) ?: return null
    val scheme = schemeMatch.groupValues[1].lowercase()
    val afterScheme = withoutFragment.substring(schemeMatch.value.length)
    val special = scheme in DEFAULT_PORTS || scheme == FILE_SCHEME

    val afterSlashes: String
    val hasAuthority: Boolean
    when {
        afterScheme.startsWith("//") -> {
            afterSlashes = afterScheme.substring(2)
            hasAuthority = true
        }
        // `file:/etc/hosts` names no host, unlike every other special scheme,
        // where the standard skips any number of slashes and reads one anyway.
        scheme == FILE_SCHEME -> {
            afterSlashes = afterScheme
            hasAuthority = false
        }
        special -> {
            afterSlashes = afterScheme.dropWhile { it == '/' || it == '\\' }
            hasAuthority = true
        }
        // A scheme with no `//` is an opaque path -- `mailto:someone`. It has no
        // host, which is what makes it refusable.
        else -> {
            afterSlashes = afterScheme
            hasAuthority = false
        }
    }

    var username = ""
    var password = ""
    var host = ""
    var port: Int? = null
    var remainder = afterSlashes

    if (hasAuthority) {
        val end = afterSlashes.indexOfFirst {
            it == '/' || it == '?' || (special && it == '\\')
        }
        val authority = if (end < 0) afterSlashes else afterSlashes.substring(0, end)
        remainder = if (end < 0) "" else afterSlashes.substring(end)

        // The *last* `@`, not the first: `https://a@b@c.example` names c.example,
        // and reading the first would name a host the address does not resolve to.
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            val credentials = authority.substring(0, at)
            username = credentials.substringBefore(':')
            password = if (credentials.contains(':')) credentials.substringAfter(':') else ""
        }

        val hostAndPort = authority.substring(at + 1)
        val split = splitHostAndPort(hostAndPort) ?: return null

        port = when (val text = split.second) {
            null, "" -> null
            else -> {
                if (!text.all { it in '0'..'9' }) return null
                val number = text.toIntOrNull() ?: return null
                if (number > 65535) return null
                number
            }
        }
        if (port == DEFAULT_PORTS[scheme]) port = null

        host = normalizeHost(split.first, special) ?: return null
        // A special scheme with no host is not an address. `file` is the
        // exception the standard makes, and the only one.
        if (special && host.isEmpty() && scheme != FILE_SCHEME) return null
    }

    val pathAndQuery = if (special) remainder.replace('\\', '/') else remainder
    val hasQuery = pathAndQuery.contains('?')
    val rawQuery = if (hasQuery) pathAndQuery.substringAfter('?') else null
    val rawPath = pathAndQuery.substringBefore('?')

    // An opaque path is kept as it was written: there are no segments in it to
    // resolve, which is what "opaque" means.
    val opaque = !hasAuthority && scheme != FILE_SCHEME && !rawPath.startsWith("/")
    val path = if (opaque) {
        encode(rawPath, PATH_SET)
    } else {
        encode(removeDotSegments(rawPath.ifEmpty { "/" }), PATH_SET)
    }

    return WebAddress(
        scheme = scheme,
        username = username,
        password = password,
        host = host,
        port = port,
        path = path,
        query = rawQuery?.let { encode(it, if (special) SPECIAL_QUERY_SET else QUERY_SET) },
    )
}

/**
 * Resolve a possibly-relative reference against a base address.
 *
 * What `new URL(candidate, base)` does, for the shapes a product page actually
 * uses: an absolute URL, a scheme-relative `//cdn.example.com/x.jpg`, an absolute
 * path, a bare query, and a relative path with `..` in it. Null when the result
 * would not be an address.
 *
 * The one shape not modelled is a candidate carrying the base's own scheme and
 * then a relative path -- `https:next.jpg` -- which the standard reads as
 * relative. It is parsed here as an absolute address instead, and then almost
 * certainly refused. No page writes it; every page that means "next.jpg" writes
 * "next.jpg".
 */
internal fun resolveWebAddress(candidate: String, base: WebAddress): WebAddress? {
    val cleaned = candidate
        .trim { it <= ' ' }
        .filter { it != '\t' && it != '\n' && it != '\r' }
        .substringBefore('#')

    if (cleaned.isEmpty()) {
        return base.copy(query = null)
    }

    if (SCHEME.containsMatchIn(cleaned)) {
        return parseWebAddress(cleaned)
    }

    if (cleaned.startsWith("//")) {
        return parseWebAddress("${base.scheme}:$cleaned")
    }

    val authority = buildString {
        append(base.host)
        if (base.port != null) {
            append(':')
            append(base.port)
        }
    }

    // A bare query keeps the base's path; anything else replaces it, either
    // outright (a leading slash) or relative to the directory the base names.
    val reference = when {
        cleaned.startsWith("?") -> "${base.path}$cleaned"
        cleaned.startsWith("/") -> cleaned
        else -> base.path.substringBeforeLast('/', missingDelimiterValue = "") + "/" + cleaned
    }

    return parseWebAddress("${base.scheme}://$authority$reference")
}

/** Split `host:port`, keeping an IPv6 literal's own colons out of it. */
private fun splitHostAndPort(hostAndPort: String): Pair<String, String?>? {
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) return null
        val host = hostAndPort.substring(0, close + 1)
        val after = hostAndPort.substring(close + 1)
        return when {
            after.isEmpty() -> host to null
            after.startsWith(":") -> host to after.substring(1)
            else -> null
        }
    }

    val colon = hostAndPort.indexOf(':')
    return if (colon < 0) {
        hostAndPort to null
    } else {
        hostAndPort.substring(0, colon) to hostAndPort.substring(colon + 1)
    }
}

/**
 * A hostname as the address will actually be dialled.
 *
 * Null for a host that cannot be parsed at all, which is a different answer from
 * a host that parses and is then refused.
 */
private fun normalizeHost(raw: String, special: Boolean): String? {
    if (raw.startsWith("[")) {
        if (!raw.endsWith("]")) return null
        val inside = raw.substring(1, raw.length - 1)
        if (inside.isEmpty()) return null
        if (inside.any { it !in "0123456789abcdefABCDEF:." }) return null
        return raw.lowercase()
    }

    if (raw.isEmpty()) return ""

    if (!special) {
        // An opaque host is percent-encoded rather than parsed as a domain, and
        // never becomes an IPv4 address. Only its emptiness matters here.
        return raw.lowercase()
    }

    if (raw.any { it in FORBIDDEN_IN_HOST }) return null
    if (raw.any { it.code < 0x20 || it.code == 0x7f }) return null

    val ascii = if (raw.all { it.code < 0x80 }) {
        raw.lowercase()
    } else {
        // Punycode, without which a non-ASCII name reaches the network as raw
        // UTF-8 and resolves nowhere.
        try {
            IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    return when (val ipv4 = parseIpv4Host(ascii)) {
        Ipv4Result.NotAnAddress -> ascii
        Ipv4Result.Invalid -> null
        is Ipv4Result.Address -> ipv4.dotted
    }
}

/** Whether a hostname is an IPv4 address, is not one, or is a broken attempt. */
private sealed interface Ipv4Result {
    data class Address(val dotted: String) : Ipv4Result
    data object NotAnAddress : Ipv4Result
    data object Invalid : Ipv4Result
}

/**
 * The URL Standard's IPv4 parser.
 *
 * Present because this is the check that decides whether `0177.0.0.1` and
 * `2130706433` are recognised as this device before anything dials them. A
 * hostname whose last label is a number is an address; if it then does not parse,
 * it is a failure rather than a domain -- which is why `1.2.3.4.5` is refused
 * outright instead of being looked up.
 */
private fun parseIpv4Host(host: String): Ipv4Result {
    val parts = host.split('.').toMutableList()
    // A trailing dot is allowed and means nothing: `8.8.8.8.` is `8.8.8.8`.
    if (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.size - 1)

    if (parts.isEmpty()) return Ipv4Result.NotAnAddress
    if (parseIpv4Number(parts.last()) == null) return Ipv4Result.NotAnAddress
    if (parts.size > 4) return Ipv4Result.Invalid

    val numbers = parts.map { parseIpv4Number(it) ?: return Ipv4Result.Invalid }

    // Every part but the last is one octet; the last takes whatever octets are
    // left over, which is what makes `127.1` an address.
    for (number in numbers.dropLast(1)) {
        if (number > 255) return Ipv4Result.Invalid
    }
    val remainingOctets = 4 - (numbers.size - 1)
    var limit = 1L
    repeat(remainingOctets) { limit *= 256 }
    if (numbers.last() >= limit) return Ipv4Result.Invalid

    var address = numbers.last()
    for ((index, number) in numbers.dropLast(1).withIndex()) {
        address += number shl (8 * (3 - index))
    }

    val dotted = (3 downTo 0).joinToString(".") { ((address shr (8 * it)) and 0xff).toString() }
    return Ipv4Result.Address(dotted)
}

/**
 * One label of an IPv4 address, in whichever base it was written.
 *
 * `0x` is hexadecimal and a leading zero is octal, exactly as a resolver reads
 * them -- which is the whole reason a check on the decimal spelling alone is not
 * a check.
 */
private fun parseIpv4Number(input: String): Long? {
    if (input.isEmpty()) return null

    var text = input
    var radix = 10
    if (text.length >= 2 && (text.startsWith("0x") || text.startsWith("0X"))) {
        text = text.substring(2)
        radix = 16
    } else if (text.length > 1 && text.startsWith("0")) {
        text = text.substring(1)
        radix = 8
    }

    // `0x` and `0` alone are zero, having had their prefix taken off.
    if (text.isEmpty()) return 0

    val digits = when (radix) {
        16 -> "0123456789abcdefABCDEF"
        8 -> "01234567"
        else -> "0123456789"
    }
    if (text.any { it !in digits }) return null

    return text.toLongOrNull(radix)
}

/**
 * Resolve `.` and `..` out of a path.
 *
 * `%2e` counts as a dot, which the standard is explicit about: without that,
 * `/a/%2e%2e/b` would climb a directory past a check that only looked for `..`.
 */
private fun removeDotSegments(path: String): String {
    val segments = path.split('/')
    val output = mutableListOf<String>()

    for ((index, segment) in segments.withIndex()) {
        val last = index == segments.size - 1
        when {
            // The empty string before the leading slash.
            index == 0 -> Unit
            isDoubleDot(segment) -> {
                if (output.isNotEmpty()) output.removeAt(output.size - 1)
                if (last) output.add("")
            }
            isSingleDot(segment) -> if (last) output.add("")
            else -> output.add(segment)
        }
    }

    return "/" + output.joinToString("/")
}

private fun isSingleDot(segment: String): Boolean =
    segment == "." || segment.equals("%2e", ignoreCase = true)

private fun isDoubleDot(segment: String): Boolean = when (segment.lowercase()) {
    "..", ".%2e", "%2e.", "%2e%2e" -> true
    else -> false
}

/**
 * The percent-encode sets, which differ between a path and a query.
 *
 * Not a detail: they decide the exact string handed to the network, and the
 * fixture compares it. An apostrophe is encoded in a query and left alone in a
 * path; a backtick and braces are the other way round.
 */
private const val PATH_SET = " \"<>`{}"
private const val QUERY_SET = " \"<>"
private const val SPECIAL_QUERY_SET = " \"<>'"

/**
 * Percent-encode what has to be, and nothing else.
 *
 * An existing escape is left exactly as written, case included: `%2f` stays
 * `%2f`. Only `%` itself would need encoding to change that, and `%` is in no
 * set -- which is also why `%zz`, not an escape at all, survives unchanged.
 */
private fun encode(value: String, set: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xff
        val char = code.toChar()
        if (code < 0x20 || code >= 0x7f || char in set) {
            append('%')
            append("%02X".format(code))
        } else {
            append(char)
        }
    }
}
