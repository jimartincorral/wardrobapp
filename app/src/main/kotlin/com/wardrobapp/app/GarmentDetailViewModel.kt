package com.wardrobapp.app

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.DatabaseGarmentDetailSource
import com.wardrobapp.presentation.GarmentDetailScreenModel
import com.wardrobapp.presentation.GarmentDetailScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * One garment's detail.
 *
 * Decides nothing about what is shown: :presentation turns the record into a
 * [GarmentDetailView], and this holds which photo is selected and keeps the read
 * off the main thread.
 *
 * What it does lives in GarmentDetailScreenModel, in common code, so the browser
 * can run it too; this is the Android half: the scope that ends with the screen,
 * the phone's own database, and the two things only the phone does here --
 * deleting photo files from its store, and ML Kit cutting a photo out of its
 * background.
 */
class GarmentDetailViewModel(
    container: AppContainer,
    garmentId: String,
) : ViewModel() {

    private val model = GarmentDetailScreenModel(
        scope = viewModelScope,
        source = DatabaseGarmentDetailSource(
            garments = container.garments,
            garmentWrites = container.garmentWrites,
            imageDirectory = container.imageDirectory,
            deletePhoto = container.photos::delete,
            removeBackground = { photo, id -> container.backgrounds.removeBackground(photo.toUri(), id) },
            io = Dispatchers.IO,
        ),
        garmentId = garmentId,
    )

    val state: StateFlow<GarmentDetailScreenState> = model.state

    fun refresh() = model.refresh()
    fun onPhotoSelected(index: Int) = model.onPhotoSelected(index)
    fun onRetireRequested() = model.onRetireRequested()
    fun onDeleteRequested() = model.onDeleteRequested()
    fun onConfirmationDismissed() = model.onConfirmationDismissed()
    fun onActionErrorDismissed() = model.onActionErrorDismissed()
    fun onConfirmed() = model.onConfirmed()
    fun onReturnedToWardrobe() = model.onReturnedToWardrobe()
    fun onRemoveBackground() = model.onRemoveBackground()
    fun onUndoBackground() = model.onUndoBackground()
}
