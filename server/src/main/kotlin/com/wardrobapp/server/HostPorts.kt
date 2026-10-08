package com.wardrobapp.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which port on the Home Assistant machine a port inside this container is reached on. */
sealed interface HostPort {
    /** Published on [port]: a phone on the home network reaches the container's port there. */
    data class Open(val port: Int) : HostPort

    /** Not published: nothing outside the container can reach it until somebody gives it a host port. */
    data object Closed : HostPort

    /** Nobody to ask, or no answer: the browser says what it always said, rather than guessing. */
    data object Unknown : HostPort
}

/**
 * Where the server finds out how its sync port is published, for Settings in
 * the browser to turn into an address a phone can scan.
 *
 * The comment this replaced said the host port was "the person's choice, and
 * not something the server can know to show". The first half is still true;
 * the second was only true of a server that did not ask. Home Assistant's
 * Supervisor answers an app about itself, port mapping included, and without
 * that answer the QR code would have to hold a port the reader typed into one
 * screen and was asked for again on another.
 */
fun interface HostPorts {
    suspend fun of(containerPort: Int): HostPort

    companion object {
        /** For a server with nobody to ask: run on its own, or in the tests. */
        val NONE = HostPorts { HostPort.Unknown }

        /** The Supervisor's, when the server is running as a Home Assistant app; [NONE] otherwise. */
        fun from(settings: ServerSettings): HostPorts =
            settings.supervisorToken?.let { SupervisorHostPorts(it) } ?: NONE
    }
}

/**
 * The Supervisor's answer to `GET /addons/self/info`, whose `network` maps
 * each port the app declares -- `"8100/tcp"` -- to the host port it is
 * published on, or to null while it is not.
 *
 * Asked each time Settings asks rather than once at start: it is one request
 * on a local network when somebody opens Settings, and the mapping changes
 * when they change it, which restarts the app anyway but need not be relied
 * on.
 *
 * Anything but a clear answer is [HostPort.Unknown] -- a Supervisor that is
 * slow, an app declaring no such port, a shape this was not written for. A
 * wrong "closed" would tell somebody whose port works to go and open it; an
 * unknown shows the instructions there were before any of this.
 *
 * The token is the one Home Assistant gives every app that asks for the API in
 * config.yaml, `hassio_api: true`, and the default role it comes with is what
 * reading an app's own information needs. Nothing here asks for more.
 */
class SupervisorHostPorts(
    private val token: String,
    private val supervisor: String = "http://supervisor",
) : HostPorts {

    // Not through any proxy: the Supervisor is on Home Assistant's own
    // network, and a proxy configured for the outside world would only be in
    // the way.
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(TIMEOUT)
        .build()

    override suspend fun of(containerPort: Int): HostPort = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder(URI.create("${supervisor.trimEnd('/')}/addons/self/info"))
                .header("Authorization", "Bearer $token")
                .timeout(TIMEOUT)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) HostPort.Unknown else hostPortIn(response.body(), containerPort)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            HostPort.Unknown
        } catch (_: Exception) {
            HostPort.Unknown
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}

/** What the Supervisor's [body] says about TCP [containerPort]; see [SupervisorHostPorts]. */
internal fun hostPortIn(body: String, containerPort: Int): HostPort {
    val network = runCatching {
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("network") as? JsonObject
    }.getOrNull() ?: return HostPort.Unknown
    val mapped = network["$containerPort/tcp"] ?: return HostPort.Unknown
    if (mapped is JsonNull) return HostPort.Closed
    val port = runCatching { mapped.jsonPrimitive.intOrNull ?: mapped.jsonPrimitive.content.toInt() }.getOrNull()
    return if (port != null && port in 1..65535) HostPort.Open(port) else HostPort.Unknown
}
