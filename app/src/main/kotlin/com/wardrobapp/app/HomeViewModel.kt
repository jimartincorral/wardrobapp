package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.DatabaseHomeSource
import com.wardrobapp.presentation.HomeScreenModel
import com.wardrobapp.presentation.HomeScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Three counts and a set of doors.
 *
 * No pure module behind this one, deliberately: there is no arithmetic to get
 * wrong. Each count is a single query that Analytics and Settings already read,
 * and everything else on the screen is navigation.
 *
 * The third is not on screen anywhere. It is what the first-steps card's last row
 * asks -- has anybody rated an outfit -- and it is read here rather than by the
 * card, because deciding what the card says from a number this model already
 * fetches is cheaper than a second model and a second read.
 *
 * What it does lives in HomeScreenModel, in common code, so the browser can run
 * it too; this is the Android half: the scope that ends with the screen, and the
 * phone's own database as where the counts come from.
 */
class HomeViewModel(container: AppContainer) : ViewModel() {

    private val model = HomeScreenModel(
        scope = viewModelScope,
        source = DatabaseHomeSource(container.garments, container.outfits, Dispatchers.IO),
    )

    val state: StateFlow<HomeScreenState> = model.state

    fun refresh() = model.refresh()
}
