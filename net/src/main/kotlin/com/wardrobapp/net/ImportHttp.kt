package com.wardrobapp.net

import com.wardrobapp.domain.FetchedPage
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.MAX_PAGE_CHARS
import com.wardrobapp.domain.PageFetcher
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.domain.isPublicAddress
import com.wardrobapp.domain.safeImportUrl
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * The network side of URL import.
 *
 * Everything that decides anything is in :domain; this fetches. Three things here
 * are better than what the app this replaced can do, and all three come from the
 * client rather than from cleverness:
 *
 *  - **Redirects are checked before they are followed.** React Native's fetch
 *    cannot be told to stop at one, so over there a redirect to a private address
 *    has already been requested by the time it is refused -- the README calls that
 *    out as the one residual risk. This client is told not to follow, so each hop
 *    goes through the same check as the address the user gave, before the request
 *    is made. Nothing unchecked is ever dialled, for a page or for an image.
 *  - **The address a name resolves to is checked, and is the one connected to.**
 *    :domain's checks read an address as it is written, and `192.168.1.1.nip.io`
 *    is written publicly and resolves to a router. So the lookup goes through
 *    [PublicOnlyDns], which refuses an answer naming anything private -- and the
 *    client connects only to the addresses that lookup returned. That second half
 *    is why this is OkHttp rather than `HttpURLConnection`: resolving a name to
 *    check it and then handing the name to a client that resolves it again would
 *    judge one answer and connect to another, and a resolver that answers
 *    differently the second time is exactly what DNS rebinding is.
 *  - **A page is read with a ceiling.** The body is read up to one character past
 *    what :domain will accept, so a server streaming without a `Content-Length`
 *    cannot make the app allocate a page it was always going to refuse.
 *
 * Behind the lookup check sits [ConnectedAddressCheck], which looks at the socket
 * a request is about to be written to and refuses it if the far end is private.
 * It catches what never passes through a lookup at all -- an address written as a
 * literal is connected to directly -- and is the check that does not depend on
 * knowing every way a client can arrive at an address. It runs after the
 * connection is made but before a byte of the request is sent.
 *
 * Proxies are not used for these requests, whatever the system says. Through a
 * proxy, the name is resolved by the proxy and the socket's far end is the proxy,
 * so neither check above would be looking at the server the request reaches --
 * the app would be vouching for an address it never saw. A network that only
 * allows traffic through a proxy will therefore fail to import, with the network
 * stack's own message, which is the right way round for a safety check.
 *
 * On Android an `http://` page will not load either: Android blocks cleartext by
 * default and the app does not opt in, and turning it on app-wide to reach the
 * occasional shop still on http would weaken every other request the app makes.
 * The checks still treat http as an allowed scheme, because refusing it there
 * would mean refusing it with a sentence about the local network, which would not
 * be true.
 */

/** How long the app will wait for a server, in milliseconds. */
private const val TIMEOUT_MS = 15_000L

/**
 * How many redirects the app will follow.
 *
 * Enough for the ordinary shape -- shortener, canonical host, locale path -- and
 * not enough for a loop to matter.
 */
private const val MAX_REDIRECTS = 5

/** How much of an image the app will download. */
internal const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

/** What the app says it is, so a server has something to log other than "Java". */
private const val USER_AGENT = "Wardrobapp/1.0 (Android)"

/**
 * The requests URL import makes.
 *
 * One per app, because it holds the client, and the client holds a connection
 * pool worth sharing between a page and the images it lists.
 *
 * The internal constructor is for tests, which run against a server on loopback
 * and so need a lookup that answers test names with loopback and a rule that
 * allows it. Everything else uses the public one: the system's resolver, and
 * [isPublicAddress] as the rule for both checks.
 */
