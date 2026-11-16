package com.wardrobapp.server

import com.wardrobapp.api.AgentAccess
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.ArchivedGarments
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.WardrobeQuery
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent port, spoken to as an MCP client speaks to it.
 *
 * Hand-written requests here, unlike the browser's tests, because the client
 * is not ours: Claude Code, or whatever else somebody points at the port, is
 * written to the specification, so these are written to it too -- each era's
 * handshake, headers and shapes as the 2025-06-18 and 2026-07-28 revisions
 * describe them -- and the tools are checked against a real wardrobe.
 */
class AgentServerTest {

    private class Setup(
        private val builder: ApplicationTestBuilder,
        val profiles: ProfileRegistry,
        val wardrobe: ServerWardrobe,
    ) {
        val token: String get() = wardrobe.agentSecret.current()
        val client get() = builder.client
        private var nextId = 1

        suspend fun post(body: String, token: String? = this.token, headers: Map<String, String> = emptyMap()): HttpResponse =
            builder.client.post(MCP_PATH) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                headers.forEach { (name, value) -> header(name, value) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        /** A 2026-07-28 request, with the `_meta` and the mirrored headers a conforming client sends. */
        suspend fun modern(
            method: String,
            params: JsonObject = JsonObject(emptyMap()),
            version: String = "2026-07-28",
            headers: Map<String, String> = modernHeaders(method, params, version),
        ): HttpResponse {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId++)
                put("method", method)
                put(
                    "params",
                    JsonObject(
                        params + mapOf(
                            "_meta" to buildJsonObject {
                                put("io.modelcontextprotocol/protocolVersion", version)
                                putJsonObject("io.modelcontextprotocol/clientInfo") {
                                    put("name", "test")
                                    put("version", "1")
                                }
                                putJsonObject("io.modelcontextprotocol/clientCapabilities") {}
                            },
                        ),
                    ),
                )
            }
            return post(body.toString(), headers = headers)
        }

        fun modernHeaders(method: String, params: JsonObject, version: String = "2026-07-28"): Map<String, String> =
            buildMap {
                put("MCP-Protocol-Version", version)
                put("Mcp-Method", method)
                (params["name"] as? JsonPrimitive)?.content?.let { put("Mcp-Name", it) }
            }

