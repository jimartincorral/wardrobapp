package com.wardrobapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.FileNotFoundException

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer.get(applicationContext)

        setContent {
            WardrobappTheme {
                val model: WardrobeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { WardrobeViewModel(container) }
                    }
                )
                val state by model.state.collectAsStateWithLifecycle()

                // The system document picker, so a backup can come from
                // anywhere the user can reach -- Downloads, a cloud provider, a
                // file a friend sent -- without the app holding storage
                // permissions. No permission is granted or asked for: the
                // picker hands back a URI for the one document chosen.
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
                    onRestoreRequested = model::onRestoreRequested,
                    // Providers disagree about what a .zip is: some report
                    // application/zip, some application/octet-stream, and some
                    // nothing at all. Filtering on the type would hide the
                    // user's own backup from them, so every type is offered and
                    // the archive itself is what decides.
                    onRestoreConfirmed = { picker.launch(arrayOf("*/*")) },
                    onRestoreDismissed = model::onRestoreDismissed,
                )
            }
        }
    }
}
