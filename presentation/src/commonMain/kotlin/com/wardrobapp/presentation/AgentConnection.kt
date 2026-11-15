package com.wardrobapp.presentation

/*
 * Connecting an AI agent to the wardrobe in Home Assistant, as Settings in the
 * browser shows it: the address the agent is pointed at, and the line that
 * points Claude Code at it.
 *
 * In common code, beside the phone's pairing address, because the two are
 * the same problem -- a device on the home network reaching a port Home
 * Assistant publishes -- with the same answer about which hosts will do. And
 * so the server takes the endpoint's path from here: the one it registers and
 * the one Settings shows cannot drift apart.
 */

/** Where on the agents' port the MCP endpoint answers. */
const val AGENT_ENDPOINT_PATH = "mcp"

/**
 * The MCP endpoint an agent reaches at [host] through [hostPort] -- the port
 * Home Assistant publishes the agents' one on -- or null for a host no device
 * on the home network can use; see [phoneSyncAddressFor], whose rule about
 * Nabu Casa and localhost holds here unchanged.
 */
fun agentEndpointFor(host: String, hostPort: Int): String? =
    phoneSyncAddressFor(host, hostPort)?.let { "$it$AGENT_ENDPOINT_PATH" }

/**
 * The one line that adds this wardrobe to Claude Code, which is the agent most
 * people setting this up will reach for first. HTTP transport, and the token as
 * a header, since that is how this endpoint is spoken to and authorised.
 *
 * The token is quoted as part of the header's value, and needs nothing more:
 * SyncSecret makes it from letters, digits and dashes.
 */
fun claudeCodeCommand(endpoint: String, token: String): String =
    "claude mcp add --transport http wardrobapp $endpoint --header \"Authorization: Bearer $token\""
