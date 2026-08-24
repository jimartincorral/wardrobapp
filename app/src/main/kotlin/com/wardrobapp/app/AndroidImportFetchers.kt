package com.wardrobapp.app

import android.content.Context
import android.net.Uri
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.FetchedPage
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImageFetcher
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.MAX_PAGE_CHARS
import com.wardrobapp.domain.PageFetcher
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.domain.safeImportUrl
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * The network side of URL import.
 *
 * Everything that decides anything is in :domain; this fetches. Two things here
 * are better than what the app this replaced can do, and both come from the platform
 * rather than from cleverness:
 *
 *  - **Redirects are checked before they are followed.** React Native's fetch
 *    cannot be told to stop at one, so over there a redirect to a private address
 *    has already been requested by the time it is refused -- the README calls that
 *    out as the one residual risk. `HttpURLConnection` can be told not to follow,
 *    so each hop goes through the same check as the address the user gave, before
 *    the request is made. Nothing unchecked is ever dialled, for a page or for an
 *    image.
 *  - **A page is read with a ceiling.** The body is read up to one character past
 *    what :domain will accept, so a server streaming without a `Content-Length`
 *    cannot make the app allocate a page it was always going to refuse.
 *
 * One thing is stricter than the app this replaced: an `http://` page will not load.
 * Android blocks cleartext by default and this app does not opt in. Turning it on
 * app-wide to reach the occasional shop still on http would weaken every other
 * request the app makes. The checks still treat http as an allowed scheme, because
 * refusing it there would mean refusing it with a sentence about the local
 * network, which would not be true.
 */

/** How long the app will wait for a server, in milliseconds. */
private const val TIMEOUT_MS = 15_000

/**
 * How many redirects the app will follow.
 *
 * Enough for the ordinary shape -- shortener, canonical host, locale path -- and
 * not enough for a loop to matter.
 */
private const val MAX_REDIRECTS = 5

/** How much of an image the app will download. */
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

/** What the app says it is, so a server has something to log other than "Java". */
private const val USER_AGENT = "Wardrobapp/1.0 (Android)"

/**
 * A page fetcher that owns its connection.
 *
 * [Closeable] because the body is deliberately not read during [fetch] --
 * :domain judges the headers first and may refuse the page without ever asking
 * for it -- so something has to close the connection on the paths where nothing
 * reads. The caller wraps the whole import in `use`.
 */
class AndroidPageFetcher : PageFetcher, Closeable {

    private var connection: HttpURLConnection? = null

    override fun close() {
        connection?.disconnect()
        connection = null
    }

    override fun fetch(url: String): FetchedPage {
        val arrived = requestFollowingRedirects(
            url,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        ) { it.setRequestProperty("Accept-Language", "en-US,en;q=0.8") }

        connection = arrived.connection

        return FetchedPage(
            finalUrl = arrived.url,
            status = arrived.status,
            contentType = arrived.connection.contentType,
            declaredLength = arrived.connection.contentLengthLong.takeIf { it >= 0 },
            readText = { readBoundedText(arrived.connection) },
        )
    }

    /**
     * Read the body, stopping one character past what :domain will take.
     *
     * The extra character is the point: :domain refuses anything longer than its
     * bound, so stopping *at* the bound would make an over-long page look like one
     * that just fits.
     */
    private fun readBoundedText(connection: HttpURLConnection): String {
        val buffer = CharArray(8 * 1024)
        val text = StringBuilder()

        body(connection).reader(charsetOf(connection.contentType)).use { reader ->
            while (text.length <= MAX_PAGE_CHARS) {
                val read = reader.read(buffer)
                if (read < 0) break
                text.appendRange(buffer, 0, read)
            }
        }

        return text.toString()
    }
}

/**
 * Downloading one of a page's images into the wardrobe.
 *
 * Two steps, because the photo store reads through the content resolver and knows
 * nothing about the network: the bytes land in the cache first, then go through
 * exactly the path a photo picked from the gallery goes through -- decoded, turned
 * upright, scaled and re-encoded. An imported photo is therefore the same shape on
 * disk as every other one, which is what keeps backups and the storage figures
 * honest.
 */
