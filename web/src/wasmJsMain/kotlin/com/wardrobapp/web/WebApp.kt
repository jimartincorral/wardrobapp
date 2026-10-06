package com.wardrobapp.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wardrobapp.api.Routes
import com.wardrobapp.api.ServerFeatures
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.data.ReleaseNote
import com.wardrobapp.data.parseWebReleases
import com.wardrobapp.presentation.AppDestination
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WhatsNewDecision
import com.wardrobapp.presentation.WindowWidth
import com.wardrobapp.presentation.windowWidthFor
import com.wardrobapp.presentation.webWhatsNewDecision
import com.wardrobapp.ui.LocalPhotoTools
import com.wardrobapp.ui.LocalWindowWidth
import com.wardrobapp.ui.PhotoTools
import com.wardrobapp.ui.TABS
import com.wardrobapp.ui.WardrobappTheme
import com.wardrobapp.ui.WardrobeBottomBar
import com.wardrobapp.ui.WardrobeNavigationRail
import com.wardrobapp.ui.WhatsNewDialog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.coroutines.cancellation.CancellationException

/**
 * The whole browser app: the theme, the bottom bar -- or on a desktop-width
 * window the rail down the side -- and whichever screen is on top of the stack.
 *
 * Shaped after MainActivity's composition, deliberately, so the two can be read
 * side by side: the same tabs, the same hand-offs between screens -- a category
 * tapped in the statistics opens the wardrobe filtered to it, a garment's
 * "build an outfit" opens the outfits tab seeded with it -- and the same rule
 * that leaving a screen is decided here, from what its model reports, and never
 * by the model.
 *
 * What the browser leaves out is the phone's: the update check (the server is
 * updated by Home Assistant, and the page comes with it -- though What's new,
 * once it has been, is here as it is there), onboarding (its
 * choices are the theme, which is in Settings, and a restore, which is Home
 * Assistant's), and the shared-element transitions, which are an Android
 * navigation feature.
 */
