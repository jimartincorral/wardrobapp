package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.DatabaseOutfitsSource
import com.wardrobapp.presentation.OutfitsScreenModel
import com.wardrobapp.presentation.OutfitsScreenState
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Outfit suggestions, and the ones that were kept.
 *
 * Decides nothing about scoring or about what the filter chips mean: the engine
 * is in :domain, the loading in :data, the chips in :presentation. What lives
 * here is the clock, the ids, and which of those results is on screen.
 *
 * What it does lives in OutfitsScreenModel, in common code, so the browser can
 * run it too; this is the Android half: the scope that ends with the screen,
 * and the phone's own database and suggestion engine as where outfits come
 * from.
 */
class OutfitsViewModel(container: AppContainer) : ViewModel() {

    private val model = OutfitsScreenModel(
        scope = viewModelScope,
        source = DatabaseOutfitsSource(
            garments = container.garments,
            outfits = container.outfits,
            outfitWrites = container.outfitWrites,
            suggestions = container.suggestions,
            io = Dispatchers.IO,
            recentlyDeleted = container.recentlyDeleted,
        ),
        undo = container.undo,
    )

    val state: StateFlow<OutfitsScreenState> = model.state

    fun onSeasonTapped(season: Season?) = model.onSeasonTapped(season)
    fun onOccasionTapped(occasion: Occasion?) = model.onOccasionTapped(occasion)
    fun onSeedRequested(garmentId: String) = model.onSeedRequested(garmentId)
    fun onSeedCleared() = model.onSeedCleared()
    fun generate() = model.generate()
    fun onSaveRequested(suggestion: Suggestion) = model.onSaveRequested(suggestion)
    fun onRated(suggestion: Suggestion, rating: Int) = model.onRated(suggestion, rating)
    fun onKeepRequested() = model.onKeepRequested()
    fun onKeepDismissed() = model.onKeepDismissed()
    fun onArchivedToggled() = model.onArchivedToggled()
    fun onPinToggled(outfit: OutfitRecord) = model.onPinToggled(outfit)
    fun refresh() = model.refresh()
    fun onDeleteRequested(outfit: OutfitRecord) = model.onDeleteRequested(outfit)
    fun onDeleteDismissed() = model.onDeleteDismissed()
    fun onDeleteConfirmed() = model.onDeleteConfirmed()
}
