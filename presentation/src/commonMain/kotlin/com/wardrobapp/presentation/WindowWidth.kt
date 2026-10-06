package com.wardrobapp.presentation

/**
 * How much room the app has across, as the two layouts it has for that.
 *
 * Two, not Material's three window size classes. The phone layout is what every
 * screen was designed as, and it works on anything up to a tablet held upright;
 * what it does badly is a desktop browser, where a single column of cards
 * stretches to a line length nobody reads and the bottom bar sits a monitor's
 * height away from everything it controls. So the second layout is for that and
 * only that -- a rail instead of the bar, panes side by side -- and a middle size
 * would be a third set of screens to keep in step with the other two for windows
 * that the first already handles.
 *
 * Here rather than in the composable that branches on it, for the reason
 * [WardrobeView] is: where the line falls is a rule, and a rule in a layout cannot
 * be asked what it would say about a given width.
 */
enum class WindowWidth { COMPACT, EXPANDED }

/**
 * The narrowest window, in dp, that gets the desktop layout.
 *
 * Twelve hundred because that is what the desktop design needs before its panes
 * stop fitting: an 80dp rail, the wardrobe's 288dp filter panel and 420dp detail
 * pane, and a grid between them still wide enough for four photos. Inside Home
 * Assistant the panel is the window minus Home Assistant's own sidebar, so on a
 * 1440px screen the layout flips when that sidebar is opened and closed -- which
 * is why the screens keep their models across the flip rather than being rebuilt.
 */
const val EXPANDED_MIN_WIDTH_DP: Float = 1200f

/** Which layout a window this many dp wide gets. Exactly at the line is the desktop one. */
fun windowWidthFor(widthDp: Float): WindowWidth =
    if (widthDp >= EXPANDED_MIN_WIDTH_DP) WindowWidth.EXPANDED else WindowWidth.COMPACT
