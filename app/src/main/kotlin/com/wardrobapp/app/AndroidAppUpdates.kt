package com.wardrobapp.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.wardrobapp.data.AppRelease
import com.wardrobapp.data.isTrustedDownload
import com.wardrobapp.data.parseAppRelease
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Where the published document lives.
 *
 * A fixed address on the rolling release, alongside the APK it describes, so it
 * moves with every build without the address ever changing. Hard-coded on purpose:
 * this is the one thing about updating that must not be configurable, since
 * anything that could point it elsewhere is a way to make the app install
 * somebody else's package.
 */
private const val RELEASE_MANIFEST_URL =
    "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/latest.json"

private const val TIMEOUT_MS = 15_000

/** Enough for a document of a few dozen changelog lines, and not enough to be a payload. */
private const val MAX_MANIFEST_CHARS = 128 * 1024

/** An APK this app's size is around 20 MB; this is room to grow and a bound. */
private const val MAX_APK_BYTES = 200L * 1024L * 1024L

private const val MAX_REDIRECTS = 5

private const val USER_AGENT = "Wardrobapp/1.0 (Android)"

/** Where a downloaded build waits to be installed. */
private const val UPDATE_DIRECTORY = "updates"

/**
 * Finding out that a newer build exists, and installing it.
 *
 * This app is not on an app store, so this is the only way a phone learns that a
 * build has been published. Two requests, both to a fixed address: a small JSON
 * document describing the newest build, and -- only if somebody asks for it -- the
 * APK itself.
 *
 * What is *not* here is any judgement about the document. Reading it and deciding
 * whether it is worth mentioning is :data's ([parseAppRelease],
 * [com.wardrobapp.data.updateWorthOffering]), where it can be tested. This class
 * is the network, the disk and the installer -- the three parts a test on a
 * workstation cannot see.
 *
 * Redirects are followed by hand rather than by `HttpURLConnection`, and every hop
 * is checked against the hosts this project's releases come from. GitHub redirects
 * an asset download to its storage host, so a redirect has to be followed; letting
 * the client follow one anywhere would mean an APK arriving from an address nothing
 * checked, which for a file about to be handed to the installer is the whole risk.
 */
class AndroidAppUpdates(private val context: Context) {

    /**
     * What the newest published build is, or null.
     *
     * Null for every failure, and there are many that are not worth a word: no
     * network, a captive portal answering with a login page, GitHub down, a
     * document from a future build of this app that this one cannot read. An
     * update check nobody asked for should be silent when it does not work.
     */
    fun latestRelease(): AppRelease? = try {
        parseAppRelease(readManifest())
    } catch (_: Exception) {
        null
    }

    /**
     * Download a build, reporting progress as a fraction where the size is known.
     *
     * Into the cache directory, because a downloaded APK is worth nothing once it
     * is installed and the system may reclaim it whenever it likes. Any previous
     * download is deleted first: keeping two builds around would mean the phone
     * holding APKs nobody asked it to keep.
     *
     * [onProgress] is called with 0..1, or with null while the server has not said
     * how large the file is.
     */
    fun download(release: AppRelease, onProgress: (Float?) -> Unit = {}): File {
        if (!isTrustedDownload(release.apkUrl)) {
            throw IOException(context.getString(R.string.error_update_untrusted))
        }

        val directory = File(context.cacheDir, UPDATE_DIRECTORY)
        directory.deleteRecursively()
        directory.mkdirs()

        val file = File(directory, "wardrobapp-${release.versionCode}.apk")
        val connection = request(release.apkUrl)

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    context.getString(R.string.error_update_refused, connection.responseCode),
                )
            }

            val expected = connection.contentLengthLong.takeIf { it > 0 }
            if (expected != null && expected > MAX_APK_BYTES) {
                throw IOException(context.getString(R.string.error_update_too_large))
            }

            var written = 0L
            connection.inputStream.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break

                        written += read
                        // Checked as it arrives as well as declared: a server can
                        // say one length and send another, and the cap is about
                        // what this phone writes to its own disk.
                        if (written > MAX_APK_BYTES) {
                            throw IOException(context.getString(R.string.error_update_too_large))
                        }

                        sink.write(buffer, 0, read)
                        onProgress(expected?.let { (written.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }

            if (expected != null && written != expected) {
                // A truncated APK is refused by the installer with a message about
                // a corrupt package, which sends the reader looking in the wrong
                // place. Better to say the download did not finish.
                throw IOException(context.getString(R.string.error_update_incomplete))
            }

            return file
        } catch (error: Exception) {
            // A half-written APK is worse than none: the next attempt would find a
            // file of the right name and the wrong contents.
            file.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hand a downloaded build to Android's installer.
     *
     * The app does not install anything itself -- it cannot, and should not be able
     * to. This asks the system to, which shows its own confirmation naming this app
     * as the source, and on Android 8 and up offers the "allow from this source"
     * toggle if it has not been granted. Declining any of that leaves the phone
     * exactly as it was.
     *
     * A `content://` URI from the app's own provider, because a `file://` one has
     * thrown since Android 7, and the read grant travels with the intent rather
     * than being a permission this app holds.
     */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", apk)

        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // From a non-activity context, which is what a ViewModel has.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** The document, as text, bounded. */
    private fun readManifest(): String {
        val connection = request(RELEASE_MANIFEST_URL)

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    context.getString(R.string.error_update_refused, connection.responseCode),
                )
            }

            // Read by hand with a bound rather than `readBytes()`: this is a
            // response from the network, and "however much it sends" is not a size.
            connection.inputStream.use { source ->
                val buffer = ByteArray(8 * 1024)
                val text = StringBuilder()

                while (text.length <= MAX_MANIFEST_CHARS) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    text.append(String(buffer, 0, read, Charsets.UTF_8))
                }

                text.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Request an address, following redirects only to hosts this project releases from.
     *
     * The check is applied to every hop, including the first, so an address that
     * arrives from anywhere is refused before the request that would follow it.
     */
    private fun request(url: String): HttpURLConnection {
        var target = url
        var redirects = 0

        while (true) {
            if (!isTrustedDownload(target)) {
                throw IOException(context.getString(R.string.error_update_untrusted))
            }

            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val location = if (connection.responseCode in 300..399) {
                connection.getHeaderField("Location")
            } else {
                null
            }

            if (location.isNullOrBlank()) return connection

            connection.disconnect()
            if (++redirects > MAX_REDIRECTS) {
                throw IOException(context.getString(R.string.error_update_redirects))
            }

            target = try {
                URL(URL(target), location).toString()
            } catch (_: MalformedURLException) {
                throw IOException(context.getString(R.string.error_update_untrusted))
            }
        }
    }
}

/**
 * The build somebody has declined.
 *
 * Its own preferences file rather than the appearance one: it is not how the app is
 * drawn, and rather than the database because a restore from another phone must not
 * bring another phone's decision about which build to skip. Zero means nothing has
 * been skipped, which is what a fresh install is.
 */
class SkippedUpdate(context: Context) {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var versionCode: Long
        get() = preferences.getLong(KEY, 0L)
        set(value) = preferences.edit { putLong(KEY, value) }

    private companion object {
        const val FILE_NAME = "wardrobapp_updates"
        const val KEY = "skipped_version_code"
    }
}
