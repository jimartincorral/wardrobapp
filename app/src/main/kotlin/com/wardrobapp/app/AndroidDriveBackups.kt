package com.wardrobapp.app

import android.content.Context
import com.wardrobapp.data.BACKUP_MIME
import com.wardrobapp.data.DriveBackup
import com.wardrobapp.data.driveDeleteUrl
import com.wardrobapp.data.driveDownloadUrl
import com.wardrobapp.data.driveFindFolderUrl
import com.wardrobapp.data.driveFolderMetadata
import com.wardrobapp.data.driveListUrl
import com.wardrobapp.data.driveUploadMetadata
import com.wardrobapp.data.driveUploadUrl
import com.wardrobapp.data.isTrustedDriveEndpoint
import com.wardrobapp.data.parseDriveBackups
import com.wardrobapp.data.parseDriveFileId
import com.wardrobapp.data.parseDriveFolderId
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long enough for a slow connection to finish a request, short enough to give up on a dead one. */
private const val TIMEOUT_MS = 30_000

/** How much is moved between progress reports, and between checks that the caller is still there. */
private const val CHUNK = 64 * 1024

/**
 * A wardrobe's backups, in one folder of somebody's Google Drive.
 *
 * The network half of cloud backup. What to ask for is decided in :data, where a
 * test can read it ([driveListUrl], [driveUploadMetadata], [parseDriveBackups]);
 * this is the sockets, the files and the bytes -- the parts a test on a
 * workstation cannot see.
 *
 * Every method takes its access token from [token] rather than holding one, so
 * there is one place that knows whether a token is still good ([DriveAuth]) and
 * this one never has to guess.
 */
