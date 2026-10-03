package com.wardrobapp.server

import com.wardrobapp.api.ServerException
import com.wardrobapp.api.ServerVersion
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerSettingsTest {

    @Test
    fun `an empty environment is the defaults`() {
        assertEquals(ServerSettings(), ServerSettings.from(emptyMap()))
    }

    @Test
    fun `every variable is read`() {
        val settings = ServerSettings.from(
            mapOf(
                "WARDROBAPP_DATA" to "/srv/wardrobe",
                "WARDROBAPP_PORT" to "9000",
                "WARDROBAPP_WEB" to "/opt/web",
                "WARDROBAPP_ALLOWED_CLIENTS" to " 172.30.32.2 , 127.0.0.1,",
                "WARDROBAPP_VERSION" to "0.1.0",
                "WARDROBAPP_BUILD" to "345",
            ),
        )

        assertEquals(
            ServerSettings(
                dataDirectory = File("/srv/wardrobe"),
                port = 9000,
                webDirectory = File("/opt/web"),
                allowedClients = setOf("172.30.32.2", "127.0.0.1"),
                version = ServerVersion("0.1.0", 345),
            ),
            settings,
        )
    }

    @Test
    fun `a blank allow list allows everyone rather than no one`() {
        // An empty set would refuse every request, which is a misconfiguration
        // that looks exactly like Home Assistant being down.
        assertNull(ServerSettings.from(mapOf("WARDROBAPP_ALLOWED_CLIENTS" to " , ")).allowedClients)
    }

    @Test
    fun `a port that is not one stops the server instead of moving it`() {
        assertFailsWith<IllegalArgumentException> { ServerSettings.from(mapOf("WARDROBAPP_PORT" to "80o")) }
        assertFailsWith<IllegalArgumentException> { ServerSettings.from(mapOf("WARDROBAPP_PORT" to "70000")) }
    }

    @Test
    fun `a request from anywhere but the allowed address is refused`() =
        serverTest(settings = ServerSettings(allowedClients = setOf("172.30.32.2"))) {
            // The test client's address is not ingress's.
            val refused = assertFailsWith<ServerException> { home.counts() }
            assertEquals(HttpStatusCode.Forbidden.value, refused.status)
        }

    @Test
    fun `a request from the allowed address is answered`() =
        serverTest(settings = ServerSettings(allowedClients = setOf("localhost", "127.0.0.1"))) {
            assertEquals(0, home.counts().items)
        }

    @Test
    fun `the photos and the page are behind the same check as the API`() =
        serverTest(settings = ServerSettings(allowedClients = setOf("172.30.32.2"))) {
            assertFailsWith<ServerException> { http.get("photos/anything.jpg") }
            assertFailsWith<ServerException> { http.get("") }
        }

    @Test
    fun `the server says which build it is`() =
        serverTest(settings = ServerSettings(version = ServerVersion("0.1.0", 345))) {
            assertEquals(ServerVersion("0.1.0", 345), storage.version())
            assertEquals(true, http.get("api/version").bodyAsText().contains("0.1.0"))
        }
}
