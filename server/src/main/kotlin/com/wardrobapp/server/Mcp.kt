package com.wardrobapp.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/*
 * The Model Context Protocol, the server's half, written here rather than
 * taken from the official Kotlin SDK.
 *
 * The SDK was the first thing tried, and it is the right tool for a server
 * that wants everything MCP offers. This one wants a list of tools, and the
 * SDK's 0.16.0 is built against Kotlin 2.4, Ktor 3.5 and kotlinx.serialization
 * 1.11, where this repository is on 2.2, 3.1 and 1.8 -- across the phone, the
 * browser and the server alike. Taking it would have meant moving every module
 * to new compilers and libraries for one port's sake, and the SDK is still in
 * the lowest of the official tiers, with an API that moves between releases.
 * The part a tools-only server needs is small: JSON-RPC over one POST endpoint,
 * a handshake, two methods. So it is this file, under tests that speak the
 * protocol to it.
 *
 * Two eras of the protocol are answered, because the clients in somebody's
 * house will be of both for a while:
 *
 *  - **Modern** (2026-07-28): stateless. There is no handshake; every request
 *    carries its protocol version in `params._meta`, mirrored into the
 *    `MCP-Protocol-Version` header, and the method and tool name into
 *    `Mcp-Method` and `Mcp-Name`. A server must answer `server/discover`, and
 *    every result says `resultType: "complete"`.
 *  - **Legacy** (2025-03-26 to 2025-11-25): a client opens with `initialize`,
 *    and the version it settles on rides in the `MCP-Protocol-Version` header
 *    afterwards. Those revisions let a server mint a session and keep a stream
 *    open for messages of its own; both are optional, and this server does
 *    neither, so a legacy client is served as statelessly as a modern one.
 *
 * Which era a request belongs to is read off the request, as the 2026-07-28
 * versioning page says a dual-era server does: `_meta` with a version is
 * modern, `initialize` is legacy, and anything else is a legacy client
 * carrying on after its handshake.
 *
 * Every answer is a single `application/json` body. A server may stream
 * instead, to send progress before the result; nothing here takes long enough
 * to be worth it, and a plain body is the one every client must accept.
 */

/** The protocol revisions this server speaks, newest first within each era. */
internal object McpVersions {
    /** Revisions with no handshake, where each request names its own version. */
    val modern = listOf("2026-07-28")

    /** Revisions that open with `initialize`. */
    val legacy = listOf("2025-11-25", "2025-06-18", "2025-03-26")

    val all = modern + legacy
}

/**
 * One thing an agent can ask the wardrobe for.
 *
 * [readOnly] and [idempotent] are said to the client as the tool's annotations,
 * which is how a client decides what to run without asking its user and what
 * to put in front of them first. Every tool that writes here is one that can
 * be undone from the app, so none is marked destructive.
 */
internal class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val idempotent: Boolean = readOnly,
    val run: suspend (arguments: JsonObject) -> ToolResult,
)

/** One block of what a tool answers. */
internal sealed interface ToolContent {
    data class Text(val text: String) : ToolContent

    /** A picture, for the agents that can look at one: a garment's photo, say. */
    data class Image(val base64: String, val mimeType: String) : ToolContent
}

/**
 * What a tool answered: its content, and whether that content is the tool
 * saying it could not do what was asked.
 *
 * A failure is an answer rather than a protocol error, so the model reads it:
 * "no garment has that id" is something it can correct itself over, where a
 * JSON-RPC error goes to the client and, often, nowhere the model sees.
 */
internal data class ToolResult(val content: List<ToolContent>, val isError: Boolean = false) {
    companion object {
        /** [value] as compact JSON text, which every client passes to its model as it is. */
        fun json(value: JsonElement, images: List<ToolContent.Image> = emptyList()) =
            ToolResult(listOf(ToolContent.Text(value.toString())) + images)

        fun error(message: String) = ToolResult(listOf(ToolContent.Text(message)), isError = true)
    }
}

/**
 * A tool's arguments were not what its schema asks for: told to the model as
 * a failed result, in words it can act on, rather than as a protocol error.
 * The 2025-11-25 revision asks for exactly that, since a model can fix an
 * argument and cannot fix a JSON-RPC error it never sees.
 */
