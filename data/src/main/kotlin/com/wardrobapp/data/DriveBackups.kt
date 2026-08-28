package com.wardrobapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What this app keeps in Google Drive, and which of it is worth keeping.
 *
 * Pure: no network, no token, no Android. Drive hands back JSON and expects
 * decisions in return -- which of these files are ours, which is newest, which
 * should be deleted so the folder does not grow forever -- and every one of those
 * can be got wrong quietly. Deleting the wrong file out of somebody's Drive is
 * not a bug that reports itself.
 *
 * The requests themselves belong in :app, beside the rest of the networking.
 */

/**
 * The scope this app asks for.
 *
 * `drive.file` rather than `appDataFolder`. Both keep the app out of the rest of
 * somebody's Drive, but `appDataFolder` writes into a hidden folder its owner
 * cannot open, download, or hand to another app -- and a backup you can only get
 * at by installing the app that made it is a worse backup. The whole point of
 * this format is that it is a zip anybody can open.
 *
 * It is also why no verification review is needed: Google classes this scope as
 * non-sensitive, because it only ever reaches files the app itself created.
 */
const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** One archive, as Drive describes it. */
data class DriveBackup(
    val id: String,
    val name: String,
    /** Milliseconds since the epoch, from Drive's `modifiedTime`. */
    val modifiedAt: Long,
    /** Null when Drive did not say. Not every listing asks for the size. */
    val bytes: Long? = null,
)

/**
 * Read a Drive file listing, keeping only the archives this app wrote.
 *
 * Everything unrecognisable is dropped rather than raised: an entry with no id,
 * no name, an unreadable timestamp, or a name that is not one of ours. This is
 * somebody's Drive folder and it may hold anything at all, so the only safe
 * reading of a file this app does not recognise is that it is not this app's to
 * touch.
 *
 * Newest first, because every question asked of this list -- which to restore,
 * which to prune -- is asked in that order.
 */
fun parseDriveBackups(text: String): List<DriveBackup> {
    val root = try {
        lenientDriveJson.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return emptyList()
    }

    val files = root["files"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()

    return files.mapNotNull { entry ->
        val fields = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null

        val id = fields.text("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = fields.text("name")?.takeIf(::isBackupName) ?: return@mapNotNull null
        val modifiedAt = fields.text("modifiedTime")?.let(::epochMillisOfIso) ?: return@mapNotNull null

        DriveBackup(
            id = id,
            name = name,
            modifiedAt = modifiedAt,
            // A string in Drive's JSON, not a number: it is a 64-bit value and
            // JSON numbers are doubles to most readers.
            bytes = fields.text("size")?.toLongOrNull(),
        )
    }.sortedByDescending { it.modifiedAt }
}

/**
 * Whether a name is one this app wrote.
 *
 * The same prefix and extension [backupFilename] produces, and nothing else. A
 * file somebody renamed stops counting as ours, which is the right way round: the
 * cost of that is one archive no longer pruned automatically, and the cost of the
 * opposite is deleting a file somebody deliberately named something else.
 */
fun isBackupName(name: String): Boolean =
    name.startsWith(BACKUP_PREFIX) && name.endsWith(".zip")

/**
 * The archives to delete so that only [keep] remain.
 *
 * Oldest go first and the newest is always kept. A [keep] below one is treated as
 * one: a retention rule that empties the folder is a misconfiguration rather than
 * an instruction, and this is the code path that deletes things.
 *
 * Returns ids rather than records, so a caller cannot delete the wrong thing by
 * carrying the wrong field along.
 */
fun backupsToPrune(backups: List<DriveBackup>, keep: Int): List<String> =
    backups.sortedByDescending { it.modifiedAt }
        .drop(maxOf(keep, 1))
        .map { it.id }

private val lenientDriveJson = Json { ignoreUnknownKeys = true }

/** One string field, or null for absent, null, or a value that is not a string. */
private fun JsonObject.text(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
