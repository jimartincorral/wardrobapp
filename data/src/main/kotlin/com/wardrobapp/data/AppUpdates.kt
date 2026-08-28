package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Knowing that a newer build of the app exists.
 *
 * This app is not on an app store, so nothing tells a phone that a build has been
 * published: every release goes to one rolling GitHub release, and the APK there is
 * replaced each time main moves. What the app can do is read a small document
 * published beside that APK and compare it with the build it is running.
 *
 * Here rather than in :app because it is parsing and a comparison -- exactly the
 * shape that is wrong in a way nobody notices until a phone stops offering updates
 * or starts offering one it already has. The fetching, the download and the
 * installer are :app's, and are the parts no test can see.
 */

/** What the published document says about the newest build. */
data class AppRelease(
    /**
     * The build number, which is what "newer" means here.
     *
     * CI derives it from the run number, so it only ever goes up, and Android
     * refuses to install a package whose code is lower than the installed one --
     * so it is the same number the phone would use to accept or refuse the APK.
     */
    val versionCode: Long,
    /** For the reader: "1.1.0", the same string Settings shows. */
    val versionName: String,
    /** Where the APK is. Checked against [TRUSTED_DOWNLOAD_HOSTS] before it is kept. */
    val apkUrl: String,
    /** What changed since the build the phone is running, newest first. */
    val changes: List<String>,
)

/**
 * The only hosts an APK may be downloaded from.
 *
 * The document is fetched over HTTPS from a fixed address, so the only way a URL
 * inside it points somewhere else is if that address is serving something it
 * should not -- but an app that downloads and installs a package must not take
 * even that on trust. GitHub serves release assets from `github.com` and redirects
 * them to `objects.githubusercontent.com`, so those are what is allowed.
 */
private val TRUSTED_DOWNLOAD_HOSTS = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Read the published document, or nothing.
 *
 * Null for everything that is not a document this app can act on: text that is not
 * JSON, a missing or unreadable version code, a download address that is not
 * HTTPS on a host that serves this project's releases. Null rather than an
 * exception because there is one thing to do about all of it -- say nothing, and
 * check again next time. A failed update check is not news.
 *
 * `version_code` is accepted as a number or a string, because a document written
 * by a shell script is one quoting accident away from the second.
 */
fun parseAppRelease(text: String): AppRelease? {
    val root = try {
        lenientJson.parseToJsonElement(text)
    } catch (_: Exception) {
        return null
    }

    if (root !is JsonObject) return null

    val versionCode = (root["version_code"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
    if (versionCode <= 0) return null

    val apkUrl = (root["apk_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!isTrustedDownload(apkUrl)) return null

    return AppRelease(
        versionCode = versionCode,
        versionName = (root["version_name"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: "",
        apkUrl = apkUrl,
        changes = (root["changes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { line -> line.isString }?.content }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList(),
    )
}

/**
 * Whether an address is one this app will download a package from.
 *
 * Parsed rather than matched on the string: `https://github.com.example.invalid/x`
 * contains "github.com" and is a different site altogether, so the host is compared
 * whole. HTTPS only, and no credentials in the address -- a URL carrying a userinfo
 * part is a way of making one host look like another in something a person reads.
 */
fun isTrustedDownload(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (!scheme.equals("https", ignoreCase = true)) return false

    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?')
    if (authority.contains('@')) return false

    val host = authority.substringBefore(':').lowercase()
    return host in TRUSTED_DOWNLOAD_HOSTS
}

/**
 * The build worth telling somebody about, if any.
 *
 * Three ways to say nothing: there is no readable document, the published build is
 * not newer than the one running, or it is one the reader has already declined.
 *
 * [skipped] is the version code of the build that was skipped, and skipping is
 * "not this one" rather than "no more of these": a build newer than the skipped one
 * is offered, because the next one is a different decision. Zero means nothing has
 * been skipped.
 */
fun updateWorthOffering(installed: Long, skipped: Long, release: AppRelease?): AppRelease? {
    if (release == null) return null
    if (release.versionCode <= installed) return null
    if (release.versionCode <= skipped) return null

    return release
}

/**
 * What the certificate on a downloaded build says about installing it.
 *
 * Android replaces an installed app only with a build signed by the same key, so a
 * build signed with a different one is not an upgrade the phone can apply. It is an
 * install the system refuses, and the refusal arrives as "App not installed" from
 * the system installer rather than from anything this app can put words around --
 * which is why it is worth knowing before the installer is ever opened.
 */
enum class SigningChange {
    /** The same key. An ordinary upgrade, installed over what is already there. */
    SAME_KEY,

    /** A different key. Android will refuse it, and only a reinstall gets there. */
    NEW_KEY,

    /**
     * One of the two certificates could not be read.
     *
     * Treated as "carry on and install" rather than as a new key. A phone that
     * cannot read a certificate would otherwise be told at every launch, and for
     * every ordinary build, that it needs to uninstall the app and restore a
     * backup -- which is worse advice than the refusal it would otherwise have read
     * off the system installer, and is wrong every time the key has not changed.
     */
    UNREADABLE,
}

/**
 * Compare the certificate a downloaded build carries with this app's own.
 *
 * Digests as text rather than as bytes: reading a certificate off a package means
 * one API below Android 9 and another above it, and both hand back something that
 * has to be hashed anyway. Comparing the hex is the same comparison with less code
 * on either side of that split.
 *
 * Punctuation and case are stripped first, because the same digest is written both
 * ways -- `keytool` prints colon-separated uppercase, and a digest built here is
 * plain lowercase -- and two spellings of one fingerprint must not read as two keys.
 */
fun signingChange(installed: String?, downloaded: String?): SigningChange {
    val running = withoutPunctuation(installed)
    val offered = withoutPunctuation(downloaded)

    if (running.isEmpty() || offered.isEmpty()) return SigningChange.UNREADABLE

    return if (running == offered) SigningChange.SAME_KEY else SigningChange.NEW_KEY
}

private fun withoutPunctuation(digest: String?): String =
    digest.orEmpty().filterNot { it == ':' || it.isWhitespace() }.lowercase()
