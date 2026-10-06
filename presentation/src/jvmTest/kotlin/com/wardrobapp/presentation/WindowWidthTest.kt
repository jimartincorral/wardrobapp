package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the desktop layout starts.
 *
 * Worth pinning because the number is shared by two things that have to agree: the
 * browser's shell, which picks the rail or the bottom bar from it, and the desktop
 * design, whose panes only fit from twelve hundred up. A line moved by accident
 * would show the desktop panes squeezed below their minimums, or a phone layout
 * stretched across a monitor, and neither fails anything but somebody's eyes.
 */
class WindowWidthTest {

    @Test
    fun `twelve hundred and up is the desktop layout`() {
        assertEquals(WindowWidth.EXPANDED, windowWidthFor(1200f))
        assertEquals(WindowWidth.EXPANDED, windowWidthFor(2560f))
    }

    @Test
    fun `anything narrower is the phone layout`() {
        assertEquals(WindowWidth.COMPACT, windowWidthFor(1199.5f))
        assertEquals(WindowWidth.COMPACT, windowWidthFor(360f))
        // A window not measured yet is zero wide, and must not be the desktop.
        assertEquals(WindowWidth.COMPACT, windowWidthFor(0f))
    }
}