class AndroidImageFetcher(
    private val context: Context,
    private val photos: AndroidPhotoStore,
    private val imageDirectory: String,
) : ImageFetcher {

    override fun download(url: String): String {
        val temporary = File.createTempFile("import-", null, context.cacheDir)

        return try {
            val arrived = requestFollowingRedirects(url, accept = "image/*,*/*;q=0.8")

            try {
                if (arrived.status !in 200..299) throw ImageRefused(arrived.status)
                temporary.outputStream().use { destination ->
                    body(arrived.connection).use { source -> copyBounded(source, destination) }
                }
            } finally {
                arrived.connection.disconnect()
            }

            val stored = photos.store(Uri.fromFile(temporary), UUID.randomUUID().toString())
            resolveImageRef(stored, imageDirectory)
        } finally {
            // Whether or not it worked: this is a copy, and the wardrobe has its
            // own by now if anything is going to use it.
            temporary.delete()
        }
    }

    private fun copyBounded(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L

        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMAGE_BYTES) throw ImageTooLarge()
            destination.write(buffer, 0, read)
        }
    }
}

/**
 * Why one image did not arrive.
 *
 * Types rather than messages, because nothing reads the message: :domain counts
 * the failures and tells the user how many photos are missing, which is all there
 * is to say about it. A sentence here would be text in Kotlin that no reader ever
 * sees and no translator could reach -- which `HardcodedStringTest` is right to
 * refuse.
 */
private class ImageRefused(val status: Int) : IOException()

private class ImageTooLarge : IOException()

/** A connection that answered, and the address it finally answered from. */
private class Arrival(
    val connection: HttpURLConnection,
    val url: String,
    val status: Int,
)

/**
 * Request an address, following redirects only where they are allowed to lead.
 *
 * The heart of it. Each hop is resolved, put through [safeImportUrl] -- the same
 * check the address the user typed went through -- and only then requested. A
 * redirect onto the local network is refused with the request unmade, which is the
 * thing React Native's fetch cannot do.
 */
private fun requestFollowingRedirects(
    url: String,
    accept: String,
    configure: (HttpURLConnection) -> Unit = {},
): Arrival {
    var target = url
    var redirects = 0

    while (true) {
        val connection = openConnection(target)
        connection.setRequestProperty("Accept", accept)
        configure(connection)

        val status = try {
            connection.responseCode
        } catch (_: SocketTimeoutException) {
            connection.disconnect()
            throw GarmentImportException(ImportFailureReason.PageTimedOut)
        }

        val location = if (status in 300..399) connection.getHeaderField("Location") else null
        if (location.isNullOrBlank()) {
            // Including a 3xx with no Location, which is a server being broken
            // rather than a redirect. :domain reports it by its status.
            return Arrival(connection, target, status)
        }

        connection.disconnect()
        if (++redirects > MAX_REDIRECTS) {
            // Reported as a page that would not load rather than as a redirect
            // problem: from the outside they are the same thing, and the status is
            // the useful half.
            throw GarmentImportException(ImportFailureReason.PageNotLoaded(status))
        }

        // A relative Location is resolved against where we already are, which is
        // what a browser does; the check then applies to the address that
        // resolution produced rather than to the fragment the server sent.
        val resolved = try {
            URL(URL(target), location).toString()
        } catch (_: MalformedURLException) {
            throw UnsafeUrlException(UnsafeUrlReason.RedirectUnreadable)
        }
        target = safeImportUrl(resolved)
    }
}

/**
 * A connection that will not follow a redirect on its own.
 *
 * The one setting this whole file exists for. Left at its default, the client
 * follows a 302 to wherever it points and hands back a body from an address
 * nothing checked.
 */
private fun openConnection(url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.setRequestProperty("User-Agent", USER_AGENT)
    // Asked for explicitly so that it can be decoded explicitly below: the
    // implicit handling only applies while the header is left alone, and it is not.
    connection.setRequestProperty("Accept-Encoding", "gzip")
    return connection
}

/** The response body, decompressed if the server compressed it. */
private fun body(connection: HttpURLConnection): InputStream {
    val stream = connection.inputStream
    return if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
        GZIPInputStream(stream)
    } else {
        stream
    }
}

/**
 * The charset a server declared, or UTF-8.
 *
 * UTF-8 rather than the JDK's HTTP default of ISO-8859-1: a page that does not
 * declare one is far more likely to be UTF-8, and getting it wrong shows up as
 * mojibake in a garment's name.
 */
private fun charsetOf(contentType: String?): Charset {
    val declared = contentType
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"', ' ')

    return try {
        declared?.takeIf { it.isNotEmpty() }?.let { Charset.forName(it) } ?: Charsets.UTF_8
    } catch (_: Exception) {
        // An unknown or malformed charset name is a page being wrong about
        // itself, which is not a reason to refuse it.
        Charsets.UTF_8
    }
}
