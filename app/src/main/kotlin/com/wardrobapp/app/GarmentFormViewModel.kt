package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.GarmentAttributes
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.DatabaseGarmentFormSource
import com.wardrobapp.presentation.FetchingGarmentImporter
import com.wardrobapp.presentation.GarmentFormScreenModel
import com.wardrobapp.presentation.GarmentFormScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Adding or editing a garment.
 *
 * The form's rules are in :presentation, as pure transitions over
 * [GarmentFormState]; this holds the current one, does the photo and database
 * work off the main thread, and decides nothing.
 *
 * Editing and adding are the same screen with a different starting state and a
 * different write at the end, which is how the React Native app has it too --
 * the alternative is two screens that drift.
 *
 * What it does lives in GarmentFormScreenModel, in common code, so the browser
 * can run it too; this is the Android half: the scope that ends with the screen,
 * PhonePhotoWork for the photos, the phone's own database for the garment, and
 * :net's fetcher for URL import.
 */
class GarmentFormViewModel(
    container: AppContainer,
    /** Null when adding. */
    garmentId: String?,
    /** What a gap suggested, when the form was opened from one. */
    wanted: PhantomGarment? = null,
) : ViewModel() {

    private val model = GarmentFormScreenModel(
        scope = viewModelScope,
        photos = PhonePhotoWork(container),
        source = DatabaseGarmentFormSource(
            garments = container.garments,
            garmentWrites = container.garmentWrites,
            duplicates = container.duplicates,
            deletePhoto = container.photos::delete,
            io = Dispatchers.IO,
        ),
        importer = FetchingGarmentImporter(container::importPages, container.importImages, Dispatchers.IO),
        garmentId = garmentId,
        wanted = wanted,
    )

    val isEditing: Boolean = model.isEditing
    val state: StateFlow<GarmentFormScreenState> = model.state

    fun suggestionsFor(brand: String): List<String> = model.suggestionsFor(brand)
    fun onSaveRequested(force: Boolean = false) = model.onSaveRequested(force)
    fun onCategorySelected(category: String) = model.onCategorySelected(category)
    fun onSubcategoryToggled(subcategory: String) = model.onSubcategoryToggled(subcategory)
    fun onSeasonToggled(season: Season) = model.onSeasonToggled(season)
    fun onColorToggled(color: String) = model.onColorToggled(color)
    fun onBrandChanged(brand: String) = model.onBrandChanged(brand)
    fun onSizeChanged(size: String) = model.onSizeChanged(size)
    fun onTagsChanged(tags: List<String>) = model.onTagsChanged(tags)
    fun onAttributesChanged(attributes: GarmentAttributes) = model.onAttributesChanged(attributes)
    fun onPhotoSelected(index: Int) = model.onPhotoSelected(index)
    fun onPhotoRemoved(index: Int) = model.onPhotoRemoved(index)
    fun onPhotoPicked(source: Uri) = model.onPhotoPicked(source)
    fun onImportUrlChanged(url: String) = model.onImportUrlChanged(url)
    fun onImportRequested() = model.onImportRequested()
    fun onSharedLinkReceived(url: String) = model.onSharedLinkReceived(url)
    fun onSharedLinkConfirmed() = model.onSharedLinkConfirmed()
    fun onSharedLinkDismissed() = model.onSharedLinkDismissed()
    fun onImportProblemDismissed() = model.onImportProblemDismissed()
    fun onCameraUnavailable() = model.onCameraUnavailable()
    fun onCropFailed() = model.onCropFailed()
    fun onRemoveBackground() = model.onRemoveBackground()
    fun onUndoBackground() = model.onUndoBackground()
    fun onDuplicateWarningDismissed() = model.onDuplicateWarningDismissed()
    fun onErrorDismissed() = model.onErrorDismissed()
}
