package com.wardrobapp.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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

                NavHost(navController = navigator, startDestination = WARDROBE) {
                    composable(WARDROBE) {
                        Wardrobe(
                            container = container,
                            // Encoded rather than concatenated: an id is
                            // opaque, and one containing a separator would
                            // otherwise navigate somewhere else entirely.
                            onGarmentOpened = { id ->
                                navigator.navigate("$GARMENT/${Uri.encode(id)}")
                            },
                        )
                    }

                    composable("$GARMENT/{$GARMENT_ID}") { entry ->
                        // Absent only if the route were built wrong, which the
                        // one call site above rules out -- but a crash on a
                        // malformed link is not the answer either, so the detail
                        // model reports it as a garment that is not there.
                        val id = entry.arguments?.getString(GARMENT_ID).orEmpty()

                        GarmentDetail(
                            container = container,
                            garmentId = id,
                            navigator = navigator,
                        )
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

    private companion object {
        const val WARDROBE = "wardrobe"
        const val GARMENT = "garment"
        const val GARMENT_ID = "garmentId"
    }
}
