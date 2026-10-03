package com.wardrobapp.domain

import kotlinx.serialization.Serializable

/**
 * Why an address was refused, and the sentence that says so.
 *
 * Taken out of `UrlSafety.kt` unchanged, for the same reason as
 * `ImportResults.kt`: the checks themselves run only where a page is fetched, on
 * a JVM, while the refusal is something every screen that shows one needs --
 * including the browser's, which never checks an address itself.
 */

/** Why a URL was refused, as a value rather than a sentence. */
@Serializable
sealed interface UnsafeUrlReason {

    /** Nothing was entered. */
    @Serializable
    data object UrlRequired : UnsafeUrlReason

    /** Not parseable as an address at all. */
    @Serializable
    data object NotAWebAddress : UnsafeUrlReason

    /** Parseable, but not a web page: `ftp:`, `file:`, an app's own scheme. */
    @Serializable
    data object SchemeNotAllowed : UnsafeUrlReason

    /**
     * Carries a username or password.
     *
     * A phishing shape -- `https://real.example@evil.test` reads as the first host
     * and fetches the second -- and no product page needs one.
     */
    @Serializable
    data object CredentialsInUrl : UnsafeUrlReason

    /** Names this device or something on its network. */
    @Serializable
    data class HostIsLocal(val host: String) : UnsafeUrlReason

    /** Redirected somewhere that will not parse. */
    @Serializable
    data object RedirectUnreadable : UnsafeUrlReason

    /** Redirected onto this device or its network, or off the web entirely. */
    @Serializable
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
