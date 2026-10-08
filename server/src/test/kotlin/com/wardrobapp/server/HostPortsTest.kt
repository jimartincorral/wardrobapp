package com.wardrobapp.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Asking Home Assistant how the sync port is published. The shape is the
 * Supervisor's `/addons/self/info`, cut down to what is read; the Supervisor
 * itself is a small HTTP server on a free port, so the request -- path, token
 * and all -- is what goes over a real socket.
 */
class HostPortsTest {

    @Test
    fun `a published port, a closed one, and anything unclear`() {
        assertEquals(HostPort.Open(8100), hostPortIn(info("""{"8100/tcp": 8100}"""), 8100))
        assertEquals(HostPort.Open(18100), hostPortIn(info("""{"8100/tcp": 18100, "8099/tcp": null}"""), 8100))
        assertEquals(HostPort.Closed, hostPortIn(info("""{"8100/tcp": null}"""), 8100))

        // Not declared, not a port, or not the Supervisor's shape at all: none
        // of these is "closed", which would send somebody to open a port that
        // may be open already.
        assertEquals(HostPort.Unknown, hostPortIn(info("""{"8200/tcp": 8200}"""), 8100))
        assertEquals(HostPort.Unknown, hostPortIn(info("""{"8100/tcp": "soon"}"""), 8100))
        assertEquals(HostPort.Unknown, hostPortIn(info("""{"8100/tcp": 70000}"""), 8100))
        assertEquals(HostPort.Unknown, hostPortIn("""{"result": "ok", "data": {}}""", 8100))
        assertEquals(HostPort.Unknown, hostPortIn("""{"result": "error", "message": "nope"}""", 8100))
        assertEquals(HostPort.Unknown, hostPortIn("<html>Bad gateway</html>", 8100))
    }

    @Test
    fun `the Supervisor is asked about this app, with the token Home Assistant gave it`() {
        var authorization: String? = null
        var path: String? = null
        supervisor({ exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            path = exchange.requestURI.path
            200 to info("""{"8100/tcp": 8100}""")
        }) { address ->
            assertEquals(HostPort.Open(8100), runBlocking { SupervisorHostPorts("token-1", address).of(8100) })
        }
        assertEquals("Bearer token-1", authorization)
        assertEquals("/addons/self/info", path)
    }

    @Test
    fun `a Supervisor that refuses, or is not there, is unknown`() {
        supervisor({ 403 to """{"result": "error"}""" }) { address ->
            assertEquals(HostPort.Unknown, runBlocking { SupervisorHostPorts("token", address).of(8100) })
        }
        // A port nothing listens on: the server that was there is stopped.
        val closed = supervisor({ 200 to "" }) { it }
        assertEquals(HostPort.Unknown, runBlocking { SupervisorHostPorts("token", closed).of(8100) })
    }

    @Test
    fun `only a server running in Home Assistant asks`() = runBlocking {
        assertEquals(HostPort.Unknown, HostPorts.from(ServerSettings()).of(8100))
        assertEquals(true, HostPorts.from(ServerSettings(supervisorToken = "t")) is SupervisorHostPorts)
    }

    private fun info(network: String) =
        """{"result": "ok", "data": {"name": "Wardrobapp", "network": $network, "ingress_port": 8099}}"""

    private fun <T> supervisor(
        answer: (com.sun.net.httpserver.HttpExchange) -> Pair<Int, String>,
        block: (String) -> T,
    ): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (status, body) = answer(exchange)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            return block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
