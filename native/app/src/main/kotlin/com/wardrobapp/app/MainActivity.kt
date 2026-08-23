package com.wardrobapp.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.io.FileNotFoundException

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.get(applicationContext)

        setContent {
            WardrobappTheme {
                val navigator = rememberNavController()
                val entry by navigator.currentBackStackEntryAsState()
                val route = entry?.destination?.route

                Scaffold(
                    // Only on the top-level destinations: a garment's detail is
                    // somewhere you came *from* one of them, so the bar would
                    // offer to take you sideways out of it.
                    bottomBar = {
                        if (TABS.any { it.route == route }) {
                            NavigationBar {
                                for (tab in TABS) {
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = { navigator.switchTo(tab.route) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { insets ->
                    NavHost(
                        navController = navigator,
                        startDestination = WARDROBE,
                        modifier = Modifier.padding(insets),
                    ) {
                        composable(WARDROBE) {
                            Wardrobe(container, onGarmentOpened = { navigator.openGarment(it) })
                        }

                        composable(OUTFITS) {
                            Outfits(container, onGarmentOpened = { navigator.openGarment(it) })
                        }

                        composable("$GARMENT/{$GARMENT_ID}") { backStackEntry ->
                            // Absent only if a route were built wrong, which the
                            // call sites rule out -- but a crash on a malformed
                            // link is not the answer either, so the detail model
                            // reports it as a garment that is not there.
                            GarmentDetail(
                                container = container,
                                garmentId = backStackEntry.arguments?.getString(GARMENT_ID).orEmpty(),
                                navigator = navigator,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Wardrobe(container: AppContainer, onGarmentOpened: (String) -> Unit) {
        val model: WardrobeViewModel = viewModel(
            factory = viewModelFactory { initializer { WardrobeViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        // The system document picker, so a backup can come from anywhere the
        // user can reach -- Downloads, a cloud provider, a file a friend sent --
        // without the app holding storage permissions. Nothing is granted or
        // asked for: the picker hands back a URI for the one document chosen.
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                // Dismissed. Not a failure, and not worth a dialog.
                model.onRestoreDismissed()
            } else {
                model.onArchivePicked {
                    contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException(
                            "That file could not be opened. Nothing was changed."
                        )
                }
            }
        }

        WardrobeScreen(
            state = state,
            onSearchChanged = model::onSearchChanged,
            onSortToggled = model::onSortToggled,
            onRetry = model::refresh,
            onGarmentOpened = onGarmentOpened,
            onRestoreRequested = model::onRestoreRequested,
            // Providers disagree about what a .zip is: some report
            // application/zip, some application/octet-stream, and some nothing
            // at all. Filtering on the type would hide the user's own backup
            // from them, so every type is offered and the archive decides.
            onRestoreConfirmed = { picker.launch(arrayOf("*/*")) },
            onRestoreDismissed = model::onRestoreDismissed,
        )
    }

    @Composable
    private fun Outfits(container: AppContainer, onGarmentOpened: (String) -> Unit) {
        val model: OutfitsViewModel = viewModel(
            factory = viewModelFactory { initializer { OutfitsViewModel(container) } }
        )
        val state by model.state.collectAsStateWithLifecycle()

        OutfitsScreen(
            state = state,
            onSeasonTapped = model::onSeasonTapped,
            onOccasionTapped = model::onOccasionTapped,
            onGenerate = model::generate,
            onSave = model::onSaveRequested,
            onRate = model::onRated,
            onPinToggled = model::onPinToggled,
            onGarmentOpened = onGarmentOpened,
        )
    }

    @Composable
    private fun GarmentDetail(
        container: AppContainer,
        garmentId: String,
        navigator: NavHostController,
    ) {
        val model: GarmentDetailViewModel = viewModel(
            factory = viewModelFactory {
                initializer { GarmentDetailViewModel(container, garmentId) }
            }
        )
        val state by model.state.collectAsStateWithLifecycle()

        GarmentDetailScreen(
            state = state,
            onBack = { navigator.popBackStack() },
            onPhotoSelected = model::onPhotoSelected,
            onRetry = model::refresh,
        )
    }

    /**
     * Open a garment.
     *
     * The id is encoded rather than concatenated: it is opaque, and one
     * containing a separator would otherwise navigate somewhere else entirely.
     */
    private fun NavHostController.openGarment(id: String) {
        navigate("$GARMENT/${Uri.encode(id)}")
    }

    /**
     * Move between tabs without stacking them.
     *
     * Without this, switching tabs four times leaves four entries on the stack
     * and back walks through the history instead of leaving -- and each visit
     * builds a second copy of the screen's state.
     */
    private fun NavHostController.switchTo(route: String) {
        navigate(route) {
            popUpTo(graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private data class Tab(val route: String, val label: String, val icon: ImageVector)

    private companion object {
        const val WARDROBE = "wardrobe"
        const val OUTFITS = "outfits"
        const val GARMENT = "garment"
        const val GARMENT_ID = "garmentId"

        val TABS = listOf(
            Tab(WARDROBE, "Wardrobe", Icons.Filled.List),
            Tab(OUTFITS, "Outfits", Icons.Filled.Star),
        )
    }
}