class ImportHttp internal constructor(
    lookup: Dns,
    isAllowed: (InetAddress) -> Boolean,
    timeoutMs: Long = TIMEOUT_MS,
) {

    constructor() : this(Dns.SYSTEM, { isPublicAddress(it.address) })

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(PublicOnlyDns(lookup, isAllowed))
        .addNetworkInterceptor(ConnectedAddressCheck(isAllowed))
        // The one pair of settings this whole file exists for. Left at their
        // default, the client follows a 302 to wherever it points and hands back
        // a body from an address nothing checked.
        .followRedirects(false)
        .followSslRedirects(false)
        // Off so that a refusal is final. Left on, a refused connection is a
        // failure the client may retry on another route, and a check that is
        // asked twice is a check whose outcome depends on how often it is asked.
        .retryOnConnectionFailure(false)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** A fetcher for one page. Closed by the caller. */
    fun pages(): HttpPageFetcher = HttpPageFetcher(this)

    /**
     * Download one image to [destination].
     *
     * Throws for a status that is not success and for an image larger than this
     * app will store. The caller decides what a failed image means, which for an
     * import is one fewer photo rather than a failed import.
     */
    fun download(url: String, destination: File) {
        request(url, accept = "image/*,*/*;q=0.8").response.use { response ->
            if (response.code !in 200..299) throw ImageRefused(response.code)
            destination.outputStream().use { sink ->
                response.body!!.byteStream().use { source -> copyBounded(source, sink) }
            }
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

    /**
     * Request an address, following redirects only where they are allowed to lead.
     *
     * The heart of it. Each hop is resolved, put through [safeImportUrl] -- the
     * same check the address the user typed went through -- and only then
     * requested. A redirect onto the local network is refused with the request
     * unmade, which is the thing React Native's fetch cannot do.
     */
    internal fun request(
        url: String,
        accept: String,
        configure: (Request.Builder) -> Unit = {},
    ): Arrival {
        var target = url
        var redirects = 0

        while (true) {
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .also(configure)
                .build()

            val response = execute(request)

            val location = if (response.code in 300..399) response.header("Location") else null
            if (location.isNullOrBlank()) {
                // Including a 3xx with no Location, which is a server being broken
                // rather than a redirect. :domain reports it by its status.
                return Arrival(response, target)
            }

            response.close()
            if (++redirects > MAX_REDIRECTS) {
                // Reported as a page that would not load rather than as a redirect
                // problem: from the outside they are the same thing, and the status
                // is the useful half.
                throw GarmentImportException(ImportFailureReason.PageNotLoaded(response.code))
            }

            // A relative Location is resolved against where we already are, which
            // is what a browser does; the check then applies to the address that
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
     * Make one request, saying a refused address the way :domain says it.
     *
     * [UnsafeUrlReason.HostIsLocal] rather than a reason of its own, because it is
     * the same fact reached a different way -- the name *is* on this device or its
     * network, as far as anything the app can connect to is concerned -- and the
     * sentence it already has is the true one to show.
     */
    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (refused: AddressRefused) {
        throw UnsafeUrlException(UnsafeUrlReason.HostIsLocal(refused.host))
    } catch (_: SocketTimeoutException) {
        throw GarmentImportException(ImportFailureReason.PageTimedOut)
    }
}

/**
 * A page fetcher that owns its response.
 *
 * [Closeable] because the body is deliberately not read during [fetch] --
 * :domain judges the headers first and may refuse the page without ever asking
 * for it -- so something has to close the response on the paths where nothing
 * reads. The caller wraps the whole import in `use`.
 */
class HttpPageFetcher internal constructor(private val http: ImportHttp) : PageFetcher, Closeable {

    private var response: Response? = null

    override fun close() {
        response?.close()
        response = null
    }

    override fun fetch(url: String): FetchedPage {
        val arrived = http.request(
            url,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        ) { it.header("Accept-Language", "en-US,en;q=0.8") }

        response = arrived.response

        return FetchedPage(
            finalUrl = arrived.url,
            status = arrived.response.code,
            contentType = arrived.response.header("Content-Type"),
            // Absent for a compressed page as well as an undeclared one: the
            // client decompresses for us and drops the header, because the length
            // on the wire is not the length of the text. :domain then reads with
            // its ceiling instead, which is the right answer to either.
            declaredLength = arrived.response.body?.contentLength()?.takeIf { it >= 0 },
            readText = { readBoundedText(arrived.response) },
        )
    }

    /**
     * Read the body, stopping one character past what :domain will take.
     *
     * The extra character is the point: :domain refuses anything longer than its
     * bound, so stopping *at* the bound would make an over-long page look like one
     * that just fits.
     */
    private fun readBoundedText(response: Response): String {
        val buffer = CharArray(8 * 1024)
        val text = StringBuilder()

        response.body!!.byteStream().reader(charsetOf(response.header("Content-Type"))).use { reader ->
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
 * A lookup that refuses to name anything private.
 *
 * The whole answer is refused if any address in it is private, rather than the
 * private ones being dropped: a name that resolves to both a shop and a router is
 * not a shop's name, and the cost of refusing it is one import that does not work.
 */
internal class PublicOnlyDns(
    private val lookup: Dns,
    private val isAllowed: (InetAddress) -> Boolean,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = lookup.lookup(hostname)
        if (addresses.any { !isAllowed(it) }) throw AddressRefused(hostname)
        return addresses
    }
}

/**
 * The last check: what the socket is actually connected to.
 *
 * A network interceptor, so it runs once the connection exists and before the
 * request is written to it. That makes it the check nothing gets around -- a
 * literal address, a pooled connection reused for another name, anything a
 * future client version resolves some new way -- because it does not ask how the
 * address was arrived at, only what it is.
 */
private class ConnectedAddressCheck(
    private val isAllowed: (InetAddress) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val farEnd = chain.connection()?.socket()?.inetAddress
        if (farEnd == null || !isAllowed(farEnd)) throw AddressRefused(chain.request().url.host)
        return chain.proceed(chain.request())
    }
}

/**
 * An address the app will not connect to.
 *
 * An [IOException] because that is what the client expects a lookup or an
 * interceptor to fail with; anything else escapes its error handling. Turned into
 * :domain's refusal by [ImportHttp] before anybody else sees it.
 */
internal class AddressRefused(val host: String) : IOException()

/**
 * Why one image did not arrive.
 *
 * Types rather than messages, because nothing reads the message: :domain counts
 * the failures and tells the user how many photos are missing, which is all there
 * is to say about it. A sentence here would be text in Kotlin that no reader ever
 * sees and no translator could reach -- which `HardcodedStringTest` is right to
 * refuse.
 */
internal class ImageRefused(val status: Int) : IOException()

internal class ImageTooLarge : IOException()

/** A response, and the address it finally came from. */
internal class Arrival(
    val response: Response,
    val url: String,
)

/**
 * The charset a server declared, or UTF-8.
 *
 * UTF-8 rather than HTTP's historical default of ISO-8859-1: a page that does not
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
