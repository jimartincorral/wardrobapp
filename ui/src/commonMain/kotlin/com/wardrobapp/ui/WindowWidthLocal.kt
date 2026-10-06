package com.wardrobapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import com.wardrobapp.presentation.WindowWidth

/**
 * Which of the two layouts the screens below should draw.
 *
 * A composition local rather than a parameter on every screen, and a local rather
 * than an expect reading the window, for two reasons. The phone never provides
 * it, so every screen it composes gets [WindowWidth.COMPACT] and is the screen it
 * always was -- not a pixel of the Android app changes, and MainActivity, which
 * calls these same screens and cannot be compiled without the SDK, keeps calling
 * them exactly as before. And the browser decides it once, at the top, from the
 * space the app actually has (see WebApp): Home Assistant's own sidebar takes
 * part of the window, so the window's width is the wrong number to ask.
 *
 * Read with [isExpanded]. Changing it recomposes the screens with the same
 * models -- only the layout swaps, so a filter set or a garment open survives
 * the sidebar being folded away.
 */
val LocalWindowWidth = compositionLocalOf { WindowWidth.COMPACT }

/** Whether the screen being drawn has the desktop's room. See [LocalWindowWidth]. */
@Composable
@ReadOnlyComposable
fun isExpanded(): Boolean = LocalWindowWidth.current == WindowWidth.EXPANDED

/**
 * A column of content no wider than [max], pinned to the start of the space.
 *
 * Start rather than centred, as the desktop design lays its pages out: centred
 * content floats away from the rail on a wide monitor, and the rail is where the
 * eye comes from. The forms are the exception and centre themselves; see
 * GarmentFormScreen.
 */
@Composable
internal fun MaxWidth(max: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        Box(modifier = Modifier.widthIn(max = max).fillMaxWidth(), content = content)
    }
}

/**
 * The pointing hand, over something you click.
 *
 * Only the desktop needs saying so: a phone has no pointer, and on a monitor the
 * difference between a card and a picture of one is the cursor. Harmless where
 * there is no mouse, so it is not conditional.
 */
internal fun Modifier.clickCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)