@Composable
fun WebApp(http: HttpClient, profile: ProfileControls) {
    ProvidePhotoLoading()

    val sources = remember(http) { WebSources(http) }
    // The screens are built once; this lets them read the profile as it is now
    // -- renamed, made somebody's own -- rather than as it was then.
    val currentProfile by rememberUpdatedState(profile)
    val navigator = remember { Navigator() }
    var theme by remember { mutableStateOf(ThemePreference.choice) }

    // What the wardrobe should show when something else opens it, and the
    // garment the outfits tab should build around: held here and consumed
    // once, for the reasons MainActivity gives for holding them beside its
    // navigator.
    var arrival by remember { mutableStateOf<WardrobeQuery?>(null) }
    var outfitSeed by remember { mutableStateOf<String?>(null) }
    // The garment to open beside the desktop wardrobe's grid when it arrives --
    // one of Home's recently added -- consumed with the query that comes with it.
    var arrivingGarment by remember { mutableStateOf<String?>(null) }

    // What Home Assistant's last update of the app changed, once per browser
    // per version; see webWhatsNewDecision. Asked once per page load, like the
    // phone's update check, and never in the way of anything: until it has an
    // answer, and whenever it has nothing to say, nothing is shown.
    var whatsNew by remember { mutableStateOf<Pair<String, List<ReleaseNote>>?>(null) }
    LaunchedEffect(http) {
        val current = try {
            http.get(Routes.VERSION).body<ServerVersion>().name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        val releases = try {
            parseWebReleases(http.get(Routes.WHATS_NEW).bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        when (val decision = webWhatsNewDecision(current, WhatsNewSeen.version, releases)) {
            is WhatsNewDecision.Show -> whatsNew = current to decision.notes
            WhatsNewDecision.NothingNew -> WhatsNewSeen.version = current
            WhatsNewDecision.NotYet -> Unit
        }
    }

    // Whether the server can cut a photo out, asked once per page load. No
    // until it answers, and no if it cannot be asked: a button missing for a
    // moment is better than one that is there and fails.
    var features by remember { mutableStateOf(ServerFeatures()) }
    LaunchedEffect(http) {
        try {
            features = http.get(Routes.FEATURES).body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An older server, or none: what it cannot say it can do, it cannot.
        }
    }

    val screens = remember(sources, navigator) {
        Screens(
            sources = sources,
            navigator = navigator,
            profile = { currentProfile },
            openWardrobe = { query, garmentId ->
                arrival = query
                arrivingGarment = garmentId
                navigator.switchTo(Destination.Wardrobe)
            },
            buildOutfitAround = { garmentId ->
                outfitSeed = garmentId
                navigator.switchTo(Destination.Outfits)
            },
        )
    }

    WardrobappTheme(theme) {
        // Backgrounds as the server says; nothing crops a photo in the browser
        // yet. See PhotoTools.
        CompositionLocalProvider(LocalPhotoTools provides PhotoTools(removesBackgrounds = features.removesBackgrounds, crops = false)) {
            // Measured here, on the space the app is given, rather than asked of
            // the window: under Home Assistant the app is a panel beside Home
            // Assistant's own sidebar, and it is the panel that has to fit the
            // desktop's panes. Opening or folding that sidebar crosses the line
            // at some widths, and only the layout changes when it does -- every
            // screen keeps its model, so nothing open is lost.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val width = windowWidthFor(maxWidth.value)
                val expanded = width == WindowWidth.EXPANDED

                CompositionLocalProvider(LocalWindowWidth provides width) {
                    val entry = navigator.current
                    val route = (entry.destination as? Destination.Tab)?.route

                    Scaffold(
                        bottomBar = {
                            if (!expanded && TABS.any { it.route == route }) {
                                WardrobeBottomBar(route) { selected ->
                                    navigator.switchTo(TAB_DESTINATIONS.getValue(selected))
                                }
                            }
                        },
                    ) { insets ->
                        Row(modifier = Modifier.fillMaxSize().padding(insets)) {
                            // On every screen, not only the tabs, as the desktop
                            // design has it: with a whole monitor there is no reason
                            // for a form to hide the way to the rest of the app, the
                            // way a phone has to.
                            if (expanded) {
                                WardrobeNavigationRail(
                                    route = navigator.tab.route,
                                    onTabSelected = { selected ->
                                        navigator.switchTo(TAB_DESTINATIONS.getValue(selected))
                                    },
                                    onAddRequested = { navigator.open(Destination.GarmentAdd()) },
                                )
                            }

                            // Keyed on the entry, so every visit composes its own
                            // screen and nothing from the last one -- a dialog's
                            // remembered state, a scroll position -- leaks into the
                            // next.
                            key(entry) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    screens.Show(
                                        entry = entry,
                                        theme = theme,
                                        onThemeSelected = { choice ->
                                            ThemePreference.choice = choice
                                            theme = choice
                                        },
                                        arrival = arrival,
                                        arrivingGarment = arrivingGarment,
                                        onArrivalApplied = {
                                            arrival = null
                                            arrivingGarment = null
                                        },
                                        outfitSeed = outfitSeed,
                                        onSeedApplied = { outfitSeed = null },
                                    )
                                }
                            }
                        }

                        whatsNew?.let { (version, notes) ->
                            fun seen() {
                                WhatsNewSeen.version = version
                                whatsNew = null
                            }
                            WhatsNewDialog(
                                notes = notes,
                                onShow = { destination ->
                                    seen()
                                    navigator.openFromNote(destination)
                                },
                                onDismiss = ::seen,
                            )
                        }

                        // A screen returned to is read again, as RefreshOnReturn
                        // does on the phone: whatever was done on the screen above
                        // may have changed what this one shows.
                        LaunchedEffect(entry) {
                            if (entry.shown) entry.onReturn?.invoke()
                            entry.shown = true
                        }
                    }
                }
            }
        }
    }
}

/** Where a release note says its change can be seen, as MainActivity's `open` does on the phone. */
private fun Navigator.openFromNote(destination: AppDestination) {
    when (destination) {
        AppDestination.HOME -> switchTo(Destination.Home)
        AppDestination.WARDROBE -> switchTo(Destination.Wardrobe)
        AppDestination.OUTFITS -> switchTo(Destination.Outfits)
        AppDestination.STATISTICS -> switchTo(Destination.Statistics)
        AppDestination.SETTINGS -> switchTo(Destination.Settings)
        AppDestination.NEW_GARMENT -> open(Destination.GarmentAdd())
        AppDestination.BULK_ADD -> open(Destination.BulkAdd)
        AppDestination.NEW_OUTFIT -> open(Destination.OutfitBuild)
    }
}

private val TAB_DESTINATIONS: Map<String, Destination.Tab> = listOf(
    Destination.Home,
    Destination.Wardrobe,
    Destination.Outfits,
    Destination.Statistics,
    Destination.Settings,
).associateBy { it.route }
