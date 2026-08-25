package com.wardrobapp.presentation

import kotlin.math.abs

/**
 * How the wardrobe is drawn.
 *
 * Rows or cells, and how many cells fit across. Here rather than in the composable
 * for the reason [WardrobeQuery] is: what a stored value means, what happens to one
 * this build does not recognise, and which counts are on offer are rules, and a
 * rule that lives in a layout cannot be asked what it would say.
 *
 * New in this app -- the React Native wardrobe had one layout and no choice about
 * it -- so there is no stored vocabulary to match, only one to keep simple.
 */
enum class WardrobeLayout { LIST, GRID }

/** The counts a grid is offered. Three, because a phone has room for three. */
val GRID_COLUMN_CHOICES: List<Int> = listOf(2, 3, 4)

/** The middle one: a photo big enough to recognise, several rows on screen. */
const val DEFAULT_GRID_COLUMNS: Int = 3

/**
 * The wardrobe's own appearance, as chosen.
 *
 * [columns] is kept while the layout is a list rather than reset, so going back to
 * a grid returns to the grid you had. It only means anything in [WardrobeLayout.GRID]
 * -- a list is one garment per row by definition -- which is why [cellsAcross]
 * exists rather than callers reading [columns] and remembering to check.
 */
data class WardrobeView(
    val layout: WardrobeLayout = WardrobeLayout.LIST,
    val columns: Int = DEFAULT_GRID_COLUMNS,
) {

    /** How many garments go across the screen: a list is always one. */
    val cellsAcross: Int
        get() = if (layout == WardrobeLayout.GRID) columns else 1

    /** True when this is the choice in force, for a menu that shows which is. */
    fun isCurrent(other: WardrobeView): Boolean =
        layout == other.layout && (layout == WardrobeLayout.LIST || columns == other.columns)

    /**
     * Take a choice from the menu.
     *
     * Picking the list keeps the width the grid had, so the menu's own list entry
     * cannot quietly reset it: what the entry says is "show me a list", not "and
     * forget how wide my grid was".
     */
    fun withChoice(choice: WardrobeView): WardrobeView = when (choice.layout) {
        WardrobeLayout.LIST -> copy(layout = WardrobeLayout.LIST)
        WardrobeLayout.GRID -> copy(layout = WardrobeLayout.GRID, columns = choice.columns)
    }
}

/** The choices to offer, in order: the list, then a grid of each width. */
val WARDROBE_VIEW_CHOICES: List<WardrobeView> =
    listOf(WardrobeView(WardrobeLayout.LIST)) +
        GRID_COLUMN_CHOICES.map { WardrobeView(WardrobeLayout.GRID, it) }

/**
 * The view a stored pair stands for.
 *
 * Anything unrecognised is the list at the default width, which is what the app
 * looked like before there was a choice: a preferences file written by a later
 * build, or edited by hand, cannot produce a wardrobe with no columns or forty of
 * them. A count outside the offered ones is snapped to the nearest offered one
 * rather than dropped, since it says something about the size that was wanted.
 */
fun wardrobeViewFor(storedLayout: String?, storedColumns: Int?): WardrobeView {
    val layout = when (storedLayout?.trim()?.lowercase()) {
        "grid" -> WardrobeLayout.GRID
        else -> WardrobeLayout.LIST
    }

    val columns = storedColumns
        ?.let { wanted -> GRID_COLUMN_CHOICES.minByOrNull { abs(it - wanted) } }
        ?: DEFAULT_GRID_COLUMNS

    return WardrobeView(layout, columns)
}

/**
 * The value to store for a layout, or null to store nothing.
 *
 * Null for the list, so the layout this app has always had is recorded as the
 * absence of a choice -- the same shape as [ThemeChoice.storedValue], and for the
 * same reason: a fresh install and a deliberate return to it are one state.
 */
val WardrobeLayout.storedValue: String?
    get() = when (this) {
        WardrobeLayout.LIST -> null
        WardrobeLayout.GRID -> "grid"
    }
