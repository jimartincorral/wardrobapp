package com.wardrobapp.api

import kotlinx.serialization.Serializable

/**
 * What an AI agent needs to connect to this profile's wardrobe over MCP, for
 * Settings in the browser to show: the token, the port the server listens for
 * agents on inside its container, and the port Home Assistant publishes that
 * one on.
 *
 * The same shape as [SyncPairing], for the same reasons, field by field: an
 * agent reaches the server the way a phone does, through a port Home Assistant
 * may or may not have opened, at an address the browser may or may not be able
 * to name. The defaults read as "cannot tell", which is what a browser reading
 * an older server's answer should conclude.
 */
@Serializable
data class AgentAccess(
    val token: String,
    val port: Int,
    val hostPortKnown: Boolean = false,
    val hostPort: Int? = null,
    /** The Home Assistant machine's address on the home network, when known and the port is open; see [SyncPairing.homeHost]. */
    val homeHost: String? = null,
)
