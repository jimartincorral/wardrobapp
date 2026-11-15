package com.wardrobapp.server

import com.wardrobapp.api.ServerVersion
import com.wardrobapp.presentation.AGENT_ENDPOINT_PATH
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.net.URI
import java.net.URISyntaxException

/**
 * The agent port: what an AI agent on the home network talks to, over the
 * Model Context Protocol (see Mcp.kt), to read the wardrobe and change a few
 * things in it.
 *
 * A third server on its own port, for the reason sync has one: the browser's
 * port answers only Home Assistant's ingress, which only a person signed in to
 * Home Assistant gets through, and an agent -- Claude Code on a laptop, a
 * script on the same machine -- is not a person in a browser. So this port is
 * built the way sync's is: closed until somebody gives it a host port in the
 * app's Network settings, answering only somebody who shows a profile's own
 * token as a bearer token, and answering nothing but [MCP_PATH]. The token is
 * per profile, like the pairing code, and opens that profile's wardrobe and no
 * other, so an agent set up for one person in a household never sees another's.
 *
 * Its own token rather than the pairing code, because the two are handed to
 * different things and are taken back for different reasons: making a new code
 * to shut out a lost phone should not also disconnect the laptop's agent, nor
 * the other way round.
 *
 * Every tool works through the same sources the browser's routes use (see
 * WardrobeTools), so an agent can do nothing the app's own screens could not.
 */
fun Application.wardrobeAgents(profiles: ProfileRegistry, version: ServerVersion) {
    install(SameOriginOnly)
    install(agentAuthorized(profiles))
    boundedBodies()

    val identity = McpEndpoint.ServerIdentity(name = "wardrobapp", title = "Wardrobapp", version = version.name)

    routing {
        post(MCP_PATH) {
            val wardrobe = call.attributes[AgentWardrobe]
            val body = call.receiveChannel().readRemaining().readByteArray().decodeToString()
            val endpoint = McpEndpoint(
                server = identity,
                instructions = AGENT_INSTRUCTIONS,
                tools = wardrobeTools(wardrobe),
                onToolFailure = { tool, failure -> call.application.log.warn("An agent's call to $tool failed", failure) },
            )
            val reply = endpoint.answer(body) { name -> call.request.headers[name] }
            val answer = reply.body
            if (answer == null) {
                call.respond(reply.status)
            } else {
                call.respondText(answer.toString(), ContentType.Application.Json, reply.status)
            }
        }

        // The 2025 revisions opened a stream for the server's own messages with
        // a GET and ended a session with a DELETE. This server sends no
        // messages of its own and keeps no sessions, which is the answer the
        // transport asks for to both.
        get(MCP_PATH) { notAllowed() }
        delete(MCP_PATH) { notAllowed() }
    }
}

/**
 * Where on the agent port the MCP endpoint is, and the only path it answers:
 * the path Settings in the browser shows, from :presentation, so the two agree.
 */
const val MCP_PATH = "/$AGENT_ENDPOINT_PATH"

private suspend fun RoutingContext.notAllowed() {
    call.response.header(HttpHeaders.Allow, "POST")
    call.respond(HttpStatusCode.MethodNotAllowed)
}

/**
 * What an agent is told about this server when it connects: what the wardrobe
 * is, what the words in it mean, and how to behave with the tools that change
 * it. In English, as the tool descriptions are -- the models these go to are
 * read in it best -- while the wardrobe's own words (brands, names) are
 * whatever their owner typed.
 */
internal const val AGENT_INSTRUCTIONS =
    "Wardrobapp holds one person's wardrobe: the garments they own, the outfits they have saved, " +
        "and the ratings that teach its outfit suggestions their taste. " +
        "Garments and outfits are referred to by id. search_wardrobe finds garments by category, type, colour, " +
        "season, occasion, brand or size; get_garment shows one, with its photo when the client can show images. " +
        "Archived garments are ones the owner no longer wears: they are kept for the record and hidden unless asked for. " +
        "To suggest what to wear, prefer suggest_outfits, which uses the app's own engine and what it has learned " +
        "from this person's ratings, over putting outfits together yourself; rate_outfit is how it learns more. " +
        "The tools that change something -- save_outfit, rate_outfit and set_garment_archived -- can each be undone " +
        "in the app, but say what you are about to change before you change it."

/**
 * Refuse a request whose `Origin` is not this server's own.
 *
 * The transport asks every server to, against DNS rebinding: a web page that
 * gets a browser to resolve its own name to this machine's address. The token
 * already stops that page -- it cannot know it -- but the check is the
 * transport's requirement and costs nothing. A client that is not a browser
 * sends no `Origin` at all, and is not affected.
 */
private val SameOriginOnly = createApplicationPlugin("SameOriginOnly") {
    onCall { call ->
        val origin = call.request.headers[HttpHeaders.Origin] ?: return@onCall
        // The host and port the request was addressed to, as Ktor reads them:
        // the Host header's, or the connection's where a client sent none.
        val addressed = withoutDefaultPort("${call.request.host().lowercase()}:${call.request.port()}", schemeOf(origin))
        if (authorityOf(origin) != addressed) {
            call.respondText("Requests from a web page are not accepted here.", status = HttpStatusCode.Forbidden)
        }
    }
}

private fun schemeOf(origin: String): String? = try {
    URI(origin).scheme?.lowercase()
} catch (_: URISyntaxException) {
    null
}

/** `host[:port]` of an origin, its default port left off as a `Host` header leaves it off; null if it is not one. */
private fun authorityOf(origin: String): String? = try {
    val uri = URI(origin)
    val host = uri.host?.lowercase() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    withoutDefaultPort(if (uri.port == -1) host else "$host:${uri.port}", scheme)
} catch (_: URISyntaxException) {
    null
}

private fun withoutDefaultPort(authority: String, scheme: String?): String = when {
    scheme == "http" && authority.endsWith(":80") -> authority.removeSuffix(":80")
    scheme == "https" && authority.endsWith(":443") -> authority.removeSuffix(":443")
    else -> authority
}

/**
 * Refuse any request without a profile's agent token, before anything else
 * has looked at it; see [wardrobeAgents].
 *
 * A 401 with a `WWW-Authenticate` challenge, which is what an MCP client
 * expects of a server that wants a token it was not given. The body says
 * where the token is, since that is the one thing the person reading the
 * client's error needs to know.
 */
private fun agentAuthorized(profiles: ProfileRegistry) = createApplicationPlugin("AgentAuthorized") {
    val refusals = Refusals(refused = "an agent's request", because = "no agent token, or the wrong one")
    onCall { call ->
        if (call.response.isCommitted) return@onCall
        val offered = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
        val wardrobe = offered?.let(profiles::agentWith)
        if (wardrobe != null) {
            call.attributes.put(AgentWardrobe, wardrobe)
        } else {
            refusals.noting(call.request.local.remoteAddress)?.let { call.application.log.warn(it) }
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"Wardrobapp\"")
            call.respondText(
                "This agent has no Wardrobapp token, or the token was changed. " +
                    "Copy the current one from Settings, under AI agents, in Wardrobapp in Home Assistant.",
                status = HttpStatusCode.Unauthorized,
            )
        }
    }
}

/** The wardrobe the request's token opened, put there by [agentAuthorized]. */
private val AgentWardrobe = AttributeKey<ServerWardrobe>("agent-wardrobe")
