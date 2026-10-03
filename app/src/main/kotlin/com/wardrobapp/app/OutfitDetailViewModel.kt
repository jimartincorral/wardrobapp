package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.DatabaseOutfitDetailSource
import com.wardrobapp.presentation.OutfitDetailScreenModel
import com.wardrobapp.presentation.OutfitDetailScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * One saved outfit.
 *
 * What it does lives in OutfitDetailScreenModel, in common code, so the browser
 * can run it too; this is the Android half: the scope that ends with the screen,
 * and the phone's own database as where the outfit comes from.
 */
class OutfitDetailViewModel(
    container: AppContainer,
    outfitId: String,
) : ViewModel() {

    private val model = OutfitDetailScreenModel(
        scope = viewModelScope,
        source = DatabaseOutfitDetailSource(
            outfits = container.outfits,
            outfitWrites = container.outfitWrites,
            garments = container.garments,
            io = Dispatchers.IO,
        ),
        outfitId = outfitId,
    )

    val state: StateFlow<OutfitDetailScreenState> = model.state

    fun refresh() = model.refresh()
    fun onRated(rating: Int) = model.onRated(rating)
    fun onDeleteRequested() = model.onDeleteRequested()
    fun onDeleteDismissed() = model.onDeleteDismissed()
    fun onDeleteConfirmed() = model.onDeleteConfirmed()
}
