package com.wardrobapp.data

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The requests this app makes of Drive, built as strings rather than made.
 *
 * The same split as the update checker: what to ask for is decided here, where a
 * test can read it, and the asking happens in :app. A query with a quoting
 * mistake in it does not fail loudly -- Drive answers a *different* question and
 * returns a listing that looks perfectly reasonable, which is how a prune ends up
 * deleting from the wrong folder.
 */

/** Where the API lives. Separate hosts for metadata and for bytes. */
const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

/** The folder this app makes for itself, in the root of somebody's Drive. */
const val DRIVE_FOLDER_NAME = "Wardrobapp"

/** What Drive calls a folder. */
const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"

/** What a backup is. */
const val BACKUP_MIME = "application/zip"

/**
 * The fields worth asking for.
 *
 * Named explicitly because the default response is large and this one is read on
 * a phone's data. `size` is a string in Drive's JSON even though it is a number:
 * it is 64-bit, and JSON numbers are doubles to most readers.
 */
private const val LISTED_FIELDS = "files(id,name,modifiedTime,size)"

/**
 * List the archives in this app's folder.
 *
 * `trashed = false` because a file in the bin still comes back otherwise, and
 * restoring from a backup its owner deleted is not what anybody meant. Ordered
 * newest first by Drive, though [parseDriveBackups] sorts again rather than
 * trusting it -- the order matters for pruning, and it is one HTTP parameter away
 * from being silently wrong.
 */
fun driveListUrl(folderId: String, pageSize: Int = 100): String {
    val query = "${quoted(folderId)} in parents and trashed = false"

    return DRIVE_API_BASE + "/files" +
        "?q=" + encoded(query) +
        "&fields=" + encoded(LISTED_FIELDS) +
        "&orderBy=" + encoded("modifiedTime desc") +
        "&pageSize=" + pageSize.coerceIn(1, 1000)
}

/**
 * Find the folder this app made last time.
 *
 * Only ever finds one this app created: `drive.file` cannot see the rest of a
 * Drive, which is the point of it. A folder somebody deleted is simply not found,
 * and the caller makes another.
 */
fun driveFindFolderUrl(name: String = DRIVE_FOLDER_NAME): String {
    val query = "name = ${quoted(name)} and " +
        "mimeType = ${quoted(DRIVE_FOLDER_MIME)} and " +
        "trashed = false"

    return DRIVE_API_BASE + "/files" +
        "?q=" + encoded(query) +
        "&fields=" + encoded("files(id,name)") +
        "&pageSize=10"
}

/** Where to send the bytes of a new archive. */
fun driveUploadUrl(): String =
    DRIVE_UPLOAD_BASE + "/files?uploadType=resumable&fields=" + encoded("id,name,modifiedTime,size")

/** Where to read one back. */
fun driveDownloadUrl(fileId: String): String =
    DRIVE_API_BASE + "/files/" + encodedPath(fileId) + "?alt=media"

/** Where to delete one. */
fun driveDeleteUrl(fileId: String): String =
    DRIVE_API_BASE + "/files/" + encodedPath(fileId)

/**
 * Whether an address is one of Google's upload endpoints.
 *
 * A resumable upload starts by being told where to send the bytes: the session
 * address arrives in a `Location` header, which is a server saying "now write to
 * here". Everything else in this app checks an address it was handed before
 * following it -- the update checker does it for redirects -- and an archive is a
 * copy of the whole wardrobe, so it is worth the same care.
 *
 * HTTPS only, and no userinfo: a URL carrying one is a way of making a host look
 * like another to whoever reads it.
 */
fun isTrustedDriveEndpoint(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (!scheme.equals("https", ignoreCase = true)) return false

    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?')
    if (authority.contains('@')) return false

    val host = authority.substringBefore(':').lowercase()

    return host == GOOGLE_APIS || host.endsWith(".$GOOGLE_APIS")
}

/**
 * The domain Drive answers on.
 *
 * Matched as a domain rather than as a list of exact names, because a resumable
 * session is handed out on whichever host Google picks and that has not always
 * been the one the request went to. The subdomain is left open; the domain is
 * not, and `evilgoogleapis.com` is a different domain -- which is why this checks
 * for a dot before it rather than for the string anywhere in the name.
 */
private const val GOOGLE_APIS = "googleapis.com"

/** The metadata sent ahead of an archive's bytes. */
fun driveUploadMetadata(name: String, folderId: String): String =
    JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "mimeType" to JsonPrimitive(BACKUP_MIME),
            "parents" to JsonArray(listOf(JsonPrimitive(folderId))),
        ),
    ).toString()

/** The body that asks for this app's folder to exist. */
fun driveFolderMetadata(name: String = DRIVE_FOLDER_NAME): String =
    JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "mimeType" to JsonPrimitive(DRIVE_FOLDER_MIME),
        ),
    ).toString()

/**
 * The id out of a response that created something, or null.
 *
 * Null rather than an exception: every caller of this has just made a change on
 * somebody's Drive, and the useful thing to do about an unreadable answer is to
 * report that it did not work, not to crash on the way back.
 */
fun parseDriveFileId(text: String): String? = try {
    lenientRequestJson.parseToJsonElement(text)
        .jsonObject["id"]
        ?.jsonPrimitive
        ?.content
        ?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** The first folder in a find response, or null when this app has none yet. */
fun parseDriveFolderId(text: String): String? = try {
    lenientRequestJson.parseToJsonElement(text)
        .jsonObject["files"]
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.firstOrNull()
        ?.jsonObject
        ?.get("id")
        ?.jsonPrimitive
        ?.content
        ?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/**
 * Whether an access token is too old to use.
 *
 * [skewMillis] is spent early on purpose. A token that expires while the request
 * is in flight fails somewhere less convenient than before it was sent, and on a
 * phone's connection that window is not small.
 *
 * Unknown expiry counts as expired: refreshing a token that did not need it costs
 * one request, and using one that has expired costs a failed backup.
 */
fun accessTokenExpired(
    expiresAtMillis: Long?,
    nowMillis: Long,
    skewMillis: Long = 60_000L,
): Boolean = expiresAtMillis == null || nowMillis >= expiresAtMillis - skewMillis

private val lenientRequestJson = Json { ignoreUnknownKeys = true }

/**
 * A value inside a Drive query.
 *
 * Drive's query language quotes with apostrophes and escapes them with a
 * backslash. A name carrying one would otherwise close the string early and turn
 * the rest of the query into syntax -- which Drive either rejects or, worse,
 * reads as a question nobody asked.
 */
private fun quoted(value: String): String {
    val escaped = value
        .replace(BACKSLASH, BACKSLASH + BACKSLASH)
        .replace(APOSTROPHE, BACKSLASH + APOSTROPHE)

    return APOSTROPHE + escaped + APOSTROPHE
}

private const val BACKSLASH = "\\"
private const val APOSTROPHE = "'"

private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * A file id going into a path rather than a query.
 *
 * `URLEncoder` is built for form bodies, where a space is `+`. In a path that is
 * a literal plus sign, so it is put back.
 */
private fun encodedPath(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
