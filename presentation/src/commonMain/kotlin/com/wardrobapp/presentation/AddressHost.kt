package com.wardrobapp.presentation

/**
 * The host of a web address, for saying where the app is about to go.
 *
 * Display only. The decision about whether an address may be fetched is made in
 * :domain, by WebAddress and the checks behind ImportHttp, and nothing here
 * feeds it; this exists so the import's confirmation can name the site in common
 * code, where Android's Uri -- which the screen used while it was in :app -- is
 * not available. WebAddress itself stays on the JVM for now because it leans on
 * java.net.IDN for international names, and the browser never runs an import of
 * its own.
 *
 * Reads the authority after `scheme://`, drops any user information up to the
 * last `@`, and stops at the port; a bracketed IPv6 literal is kept whole,
 * brackets included, as java.net.URI gives it. Null when there is no host, so
 * the caller decides what to show instead.
 */
fun hostOfAddress(url: String): String? {
    val authority = AUTHORITY.find(url.trim())?.groupValues?.get(1) ?: return null
    val hostAndPort = authority.substringAfterLast('@')
    val host = if (hostAndPort.startsWith('[')) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) "" else hostAndPort.substring(0, close + 1)
    } else {
        hostAndPort.substringBefore(':')
    }
    return host.ifEmpty { null }
}

private val AUTHORITY = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://([^/?#]*)""")
