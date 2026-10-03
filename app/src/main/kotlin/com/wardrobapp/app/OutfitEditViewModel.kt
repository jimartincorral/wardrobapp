package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.DatabaseOutfitEditSource
import com.wardrobapp.presentation.OutfitEditScreenModel
import com.wardrobapp.presentation.OutfitEditScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Building an outfit by hand, and changing one.
 *
 * One model for both, because they are the same job with a different starting
 * point and a different write at the end -- the garment form is add-or-edit for
 * the same reason. The rules are in :presentation as transitions over
 * [OutfitEditState]; this loads the wardrobe to pick from and writes the row.
 *
 * What it does lives in OutfitEditScreenModel, in common code, so the browser
 * can run it too; this is the Android half: the scope that ends with the screen,
 * and the phone's own database as where the garments come from and the outfit
 * goes.
 */
class OutfitEditViewModel(
    container: AppContainer,
    /** Null when building a new outfit. */
    outfitId: String?,
) : ViewModel() {

    private val model = OutfitEditScreenModel(
        scope = viewModelScope,
        source = DatabaseOutfitEditSource(
            garments = container.garments,
            outfits = container.outfits,
            outfitWrites = container.outfitWrites,
            io = Dispatchers.IO,
        ),
        outfitId = outfitId,
    )

    val isEditing = model.isEditing
    val state: StateFlow<OutfitEditScreenState> = model.state

    fun onNameChanged(name: String) = model.onNameChanged(name)
    fun onSearchChanged(search: String) = model.onSearchChanged(search)
    fun onGarmentToggled(garmentId: String) = model.onGarmentToggled(garmentId)
    fun onOccasionTapped(occasion: Occasion) = model.onOccasionTapped(occasion)
    fun onSeasonTapped(season: Season) = model.onSeasonTapped(season)
    fun onSaveRequested() = model.onSaveRequested()
    fun onErrorDismissed() = model.onErrorDismissed()
}
