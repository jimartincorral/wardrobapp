package com.wardrobapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

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

                WardrobeScreen(
                    state = state,
                    onSearchChanged = model::onSearchChanged,
                    onSortToggled = model::onSortToggled,
                    onRetry = model::refresh,
                )
            }
        }
    }
}
