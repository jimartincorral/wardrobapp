package com.wardrobapp.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real HTTP server on loopback, for the fetcher to talk to.
 *
 * The JDK's own rather than a mock-server library, because what these tests are
 * about is what actually reaches a socket -- whether a refused redirect was
 * requested at all is a question only a server can answer -- and the JDK's
 * server answers it with no new dependency.
 *
 * Every path counts its requests, so a test can say "this was never asked for"
 * as plainly as "this was".
 */
internal class TestServer : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    val port: Int get() = server.address.port

    init {
        server.start()
    }

    /** The address of [path] on this server, by loopback literal. */
    fun url(path: String): String = "http://127.0.0.1:$port$path"

    /** How many times [path] was requested. */
    fun hits(path: String): Int = hits[path]?.get() ?: 0

    /**
     * Answer [path] with [handle].
     *
     * Contexts match by prefix, so each test gives its paths distinct names; the
     * count is kept for the exact path asked for.
     */
    fun on(path: String, handle: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            hits.computeIfAbsent(exchange.requestURI.path) { AtomicInteger() }.incrementAndGet()
            // Closed by hand rather than with `use`: HttpExchange only became
            // AutoCloseable after JDK 17, which is what this project targets.
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
    }

    /** Answer [path] with a fixed response. */
    fun respond(
        path: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ) = on(path) { exchange ->
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        // -1 is the JDK's spelling of "no body"; 0 would mean chunked.
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    /** Redirect [path] to [location], exactly as written. */
    fun redirect(path: String, location: String, status: Int = 302) =
        respond(path, status, headers = mapOf("Location" to location))

    override fun close() = server.stop(0)
}
