package com.wardrobapp.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.ui.LocalPhotoTools
import com.wardrobapp.ui.PhotoTools
import com.wardrobapp.ui.TABS
import com.wardrobapp.ui.WardrobappTheme
import com.wardrobapp.ui.WardrobeBottomBar
import io.ktor.client.HttpClient

/**
 * The whole browser app: the theme, the bottom bar, and whichever screen is on
 * top of the stack.
 *
 * Shaped after MainActivity's composition, deliberately, so the two can be read
 * side by side: the same tabs, the same hand-offs between screens -- a category
 * tapped in the statistics opens the wardrobe filtered to it, a garment's
 * "build an outfit" opens the outfits tab seeded with it -- and the same rule
 * that leaving a screen is decided here, from what its model reports, and never
 * by the model.
 *
 * What the browser leaves out is the phone's: the update check (the server is
 * updated by Home Assistant, and the page comes with it), onboarding (its
 * choices are the theme, which is in Settings, and a restore, which is Home
 * Assistant's), and the shared-element transitions, which are an Android
 * navigation feature.
 */
@Composable
fun WebApp(http: HttpClient) {
    ProvidePhotoLoading()

    val sources = remember(http) { WebSources(http) }
    val navigator = remember { Navigator() }
    var theme by remember { mutableStateOf(ThemePreference.choice) }

    // What the wardrobe should show when something else opens it, and the
    // garment the outfits tab should build around: held here and consumed
    // once, for the reasons MainActivity gives for holding them beside its
    // navigator.
    var arrival by remember { mutableStateOf<WardrobeQuery?>(null) }
    var outfitSeed by remember { mutableStateOf<String?>(null) }

    val screens = remember(sources, navigator) {
        Screens(
            sources = sources,
            navigator = navigator,
            openWardrobe = { query ->
                arrival = query
                navigator.switchTo(Destination.Wardrobe)
            },
            buildOutfitAround = { garmentId ->
                outfitSeed = garmentId
                navigator.switchTo(Destination.Outfits)
            },
        )
    }

    WardrobappTheme(theme) {
        // Nothing removes or crops a photo in the browser yet; see PhotoTools.
        CompositionLocalProvider(LocalPhotoTools provides PhotoTools(removesBackgrounds = false, crops = false)) {
            val entry = navigator.current
            val route = (entry.destination as? Destination.Tab)?.route

            Scaffold(
                bottomBar = {
                    if (TABS.any { it.route == route }) {
                        WardrobeBottomBar(route) { selected ->
                            navigator.switchTo(TAB_DESTINATIONS.getValue(selected))
                        }
                    }
                },
            ) { insets ->
                // Keyed on the entry, so every visit composes its own screen and
                // nothing from the last one -- a dialog's remembered state, a
                // scroll position -- leaks into the next.
                key(entry) {
                    Box(Modifier.padding(insets)) {
                        screens.Show(
                            entry = entry,
                            theme = theme,
                            onThemeSelected = { choice ->
                                ThemePreference.choice = choice
                                theme = choice
                            },
                            arrival = arrival,
                            onArrivalApplied = { arrival = null },
                            outfitSeed = outfitSeed,
                            onSeedApplied = { outfitSeed = null },
                        )
                    }
                }

                // A screen returned to is read again, as RefreshOnReturn does on
                // the phone: whatever was done on the screen above may have
                // changed what this one shows.
                LaunchedEffect(entry) {
                    if (entry.shown) entry.onReturn?.invoke()
                    entry.shown = true
                }
            }
        }
    }
}

private val TAB_DESTINATIONS: Map<String, Destination.Tab> = listOf(
    Destination.Home,
    Destination.Wardrobe,
    Destination.Outfits,
    Destination.Statistics,
    Destination.Settings,
).associateBy { it.route }