internal class ToolArgumentException(message: String) : RuntimeException(message)

/** What the endpoint answers: an HTTP status, and a JSON-RPC message or nothing. */
internal class McpReply(val status: HttpStatusCode, val body: JsonObject?)

/**
 * The MCP endpoint for one wardrobe: [tools], answered under either era.
 *
 * Knows nothing of HTTP beyond the status codes the transport assigns, and
 * nothing of who is asking -- the port checks the token before a request
 * reaches here (see AgentServer). [header] reads a request header by name,
 * case-insensitively, as HTTP's are.
 */
internal class McpEndpoint(
    private val server: ServerIdentity,
    private val instructions: String,
    private val tools: List<McpTool>,
    private val onToolFailure: (tool: String, failure: Throwable) -> Unit = { _, _ -> },
) {

    /** What a client is told this server is called, and which build it is. */
    data class ServerIdentity(val name: String, val title: String, val version: String)

    suspend fun answer(body: String, header: (String) -> String?): McpReply {
        val message = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return McpReply(HttpStatusCode.BadRequest, error(null, PARSE_ERROR, "The request body is not JSON."))
        }

        // Batches were in the 2025-03-26 revision and taken out again in
        // 2025-06-18; every client since sends one message per request.
        if (message is JsonArray) {
            return McpReply(HttpStatusCode.BadRequest, error(null, INVALID_REQUEST, "Send one JSON-RPC message per request; batches are not supported."))
        }
        val request = message as? JsonObject
            ?: return McpReply(HttpStatusCode.BadRequest, error(null, INVALID_REQUEST, "A JSON-RPC message is an object."))

        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (method == null) {
            // A client's answer to a question: this server never asks one, so
            // there is nothing to do with it but say it arrived.
            return if ("result" in request || "error" in request) {
                McpReply(HttpStatusCode.Accepted, null)
            } else {
                McpReply(HttpStatusCode.BadRequest, error(id.orNull(), INVALID_REQUEST, "A JSON-RPC request needs a method."))
            }
        }

        // A notification -- `notifications/initialized`, a cancellation --
        // asks for nothing back. Nothing here runs long enough to cancel.
        if (id == null) return McpReply(HttpStatusCode.Accepted, null)
        if (id !is JsonPrimitive || id is JsonNull) {
            return McpReply(HttpStatusCode.BadRequest, error(null, INVALID_REQUEST, "A request id is a string or a number."))
        }

        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val modernVersion = ((params["_meta"] as? JsonObject)?.get(PROTOCOL_VERSION_KEY) as? JsonPrimitive)?.contentOrNull

        return if (method != INITIALIZE && modernVersion != null) {
            modern(id, method, params, modernVersion, header)
        } else {
            legacy(id, method, params, header)
        }
    }

    /**
     * A request in the stateless era, checked the way its transport asks: the
     * headers must say what the body says, since something between the client
     * and here may have acted on the headers alone.
     */
    private suspend fun modern(
        id: JsonPrimitive,
        method: String,
        params: JsonObject,
        version: String,
        header: (String) -> String?,
    ): McpReply {
        val headerVersion = header(PROTOCOL_VERSION_HEADER)
        if (headerVersion != version) {
            return mismatch(id, "$PROTOCOL_VERSION_HEADER is ${headerVersion ?: "missing"}, and the request's _meta says $version.")
        }
        if (version !in McpVersions.modern) {
            return McpReply(
                HttpStatusCode.BadRequest,
                error(
                    id,
                    UNSUPPORTED_PROTOCOL_VERSION,
                    "Unsupported protocol version",
                    buildJsonObject {
                        putJsonArray("supported") { McpVersions.all.forEach { add(it) } }
                        put("requested", version)
                    },
                ),
            )
        }

        val headerMethod = header(METHOD_HEADER)
        if (headerMethod != method) {
            return mismatch(id, "$METHOD_HEADER is ${headerMethod ?: "missing"}, and the request's method is $method.")
        }
        if (method == TOOLS_CALL) {
            val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            val headerName = header(NAME_HEADER)?.let(::decodedHeaderValue)
            if (headerName != name) {
                return mismatch(id, "$NAME_HEADER is ${headerName ?: "missing"}, and the tool called is $name.")
            }
        }

        return when (method) {
            DISCOVER -> McpReply(HttpStatusCode.OK, result(id, discovery(), modern = true))
            TOOLS_LIST -> McpReply(HttpStatusCode.OK, result(id, toolList(modern = true), modern = true))
            TOOLS_CALL -> call(id, params, modern = true)
            // A 404 with a JSON-RPC error in it, which is how a modern client
            // tells "no such method" from "no MCP endpoint here at all".
            else -> McpReply(HttpStatusCode.NotFound, error(id, METHOD_NOT_FOUND, "This server has no method $method."))
        }
    }

    /** A request from a client that opens with `initialize`, or one carrying on after it did. */
    private suspend fun legacy(
        id: JsonPrimitive,
        method: String,
        params: JsonObject,
        header: (String) -> String?,
    ): McpReply {
        // After the handshake a 2025-06-18 or later client names the version it
        // settled on in every request. One this server never offered is a
        // client confused about who it is talking to, and the transport says
        // to refuse it. A request without the header is a 2025-03-26 client,
        // which never sent one.
        val headerVersion = header(PROTOCOL_VERSION_HEADER)
        if (method != INITIALIZE && headerVersion != null && headerVersion !in McpVersions.legacy) {
            return McpReply(
                HttpStatusCode.BadRequest,
                error(id, INVALID_REQUEST, "Unsupported $PROTOCOL_VERSION_HEADER: $headerVersion. Supported: ${McpVersions.all.joinToString()}."),
            )
        }

        return when (method) {
            INITIALIZE -> McpReply(HttpStatusCode.OK, result(id, initialization(params), modern = false))
            PING -> McpReply(HttpStatusCode.OK, result(id, JsonObject(emptyMap()), modern = false))
            TOOLS_LIST -> McpReply(HttpStatusCode.OK, result(id, toolList(modern = false), modern = false))
            TOOLS_CALL -> call(id, params, modern = false)
            else -> McpReply(HttpStatusCode.OK, error(id, METHOD_NOT_FOUND, "This server has no method $method."))
        }
    }

    /**
     * The handshake's answer: the version asked for if this server speaks it,
     * and otherwise the newest it does -- which is the rule, and leaves the
     * client to decide whether it can live with that.
     */
    private fun initialization(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = requested?.takeIf { it in McpVersions.legacy } ?: McpVersions.legacy.first()
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") {
                // The list never changes while the server runs, so nobody is
                // told to listen for it changing.
                putJsonObject("tools") { put("listChanged", false) }
            }
            put("serverInfo", serverInfo())
            put("instructions", instructions)
        }
    }

    /** `server/discover`: every version this server speaks, what it offers, and what it is. */
    private fun discovery(): JsonObject = buildJsonObject {
        putJsonArray("supportedVersions") { McpVersions.all.forEach { add(it) } }
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        put("instructions", instructions)
        cacheable()
    }

    private fun toolList(modern: Boolean): JsonObject = buildJsonObject {
        // In the order they are written, every time: 2026-07-28 asks for a
        // deterministic order, so a client's cached copy and a model's cached
        // prompt both stay valid between asks.
        put("tools", buildJsonArray { tools.forEach { add(it.listed()) } })
        if (modern) cacheable()
    }

    /**
     * How long a client may keep an answer that does not change while the
     * server runs: five minutes, so a server updated with new tools is seen
     * soon after. Public, because the list is the same for every wardrobe.
     */
    private fun JsonObjectBuilder.cacheable() {
        put("ttlMs", LIST_TTL_MS)
        put("cacheScope", "public")
    }

    private fun McpTool.listed(): JsonObject = buildJsonObject {
        put("name", name)
        put("title", title)
        put("description", description)
        put("inputSchema", inputSchema)
        putJsonObject("annotations") {
            put("title", title)
            put("readOnlyHint", readOnly)
            put("destructiveHint", false)
            put("idempotentHint", idempotent)
            // Only this wardrobe: nothing here reaches out to the web.
            put("openWorldHint", false)
        }
    }

    private suspend fun call(id: JsonPrimitive, params: JsonObject, modern: Boolean): McpReply {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return McpReply(HttpStatusCode.OK, error(id, INVALID_PARAMS, "tools/call needs the name of a tool."))
        val tool = tools.firstOrNull { it.name == name }
            ?: return McpReply(HttpStatusCode.OK, error(id, INVALID_PARAMS, "There is no tool called $name."))
        val arguments = when (val given = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> given
            else -> return McpReply(HttpStatusCode.OK, error(id, INVALID_PARAMS, "A tool's arguments are an object."))
        }

        val outcome = try {
            tool.run(arguments)
        } catch (wrong: ToolArgumentException) {
            ToolResult.error(wrong.message ?: "Those arguments are not what $name takes.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Logged in full for whoever reads the app's log; the model gets
            // the one line, which is all it could use.
            onToolFailure(name, failure)
            ToolResult.error("The wardrobe could not do that: ${failure.message ?: failure::class.simpleName}")
        }

        return McpReply(HttpStatusCode.OK, result(id, outcome.encoded(), modern))
    }

    private fun ToolResult.encoded(): JsonObject = buildJsonObject {
        putJsonArray("content") {
            for (block in content) {
                add(
                    when (block) {
                        is ToolContent.Text -> buildJsonObject {
                            put("type", "text")
                            put("text", block.text)
                        }
                        is ToolContent.Image -> buildJsonObject {
                            put("type", "image")
                            put("data", block.base64)
                            put("mimeType", block.mimeType)
                        }
                    },
                )
            }
        }
        put("isError", isError)
    }

    private fun serverInfo(): JsonObject = buildJsonObject {
        put("name", server.name)
        put("title", server.title)
        put("version", server.version)
    }

    /**
     * A JSON-RPC result. A modern one also says it is complete -- the field
     * that tells it apart from a request for more input -- and names the
     * server, since with no handshake there is nowhere else to.
     */
    private fun result(id: JsonPrimitive, value: JsonObject, modern: Boolean): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "result",
            if (!modern) {
                value
            } else {
                JsonObject(
                    value + mapOf(
                        "resultType" to JsonPrimitive("complete"),
                        "_meta" to buildJsonObject { put(SERVER_INFO_KEY, serverInfo()) },
                    ),
                )
            },
        )
    }

    private fun mismatch(id: JsonPrimitive, message: String) =
        McpReply(HttpStatusCode.BadRequest, error(id, HEADER_MISMATCH, "Header mismatch: $message"))

    private companion object {
        const val INITIALIZE = "initialize"
        const val PING = "ping"
        const val DISCOVER = "server/discover"
        const val TOOLS_LIST = "tools/list"
        const val TOOLS_CALL = "tools/call"

        const val PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion"
        const val SERVER_INFO_KEY = "io.modelcontextprotocol/serverInfo"

        const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val METHOD_HEADER = "Mcp-Method"
        const val NAME_HEADER = "Mcp-Name"

        const val LIST_TTL_MS = 300_000L

        // JSON-RPC's own codes, and the two MCP 2026-07-28 allocates.
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val HEADER_MISMATCH = -32020
        const val UNSUPPORTED_PROTOCOL_VERSION = -32022

        private const val BASE64_OPEN = "=?base64?"
        private const val BASE64_CLOSE = "?="

        /**
         * A header value as the client meant it. A name that cannot travel as
         * plain ASCII is sent as `=?base64?...?=`, and the transport says to
         * compare what it decodes to.
         */
        fun decodedHeaderValue(value: String): String? =
            if (value.startsWith(BASE64_OPEN) && value.endsWith(BASE64_CLOSE) && value.length >= BASE64_OPEN.length + BASE64_CLOSE.length) {
                try {
                    Base64.getDecoder()
                        .decode(value.substring(BASE64_OPEN.length, value.length - BASE64_CLOSE.length))
                        .decodeToString()
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                value
            }

        fun error(id: JsonPrimitive?, code: Int, message: String, data: JsonElement? = null): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            }
        }

        fun JsonElement?.orNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
    }
}