class AndroidDriveBackups(
    private val context: Context,
    private val token: suspend () -> String,
) {

    /**
     * The app's folder, made if it is not there.
     *
     * `drive.file` can only see files this app created, so "not found" here means
     * either that there has never been one or that somebody deleted it -- and the
     * answer to both is the same, which is why this does not try to tell them
     * apart.
     */
    suspend fun folderId(): String = existingFolderId() ?: createdFolderId()

    /**
     * The app's folder if there is one, without making it.
     *
     * Separate from [folderId] because listing must not create: opening the
     * settings screen while signed in asks what is in Drive, and that question
     * should not put a folder in somebody's Drive before they have ever asked for
     * a backup.
     */
    suspend fun existingFolderId(): String? = withContext(Dispatchers.IO) {
        parseDriveFolderId(get(driveFindFolderUrl()))
    }

    private suspend fun createdFolderId(): String = withContext(Dispatchers.IO) {
        val created = postJson(
            url = "https://www.googleapis.com/drive/v3/files",
            body = driveFolderMetadata(),
        )

        parseDriveFileId(created)
            ?: throw IOException(context.getString(R.string.error_drive_no_folder))
    }

    /** What is in the folder, newest first. */
    suspend fun list(folderId: String): List<DriveBackup> = withContext(Dispatchers.IO) {
        parseDriveBackups(get(driveListUrl(folderId)))
    }

    /**
     * Send an archive up.
     *
     * Resumable rather than a single multipart request: the metadata goes first
     * and is accepted or rejected before ten megabytes of somebody's photos are
     * put on a phone connection. The bytes then go in one PUT -- this does not yet
     * resume a broken upload, it only asks for a session that could.
     *
     * The address for those bytes comes back in a `Location` header, which is a
     * server saying "write to here", so it is checked before it is used.
     */
    suspend fun upload(
        archive: File,
        name: String,
        folderId: String,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val session = openUploadSession(name, folderId, archive.length())

        if (!isTrustedDriveEndpoint(session)) {
            throw IOException(context.getString(R.string.error_drive_untrusted))
        }

        val connection = open(session, "PUT")
        connection.setRequestProperty("Content-Type", BACKUP_MIME)
        connection.setFixedLengthStreamingMode(archive.length())
        connection.doOutput = true

        try {
            archive.inputStream().use { source ->
                connection.outputStream.use { sink ->
                    val buffer = ByteArray(CHUNK)
                    var sent = 0L

                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break

                        sink.write(buffer, 0, read)
                        sent += read
                        onProgress(sent.toFloat() / archive.length().coerceAtLeast(1))
                    }
                }
            }

            // Before the body, because a refused PUT has no body to read: the
            // stream throws, and what reaches the screen is a Java exception
            // carrying the session address rather than a sentence about Drive.
            connection.expectOk()

            val body = connection.readBody()
            parseDriveFileId(body)
                ?: throw IOException(context.getString(R.string.error_drive_upload_unconfirmed))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Bring one back down, into [destination].
     *
     * Written to the file the caller names and not swapped in anywhere: what
     * happens to a downloaded archive afterwards is [com.wardrobapp.data.ArchiveRestore]'s
     * decision, and it already stages, verifies and rolls back. A half-written
     * download is deleted rather than left looking like an archive.
     */
    suspend fun download(
        fileId: String,
        destination: File,
        onProgress: (Float?) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val connection = open(driveDownloadUrl(fileId), "GET")

        try {
            connection.expectOk()

            val declared = connection.contentLengthLong
            destination.outputStream().use { sink ->
                connection.inputStream.use { source ->
                    val buffer = ByteArray(CHUNK)
                    var received = 0L

                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break

                        sink.write(buffer, 0, read)
                        received += read
                        onProgress(if (declared > 0) received.toFloat() / declared else null)
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** Remove one. Used by pruning, which decides *which* in :data. */
    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        val connection = open(driveDeleteUrl(fileId), "DELETE")

        try {
            // Drive answers a delete with 204 and no body. A 404 means somebody
            // already removed it from their own folder, which `drive.file` exists
            // to let them do -- the file is gone, which is what was asked for, and
            // failing the whole prune over it would leave the rest unpruned.
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return@withContext

            if (code != HttpURLConnection.HTTP_NO_CONTENT && code != HttpURLConnection.HTTP_OK) {
                throw IOException(context.getString(R.string.error_drive_refused, code))
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Ask where to put the bytes, and be told. */
    private suspend fun openUploadSession(name: String, folderId: String, bytes: Long): String {
        val connection = open(driveUploadUrl(), "POST")
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        // So the size is refused up front rather than after it has been sent.
        connection.setRequestProperty("X-Upload-Content-Type", BACKUP_MIME)
        connection.setRequestProperty("X-Upload-Content-Length", bytes.toString())
        connection.doOutput = true

        return try {
            connection.outputStream.use { it.write(driveUploadMetadata(name, folderId).toByteArray()) }
            connection.expectOk()

            connection.getHeaderField("Location")
                ?: throw IOException(context.getString(R.string.error_drive_no_session))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun get(url: String): String {
        val connection = open(url, "GET")

        return try {
            connection.expectOk()
            connection.readBody()
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun postJson(url: String, body: String): String {
        val connection = open(url, "POST")
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.doOutput = true

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.expectOk()
            connection.readBody()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One request, carrying a token that is fresh as of now.
     *
     * Redirects are off. Everything here is addressed from constants or from a
     * `Location` this app checked itself, so a redirect is something unexpected
     * rather than something to follow.
     */
    private suspend fun open(url: String, method: String): HttpURLConnection {
        if (!isTrustedDriveEndpoint(url)) {
            throw IOException(context.getString(R.string.error_drive_untrusted))
        }

        val bearer = token()

        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
        }
    }

    /**
     * Anything but success, as something the screen can say.
     *
     * 401 is called out because it is the one a person can do something about:
     * the authorization was withdrawn, from the Google account rather than from
     * here, and the way back is to sign in again.
     */
    private fun HttpURLConnection.expectOk() {
        val code = responseCode
        if (code in 200..299) return

        throw IOException(
            when (code) {
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    context.getString(R.string.error_drive_unauthorized)
                else -> context.getString(R.string.error_drive_refused, code)
            },
        )
    }

    private fun HttpURLConnection.readBody(): String =
        inputStream.bufferedReader().use { it.readText() }
}
