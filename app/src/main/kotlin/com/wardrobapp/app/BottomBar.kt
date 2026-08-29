package com.wardrobapp.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

// The bar, and the five places it goes. Out of MainActivity so that it can be
// composed on its own, which is what its test does: the point of measuring the
// labels at all is lost if what gets measured is a copy of the bar made in a test.
internal const val HOME = "home"
internal const val WARDROBE = "wardrobe"
internal const val OUTFITS = "outfits"
internal const val STATISTICS = "statistics"
internal const val SETTINGS = "settings"

// The label is a resource id rather than a string because this list is built
// once, outside any composition, and a string would freeze the language it was
// built in.
internal data class Tab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

internal val TABS = listOf(
    Tab(HOME, R.string.tab_home, Icons.Filled.Home),
    Tab(WARDROBE, R.string.tab_wardrobe, Icons.Filled.List),
    Tab(OUTFITS, R.string.tab_outfits, Icons.Filled.Star),
    Tab(STATISTICS, R.string.tab_statistics, Icons.Filled.Info),
    // A tab rather than somewhere you go and come back from. What is in it --
    // the theme, the language, backups, storage -- is not a detour off one
    // screen, and it was reached through a gear on the wardrobe's bar, which put
    // it behind a screen it has nothing to do with.
    Tab(SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
internal fun WardrobeBottomBar(route: String?, onTabSelected: (String) -> Unit) {
    NavigationBar {
        for (tab in TABS) {
            NavigationBarItem(
                selected = route == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { TabLabel(stringResource(tab.labelRes)) },
            )
        }
    }
}

/**
 * A tab's label, on one line.
 *
 * Five tabs share the bar, so each gets about a fifth of the screen -- roughly
 * 82dp on a common phone. Material's own label size put Spanish's "Estadisticas"
 * a few dp over that, and nothing said the label was one line, so it wrapped
 * inside a bar whose height is fixed. Three things together stop that:
 *
 * - Two points smaller, which buys back about a sixth of the width.
 * - No letter spacing. Material's half point over twelve glyphs is 6dp spent on
 *   nothing, and it is the cheapest width there is.
 * - One line with an ellipsis, which is the part that fixes the *class* of this
 *   rather than this instance of it. A larger font scale, a sixth tab or a third
 *   language would put some other word over the line, and an ellipsis is a
 *   legible way to lose that argument where a second line is not.
 *
 * The other four labels get smaller for a reason that is not theirs, which is
 * what shrinking the labels means and is the trade this was chosen over renaming
 * the Spanish tab.
 */
@Composable
private fun TabLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 10.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