        /** A request in the initialization-based era: no `_meta`, and the negotiated version in the header. */
        suspend fun legacy(method: String, params: JsonObject = JsonObject(emptyMap()), version: String? = "2025-06-18"): HttpResponse {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId++)
                put("method", method)
                put("params", params)
            }
            return post(body.toString(), headers = version?.let { mapOf("MCP-Protocol-Version" to it) } ?: emptyMap())
        }

        /** Call [tool] the modern way; its result, which the test then reads. */
        suspend fun call(tool: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
            val response = modern("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) })
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        }

        /** Add a garment through the form's own source, with a real photo when [photo] is given. */
        suspend fun addGarment(
            category: String = "tops",
            colour: String = "#000080",
            brand: String = "",
            types: List<String> = emptyList(),
            seasons: List<Season> = emptyList(),
            materials: List<String> = emptyList(),
            photo: ByteArray = realJpeg(40, 30),
        ): String {
            val before = everything()
            val name = wardrobe.photos.store(photo)
            wardrobe.garmentForm.save(
                null,
                GarmentFormState(
                    imageUris = listOf(wardrobe.photoRef(name)),
                    bgRemovedUris = listOf(""),
                    category = category,
                    subcategories = types,
                    seasons = seasons,
                    materials = materials,
                    brand = brand,
                    colorPalette = listOf(colour),
                    colorsChosen = true,
                ),
                emptyList(),
            )
            return (everything() - before).single()
        }

        private suspend fun everything(): Set<String> = wardrobe.wardrobe
            .garments(WardrobeQuery(archived = ArchivedGarments.INCLUDED))
            .map { it.id }
            .toSet()
    }

    private fun agentTest(block: suspend Setup.() -> Unit) {
        val directory = Files.createTempDirectory("wardrobe-agents").toFile()
        val profiles = ProfileRegistry(File(directory, "server"))
        try {
            testApplication {
                application { wardrobeAgents(profiles, ServerVersion("0.11.0", 12)) }
                Setup(this, profiles, profiles.wardrobe(ProfileRegistry.FIRST)!!).block()
            }
        } finally {
            profiles.close()
            directory.deleteRecursively()
        }
    }

    // ---- Who may ask ------------------------------------------------------------

    @Test
    fun `an agent without the token is refused, and told to bring one`() = agentTest {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = null)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer realm=\"Wardrobapp\"", response.headers[HttpHeaders.WWWAuthenticate])
        assertTrue("Settings" in response.bodyAsText())
    }

    @Test
    fun `the pairing code is not an agent token`() = agentTest {
        // Two secrets for two things, taken back for different reasons: a
        // phone's code opening the agents' port would make "Make a new code"
        // the only way to shut out either.
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = wardrobe.syncSecret.current())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a profile's token reaches that profile's wardrobe and no other`() = agentTest {
        addGarment(brand = "Mine")
        val other = profiles.create("Other", owner = null)
        val theirs = profiles.wardrobe(other.id)!!

        val response = post(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_wardrobe","arguments":{}}}""",
            token = theirs.agentSecret.current(),
            headers = mapOf("MCP-Protocol-Version" to "2025-06-18"),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, response.toolJson()["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a new token shuts out the old one`() = agentTest {
        val old = token
        wardrobe.agentSecret.reset()

        assertEquals(HttpStatusCode.Unauthorized, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = old).status)
        assertEquals(HttpStatusCode.OK, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").status)
    }

    @Test
    fun `a request from a web page on another origin is refused`() = agentTest {
        val elsewhere = post(
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            headers = mapOf(HttpHeaders.Origin to "http://attacker.example"),
        )
        assertEquals(HttpStatusCode.Forbidden, elsewhere.status)

        val sameOrigin = post(
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            headers = mapOf(HttpHeaders.Origin to "http://localhost"),
        )
        assertEquals(HttpStatusCode.OK, sameOrigin.status)
    }

    // ---- The protocol, before 2026-07-28 -------------------------------------------

    @Test
    fun `a legacy client's handshake gets the version it asked for, and what the server offers`() = agentTest {
        val response = legacy("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "test"); put("version", "1") }
        }, version = null)

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.json()["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        assertEquals("wardrobapp", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("0.11.0", result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertTrue("Archived garments" in result["instructions"]!!.jsonPrimitive.content)
        // Stateless: nothing to carry from one request to the next.
        assertNull(response.headers["Mcp-Session-Id"])
    }

    @Test
    fun `a handshake asking for a version the server does not speak is offered the newest it does`() = agentTest {
        val result = legacy("initialize", buildJsonObject { put("protocolVersion", "2024-11-05") }, version = null)
            .json()["result"]!!.jsonObject

        assertEquals("2025-11-25", result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a notification is accepted with nothing to say back`() = agentTest {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `a legacy client naming a version it never negotiated is refused`() = agentTest {
        assertEquals(HttpStatusCode.BadRequest, legacy("tools/list", version = "1999-01-01").status)
    }

    @Test
    fun `there is no stream to open and no session to end`() = agentTest {
        val opened = client.get(MCP_PATH) { header(HttpHeaders.Authorization, "Bearer $token") }
        val ended = client.delete(MCP_PATH) { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.MethodNotAllowed, opened.status)
        assertEquals(HttpStatusCode.MethodNotAllowed, ended.status)
    }

    @Test
    fun `a batch is refused, since no revision this server speaks has them`() = agentTest {
        val response = post("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(-32600, response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a body that is not JSON is a parse error`() = agentTest {
        val response = post("{not json")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(-32700, response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    // ---- The protocol, 2026-07-28 ---------------------------------------------------

    @Test
    fun `discovery names every version spoken, and says who is answering`() = agentTest {
        val response = modern("server/discover")

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.json()["result"]!!.jsonObject
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26"),
            result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        assertEquals(
            "wardrobapp",
            result["_meta"]!!.jsonObject["io.modelcontextprotocol/serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertTrue(result["ttlMs"]!!.jsonPrimitive.int > 0)
    }

    @Test
    fun `headers that disagree with the body are refused`() = agentTest {
        val wrongMethod = modern("tools/list", headers = modernHeaders("server/discover", JsonObject(emptyMap())))
        assertEquals(HttpStatusCode.BadRequest, wrongMethod.status)
        assertEquals(-32020, wrongMethod.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val noVersion = modern("tools/list", headers = mapOf("Mcp-Method" to "tools/list"))
        assertEquals(-32020, noVersion.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val params = buildJsonObject { put("name", "search_wardrobe") }
        val wrongName = modern("tools/call", params, headers = modernHeaders("tools/call", params) + ("Mcp-Name" to "get_garment"))
        assertEquals(-32020, wrongName.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a tool name sent base64-encoded in its header is read decoded`() = agentTest {
        val params = buildJsonObject { put("name", "wardrobe_statistics") }
        val encoded = "=?base64?" + Base64.getEncoder().encodeToString("wardrobe_statistics".toByteArray()) + "?="

        val response = modern("tools/call", params, headers = modernHeaders("tools/call", params) + ("Mcp-Name" to encoded))

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an unsupported version is refused with the versions that are`() = agentTest {
        val response = modern("tools/list", version = "2027-01-01")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.json()["error"]!!.jsonObject
        assertEquals(-32022, error["code"]!!.jsonPrimitive.int)
        assertEquals("2027-01-01", error["data"]!!.jsonObject["requested"]!!.jsonPrimitive.content)
        assertTrue(JsonPrimitive("2026-07-28") in error["data"]!!.jsonObject["supported"]!!.jsonArray)
    }

    @Test
    fun `an unknown method is a 404 that still says it is MCP's`() = agentTest {
        val response = modern("resources/list")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(-32601, response.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    // ---- The tools ------------------------------------------------------------------

    @Test
    fun `the tools are listed in one order, and only the writing ones say they write`() = agentTest {
        val first = modern("tools/list").json()["result"]!!.jsonObject
        val second = modern("tools/list").json()["result"]!!.jsonObject
        val tools = first["tools"]!!.jsonArray.map { it.jsonObject }

        assertEquals(first["tools"], second["tools"])
        assertEquals(
            listOf(
                "search_wardrobe", "get_garment", "suggest_outfits", "list_outfits", "get_outfit",
                "wardrobe_statistics", "wardrobe_gaps", "save_outfit", "rate_outfit", "set_garment_archived",
            ),
            tools.map { it["name"]!!.jsonPrimitive.content },
        )
        val writers = tools.filter { !it["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean }
        assertEquals(listOf("save_outfit", "rate_outfit", "set_garment_archived"), writers.map { it["name"]!!.jsonPrimitive.content })
        // Nothing an agent can do here is beyond the app's own undo.
        assertTrue(tools.none { it["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.boolean })
        for (tool in tools) assertEquals("object", tool["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("complete", first["resultType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a search narrows by what the app knows a garment is, colours by name`() = agentTest {
        val jeans = addGarment(category = "bottoms", colour = "#000080", brand = "Levi's", types = listOf("Jeans"))
        addGarment(category = "tops", colour = "#FFFFFF", brand = "Uniqlo", types = listOf("T-Shirt"))

        val bottoms = call("search_wardrobe", buildJsonObject { put("category", "bottoms") }).json()
        assertEquals(listOf(jeans), bottoms.ids())

        val navy = call("search_wardrobe", buildJsonObject { put("color", "navy") }).json()
        assertEquals(listOf(jeans), navy.ids())

        // A type in whatever case the model wrote it.
        val byType = call("search_wardrobe", buildJsonObject { put("type", "jeans") }).json()
        assertEquals(listOf(jeans), byType.ids())

        val garment = navy["garments"]!!.jsonArray.single().jsonObject
        assertEquals("Levi's", garment["brand"]!!.jsonPrimitive.content)
        assertEquals(listOf("navy"), garment["colors"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(garment["archived"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a garment says what it is made of, and a search can ask by material`() = agentTest {
        val jumper = addGarment(category = "tops", types = listOf("Sweater"), materials = listOf("wool", "cashmere"))
        addGarment(category = "tops", types = listOf("T-Shirt"), materials = listOf("cotton"))

        val found = call("search_wardrobe", buildJsonObject { put("material", "wool") }).json()
        assertEquals(listOf(jumper), found.ids())

        val garment = found["garments"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("wool", "cashmere"), garment["materials"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Materials are not repeated among the tags somebody typed.
        assertNull(garment["tags"])
    }

    @Test
    fun `a search can ask for the archived garments alone`() = agentTest {
        val kept = addGarment()
        val gone = addGarment()
        call("set_garment_archived", buildJsonObject { put("garment_id", gone); put("archived", true) })

        assertEquals(listOf(kept), call("search_wardrobe").json().ids())
        assertEquals(listOf(gone), call("search_wardrobe", buildJsonObject { put("archived", "only") }).json().ids())
        assertEquals(setOf(kept, gone), call("search_wardrobe", buildJsonObject { put("archived", "included") }).json().ids().toSet())

        call("set_garment_archived", buildJsonObject { put("garment_id", gone); put("archived", false) })
        assertEquals(setOf(kept, gone), call("search_wardrobe").json().ids().toSet())
    }

    @Test
    fun `a garment comes with its photo, scaled down`() = agentTest {
        val id = addGarment(photo = realJpeg(1600, 1200))

        val result = call("get_garment", buildJsonObject { put("garment_id", id) })
        val content = result["content"]!!.jsonArray.map { it.jsonObject }

        assertEquals(id, content[0]["text"]!!.jsonPrimitive.content.let(::parse)["id"]!!.jsonPrimitive.content)
        val image = content.single { it["type"]!!.jsonPrimitive.content == "image" }
        assertEquals("image/jpeg", image["mimeType"]!!.jsonPrimitive.content)
        val picture = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content)))
        assertEquals(768, picture.width)
        assertEquals(576, picture.height)
    }

    @Test
    fun `a garment whose photo cannot be read still answers, without one`() = agentTest {
        val id = addGarment(photo = jpeg())

        val content = call("get_garment", buildJsonObject { put("garment_id", id) })["content"]!!.jsonArray

        assertEquals(1, content.size)
    }

    @Test
    fun `a mistake is told to the model as a failed result it can correct`() = agentTest {
        val missing = call("get_garment", buildJsonObject { put("garment_id", "nope") })
        assertTrue(missing["isError"]!!.jsonPrimitive.boolean)
        assertTrue("nope" in missing.text())

        val badChoice = call("search_wardrobe", buildJsonObject { put("category", "hats") })
        assertTrue(badChoice["isError"]!!.jsonPrimitive.boolean)
        assertTrue("bottoms" in badChoice.text(), "the error lists the categories there are")

        val badRating = call("rate_outfit", buildJsonObject { put("outfit_id", "x"); put("rating", 9) })
        assertTrue(badRating["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an outfit saved by an agent can be listed, rated and read back`() = agentTest {
        val top = addGarment(category = "tops", types = listOf("Shirt"))
        val bottom = addGarment(category = "bottoms", types = listOf("Chinos"))

        val saved = call("save_outfit", buildJsonObject {
            put("name", "Office Friday")
            putJsonArray("garment_ids") { add(JsonPrimitive(top)); add(JsonPrimitive(bottom)) }
            put("occasion", "work")
        }).json()
        val id = saved["outfit_id"]!!.jsonPrimitive.content

        val listed = call("list_outfits").json()["outfits"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Office Friday"), listed.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("work", listed.single()["occasion"]!!.jsonPrimitive.content)

        val rated = call("rate_outfit", buildJsonObject { put("outfit_id", id); put("rating", 4) })
        assertFalse(rated["isError"]!!.jsonPrimitive.boolean)

        val outfit = call("get_outfit", buildJsonObject { put("outfit_id", id) }).json()
        assertEquals(4, outfit["rating"]!!.jsonPrimitive.int)
        assertEquals(listOf(top, bottom), outfit["garments"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `an outfit naming a garment there is none of is not saved`() = agentTest {
        val top = addGarment()

        val result = call("save_outfit", buildJsonObject {
            put("name", "Half")
            putJsonArray("garment_ids") { add(JsonPrimitive(top)); add(JsonPrimitive("ghost")) }
        })

        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(0, call("list_outfits").json()["outfits"]!!.jsonArray.size)
    }

    @Test
    fun `suggestions come from the app's engine, made of the wardrobe's garments`() = agentTest {
        addGarment(category = "tops", colour = "#FFFFFF", types = listOf("T-Shirt"))
        addGarment(category = "bottoms", colour = "#000080", types = listOf("Jeans"))
        addGarment(category = "shoes", colour = "#000000", types = listOf("Sneakers"))

        val result = call("suggest_outfits", buildJsonObject { put("occasion", "casual"); put("count", 1) })

        assertFalse(result["isError"]!!.jsonPrimitive.boolean, result.text())
        val suggestions = result.json()["suggestions"]!!.jsonArray
        assertTrue(suggestions.size <= 1)
        for (suggestion in suggestions) {
            assertTrue(suggestion.jsonObject["garments"]!!.jsonArray.isNotEmpty())
        }
    }

    @Test
    fun `statistics name colours rather than hexes`() = agentTest {
        addGarment(colour = "#000080")
        addGarment(colour = "#000080")

        val statistics = call("wardrobe_statistics").json()

        assertEquals(2, statistics["in_use"]!!.jsonPrimitive.int)
        val colors = statistics["colors"]!!.jsonArray.map { it.jsonObject }
        assertEquals("navy", colors.first()["color"]!!.jsonPrimitive.content)
        assertEquals(2, colors.first()["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the same tools answer a legacy client, in its shapes`() = agentTest {
        addGarment()

        val response = legacy(
            "tools/call",
            buildJsonObject { put("name", "search_wardrobe"); put("arguments", JsonObject(emptyMap())) },
        )

        val result = response.json()["result"]!!.jsonObject
        assertNull(result["resultType"], "a legacy result has no resultType")
        assertEquals(1, result.json()["total"]!!.jsonPrimitive.int)
    }

    // ---- Settings in the browser --------------------------------------------------

    @Test
    fun `Settings in the browser shows the token, and a new one replaces it`() = serverTest(
        hostPorts = { port -> if (port == ServerSettings.DEFAULT_AGENT_PORT) HostPort.Open(18101) else HostPort.Unknown },
    ) {
        val access = storage.agentAccess()!!
        assertEquals(AgentAccess(wardrobe.agentSecret.current(), ServerSettings.DEFAULT_AGENT_PORT, true, 18101), access)
        // Its own secret: showing it makes no pairing code, and is not one.
        assertTrue(access.token != wardrobe.syncSecret.current())

        val renewed = storage.resetAgentAccess()
        assertTrue(renewed.token != access.token)
        assertEquals(wardrobe.agentSecret.current(), renewed.token)
        assertEquals(18101, renewed.hostPort)
    }

    @Test
    fun `a server with the agents' port off has nothing to show`() =
        serverTest(settings = ServerSettings(agentPort = null)) {
            assertNull(storage.agentAccess())
        }

    @Test
    fun `deleting a profile takes its agent token with it`() = agentTest {
        val other = profiles.create("Other", owner = null)
        val token = profiles.wardrobe(other.id)!!.agentSecret.current()
        assertNotNull(profiles.agentWith(token))

        profiles.delete(other.id)

        assertNull(profiles.agentWith(token))
    }

    // ---- Reading answers ----------------------------------------------------------

    private suspend fun HttpResponse.json(): JsonObject = parse(bodyAsText())

    private suspend fun HttpResponse.toolJson(): JsonObject = json()["result"]!!.jsonObject.json()

    /** A tool result's first block, which is its JSON. */
    private fun JsonObject.json(): JsonObject = parse(text())

    private fun JsonObject.text(): String = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.ids(): List<String> = this["garments"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

}

/** A JPEG that decodes, [width] by [height]: the harness's `jpeg()` is only its signature. */
private fun realJpeg(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    return ByteArrayOutputStream().use { out ->
        ImageIO.write(image, "jpg", out)
        out.toByteArray()
    }
}
