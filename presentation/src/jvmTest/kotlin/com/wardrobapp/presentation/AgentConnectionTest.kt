package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentConnectionTest {

    @Test
    fun `an agent is pointed at the endpoint on the published port`() {
        assertEquals("http://homeassistant.local:8101/mcp", agentEndpointFor("homeassistant.local", 8101))
        assertEquals("http://192.168.1.20:18101/mcp", agentEndpointFor("192.168.1.20", 18101))
    }

    @Test
    fun `a host no device at home can use gives no endpoint, as it gives no pairing address`() {
        // Nabu Casa forwards Home Assistant's own port and no other, and
        // localhost is the browser's machine, not the agent's.
        assertNull(agentEndpointFor("abcdef.ui.nabu.casa", 8101))
        assertNull(agentEndpointFor("localhost", 8101))
    }

    @Test
    fun `the Claude Code line carries the endpoint and the token as a bearer header`() {
        assertEquals(
            "claude mcp add --transport http wardrobapp http://homeassistant.local:8101/mcp " +
                "--header \"Authorization: Bearer ABCDE-FGHJK\"",
            claudeCodeCommand("http://homeassistant.local:8101/mcp", "ABCDE-FGHJK"),
        )
    }
}
