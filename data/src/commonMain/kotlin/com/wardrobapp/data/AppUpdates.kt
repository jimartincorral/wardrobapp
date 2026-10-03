package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    /**
     * What to tell somebody about this build, newest first.
     *
     * As published, the notes of this build alone -- everything merged since the
     * build before it. Once [updateWorthOffering] has seen it, everything since the
     * build the phone is running, which is what a reader deciding whether to
     * install actually wants and what a phone two builds behind used to miss.
     */
    val changes: List<String>,
    /**
     * Recent notes with the build each arrived in, newest first.
     *
     * What lets [changes] be cut to the phone's own build. Empty for a document
     * published before builds carried it, in which case [changes] is all there is.
     */
    val history: List<ReleaseNote> = emptyList(),
    /**
     * What to tell somebody about this build, as notes rather than lines: the
     * same cut as [changes], with each note's kind and Spanish, for a dialog
     * that shows them in the reader's language. Empty until
     * [updateWorthOffering] has seen it.
     */
    val notes: List<ReleaseNote> = emptyList(),
)

/** What a note says changed. A note that does not say is an improvement. */
enum class ReleaseNoteKind { NEW, IMPROVED, FIXED }

/** Which of the two apps a note is about: this one, or the browser's in Home Assistant. */
enum class ReleasePlatform { ANDROID, WEB }

/**
 * One changelog line, and the build that brought it.
 *
 * Written as a `Release-Note:` trailer on the commit that made the change --
 * see scripts/release-notes.py for the grammar, and for why a note that
 * predates it reads as an improvement to the phone.
 */
data class ReleaseNote(
    val build: Long,
    val text: String,
    val kind: ReleaseNoteKind = ReleaseNoteKind.IMPROVED,
    val platforms: Set<ReleasePlatform> = setOf(ReleasePlatform.ANDROID),
    /** The note in Spanish, when its author wrote one. */
    val textEs: String? = null,
    /**
     * Where in the app the change can be seen, as the script names it --
     * `settings`, `garment.new` -- or null. Read by :presentation, which knows
     * the app's screens; kept as written here, since a name this build does not
     * know is still not a reason to drop the note.
     */
    val destination: String? = null,
) {
    val forPhone: Boolean get() = ReleasePlatform.ANDROID in platforms
}

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
        history = (root["history"] as? JsonArray)?.mapNotNull(::parseReleaseNote) ?: emptyList(),
    )
}

/**
 * One entry of `history`, or nothing if it is not one.
 *
 * Skipped rather than fatal, like everything else in the document that is not
 * the version code or the download: a malformed changelog line is a line fewer in
 * a dialog, not a reason to stop offering updates. The build is read as a number
 * or a string for the same quoting reason as `version_code`.
 *
 * Its own key rather than a new shape for `changes`, because every phone already
 * installed reads `changes` as a list of strings and drops anything else. An
 * object there would leave each of them with an empty changelog -- for the very
 * update that would teach them the new shape.
 */
private fun parseReleaseNote(element: JsonElement): ReleaseNote? {
    val entry = element as? JsonObject ?: return null
    val build = (entry["build"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
    val text = entry.text("text") ?: return null

    // Each of the rest read leniently, for the same reason as the entry itself:
    // a kind or an app this build has never heard of costs the detail, not the
    // note. An entry that names no app this build knows predates the field, and
    // every note from then was about the phone.
    val kind = when (entry.text("kind")) {
        "new" -> ReleaseNoteKind.NEW
        "fixed" -> ReleaseNoteKind.FIXED
        else -> ReleaseNoteKind.IMPROVED
    }
    val platforms = (entry["platforms"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        ?.mapNotNull {
            when (it) {
                "android" -> ReleasePlatform.ANDROID
                "web" -> ReleasePlatform.WEB
                else -> null
            }
        }
        ?.toSet()
        ?.ifEmpty { null }
        ?: setOf(ReleasePlatform.ANDROID)

    return ReleaseNote(
        build = build,
        text = text,
        kind = kind,
        platforms = platforms,
        textEs = entry.text("text_es"),
        destination = entry.text("to"),
    )
}

/** A string field, trimmed, or null if it is absent, not a string, or blank. */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

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
 * The build worth telling somebody about, if any, with what to tell them.
 *
 * Three ways to say nothing: there is no readable document, the published build is
 * not newer than the one running, or it is one the reader has already declined.
 *
 * What is said is cut to [installed]: see [changesSince].
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

    return release.copy(
        changes = changesSince(installed, release),
        notes = notesSince(installed, release),
    )
}

/**
 * Everything [release] would change for a phone running [installed].
 *
 * Every published build replaces the one before it, and the published notes are
 * that build's own -- so a phone that last updated three builds ago would be told
 * about the third and not the first two. [AppRelease.history] carries each note
 * with its build, and this keeps the ones the phone has not got.
 *
 * Falls back to [AppRelease.changes] when nothing in the history is newer than
 * the phone, which covers two cases with one rule. A document with no history is
 * from before it existed, and its own notes are the best there is. And when every
 * build since the phone's said `Release-Note: none`, the published changes hold
 * the line that says so, which beats an empty list.
 *
 * A note claiming a build newer than the one offered is ignored: it describes
 * something this download does not contain.
 */
fun changesSince(installed: Long, release: AppRelease): List<String> =
    notesSince(installed, release).map { it.text }

/**
 * [changesSince] as notes: the phone's, newer than [installed] and no newer
 * than the build offered, each once.
 *
 * Only the phone's, now that the history also carries the browser's notes: a
 * change to the Home Assistant app is nothing a phone deciding whether to
 * update needs to hear. The fallback lines are the phone's already -- the
 * script writes `changes` for the builds that read nothing else.
 */
fun notesSince(installed: Long, release: AppRelease): List<ReleaseNote> =
    release.history
        .filter { it.forPhone && it.build > installed && it.build <= release.versionCode }
        .distinctBy { it.text }
        .ifEmpty { release.changes.map { ReleaseNote(build = release.versionCode, text = it) } }
