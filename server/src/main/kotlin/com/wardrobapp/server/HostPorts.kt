package com.wardrobapp.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * config.yaml, `hassio_api: true`, with the default role, the least an app can
 * ask for. That role still lets an app do a few things to itself -- change its
 * own options, restart -- so the honest claim is not that the token can do
 * nothing else but that this server does nothing else with it: one GET, from
 * this class, and the token is never logged, never sent on and never put in
 * a response.
 */
class SupervisorHostPorts(
    private val token: String,
    private val supervisor: String = "http://supervisor",
) : HostPorts, HomeAddress {

    private val client = SupervisorClient(token, supervisor)

    override suspend fun of(containerPort: Int): HostPort =
        client.get("/addons/self/info")?.let { hostPortIn(it, containerPort) } ?: HostPort.Unknown

    override suspend fun home(): String? = client.get("/network/info")?.let(::homeAddressIn)
}

/**
 * Where a phone at home finds the Home Assistant machine, for the QR code
 * when the browser's own address is no use to a phone.
 *
 * The browser's host is the right address whenever it is one (see
 * phoneSyncAddressFor); this is for the two cases it is not. Somebody who
 * opened Home Assistant through Nabu Casa's remote address is reading
 * Settings from outside their home, or from inside it by the long way round,
 * and the address in their bar reaches Home Assistant alone. The machine's
 * own address on the home network is the one the phone wants instead, and
 * the Supervisor knows it. Null when there is nothing better than the
 * browser's host to offer, and the section says so as it did.
 */
fun interface HomeAddress {
    suspend fun home(): String?

    companion object {
        val NONE = HomeAddress { null }

        /** The Supervisor's, when the server is running as a Home Assistant app; [NONE] otherwise. */
        fun from(settings: ServerSettings): HomeAddress =
            settings.supervisorToken?.let { SupervisorHostPorts(it) } ?: NONE
    }
}

/**
 * One GET to the Supervisor, with the token, answered as the body or as
 * null for anything that is not a 200: slow, refused, not there. The two
 * questions this server asks it share the client and the way its failures
 * are read, which is the same for both -- there is no answer, and the
 * caller says what it always said.
 */
private class SupervisorClient(private val token: String, private val supervisor: String) {

    // Not through any proxy: the Supervisor is on Home Assistant's own
    // network, and a proxy configured for the outside world would only be in
    // the way.
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(TIMEOUT)
        .build()

    suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder(URI.create("${supervisor.trimEnd('/')}$path"))
                .header("Authorization", "Bearer $token")
                .timeout(TIMEOUT)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) response.body() else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}

/**
 * The machine's IPv4 address on the home network, from the Supervisor's
 * `/network/info`, or null.
 *
 * `interfaces` lists the host's, each with whether it is enabled, connected
 * and the primary one, and its IPv4 addresses with their prefix length
 * (`192.168.1.10/24`). The primary connected interface's first address is
 * taken, else the first connected interface's; the prefix is cut off.
 * IPv4 only: a phone on home Wi-Fi reaches it, and an IPv6 address would
 * have to be a global one to be any use and would be the long way to say
 * the same thing. Loopback and link-local are no use and are skipped.
 *
 * The address is whatever the router handed out, which can change; the
 * browser's own host is still preferred when it is one a phone can use,
 * and this is the fallback for when it is not.
 */
internal fun homeAddressIn(body: String): String? {
    val interfaces = runCatching {
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("interfaces") as? JsonArray
    }.getOrNull() ?: return null
    val usable = interfaces.mapNotNull { it as? JsonObject }
        .filter { it["enabled"]?.jsonPrimitive?.booleanOrNull == true && it["connected"]?.jsonPrimitive?.booleanOrNull == true }
        .sortedByDescending { it["primary"]?.jsonPrimitive?.booleanOrNull == true }
    for (network in usable) {
        val addresses = (network["ipv4"] as? JsonObject)?.get("address") as? JsonArray ?: continue
        for (entry in addresses) {
            val address = runCatching { entry.jsonPrimitive.content }.getOrNull()?.substringBefore('/') ?: continue
            if (IPV4.matches(address) && !address.startsWith("127.") && !address.startsWith("169.254.")) return address
        }
    }
    return null
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

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
