package com.wardrobapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.agentEndpointFor
import com.wardrobapp.presentation.claudeCodeCommand
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.agent_access_address
import com.wardrobapp.ui.resources.agent_access_at_home
import com.wardrobapp.ui.resources.agent_access_claude_code
import com.wardrobapp.ui.resources.agent_access_closed
import com.wardrobapp.ui.resources.agent_access_elsewhere
import com.wardrobapp.ui.resources.agent_access_hint
import com.wardrobapp.ui.resources.agent_access_off
import com.wardrobapp.ui.resources.agent_access_open_port
import com.wardrobapp.ui.resources.agent_access_reset
import com.wardrobapp.ui.resources.agent_access_reset_body
import com.wardrobapp.ui.resources.agent_access_reset_title
import com.wardrobapp.ui.resources.agent_access_token
import com.wardrobapp.ui.resources.settings_section_agents
import org.jetbrains.compose.resources.stringResource

/**
 * Settings' AI agents section in the browser: what an agent on the home
 * network needs to reach this profile's wardrobe over MCP -- an address and a
 * token -- shown to somebody signed in to Home Assistant, which is who reaches
 * this page.
 *
 * The phone's pairing section is the model, and the address is worked out the
 * same way (see SyncPairingSection), from most to least helpful: a closed port
 * said plainly first; an address made of the host in this browser's address
 * bar when a device at home can use it; the machine's home address when it
 * cannot and the server found one; and otherwise the port and a sentence about
 * which address to put in front of it. No QR code, since an agent is set up by
 * typing or pasting into a terminal, not by holding a camera up to a screen.
 *
 * With an address, the one line that adds the wardrobe to Claude Code, ready
 * to copy: most people setting this up will be setting up that, and a line
 * with the token already in it is one fewer thing to get wrong. Every agent
 * needs only the two values above it, so the line is an example, not the way.
 *
 * Everything is selectable, so it can be copied rather than retyped. [port] is
 * the one inside the app's container, which is what Home Assistant's Network
 * settings list; null when the server has the agents' port switched off, and
 * [token] null until it has answered.
 */
@Composable
fun AgentAccessSection(
    token: String?,
    port: Int?,
    hostPortKnown: Boolean,
    hostPort: Int?,
    browserHost: String,
    onResetConfirmed: () -> Unit,
    homeHost: String? = null,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.agent_access_reset_title)) },
            text = { Text(stringResource(Res.string.agent_access_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onResetConfirmed()
                }) { Text(stringResource(Res.string.agent_access_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    Section(stringResource(Res.string.settings_section_agents))

    if (port == null) {
        AgentHint(stringResource(Res.string.agent_access_off))
        return
    }

    AgentHint(stringResource(Res.string.agent_access_hint))

    val browserEndpoint = hostPort?.let { agentEndpointFor(browserHost, it) }
    val homeEndpoint = if (browserEndpoint == null && hostPort != null && homeHost != null) {
        agentEndpointFor(homeHost, hostPort)
    } else {
        null
    }
    val endpoint = browserEndpoint ?: homeEndpoint
    val closed = hostPortKnown && hostPort == null
    when {
        closed -> Text(
            stringResource(Res.string.agent_access_closed, port),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        homeEndpoint != null -> AgentHint(stringResource(Res.string.agent_access_at_home))
        endpoint != null -> Unit
        hostPort != null -> AgentHint(stringResource(Res.string.agent_access_elsewhere, hostPort))
        else -> AgentHint(stringResource(Res.string.agent_access_open_port, port))
    }

    if (token == null) return

    if (endpoint != null) {
        Copyable(
            label = stringResource(Res.string.agent_access_address),
            value = endpoint,
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    Copyable(
        label = stringResource(Res.string.agent_access_token),
        value = token,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag(AGENT_ACCESS_TOKEN),
    )

    if (endpoint != null) {
        Copyable(
            label = stringResource(Res.string.agent_access_claude_code),
            value = claudeCodeCommand(endpoint, token),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    OutlinedButton(
        onClick = { confirming = true },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(stringResource(Res.string.agent_access_reset))
    }
}

/** A label, and under it a value in a monospaced face that can be selected and copied. */
@Composable
private fun Copyable(label: String, value: String, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                value,
                style = style.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AgentHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** The token in the browser's AI agents section, for its tests. */
const val AGENT_ACCESS_TOKEN = "agent-access-token"
