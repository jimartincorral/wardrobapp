package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.DatabaseOutfitsSource
import com.wardrobapp.presentation.TasteTrainingScreenModel
import com.wardrobapp.presentation.TasteTrainingScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * A training session on the phone: the scope that ends with the screen, and
 * the phone's own database and suggestion engine as where outfits come from
 * -- the same source the outfits tab uses, so a rating given here is the
 * rating the tab would have recorded. What it does lives in
 * TasteTrainingScreenModel, in common code, so the browser can run it too.
 */
class TasteTrainingViewModel(container: AppContainer) : ViewModel() {

    private val model = TasteTrainingScreenModel(
        scope = viewModelScope,
        source = DatabaseOutfitsSource(
            garments = container.garments,
            outfits = container.outfits,
            outfitWrites = container.outfitWrites,
            suggestions = container.suggestions,
            io = Dispatchers.IO,
            recentlyDeleted = container.recentlyDeleted,
        ),
    )

    val state: StateFlow<TasteTrainingScreenState> = model.state

    fun onRated(rating: Int) = model.onRated(rating)
    fun onSkipped() = model.onSkipped()
    fun onSaveRequested() = model.onSaveRequested()
    fun onKeepGoing() = model.onKeepGoing()
    fun onRetry() = model.onRetry()
}
