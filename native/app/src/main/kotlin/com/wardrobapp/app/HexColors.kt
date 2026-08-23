package com.wardrobapp.app

import androidx.compose.ui.graphics.Color
import com.wardrobapp.domain.parseHexColor

/**
 * A stored hex colour as a Compose colour, or null if it cannot be read.
 *
 * Through the domain parser rather than a second implementation: it is the one
 * that knows `#RGB` shorthand, the multi-colour sentinel, and how to refuse
 * malformed input instead of returning something wrong. A colour that cannot be
 * read is left undrawn rather than guessed at.
 */
internal fun String.toComposeColor(): Color? =
    parseHexColor(this)?.let { Color(it.r, it.g, it.b) }
