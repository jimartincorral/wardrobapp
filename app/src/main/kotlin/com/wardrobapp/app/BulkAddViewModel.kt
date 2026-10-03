package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.BulkAddScreenModel
import com.wardrobapp.presentation.BulkAddScreenState
import com.wardrobapp.presentation.DatabaseBulkAddSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Cataloguing several garments from several photos.
 *
 * The queue's rules are in :presentation as pure transitions over
 * [BulkAddState]; this stores the photos, reads their colours off the main
 * thread, and writes a row per garment as the queue advances.
 *
 * Written as each garment is confirmed rather than all at once at the end. A
 * batch of twenty rows written on a final tap is a batch that can be lost whole
 * -- to a phone call, a low-memory kill, or somebody pressing back -- and having
 * to re-enter nineteen garments because of the twentieth is exactly the tedium
 * this screen exists to remove.
 *
 * What it does lives in BulkAddScreenModel, in common code, so the browser can
 * run it too; this is the Android half: the scope that ends with the screen,
 * PhonePhotoWork for the photos, and the phone's own database for the garments.
 */
class BulkAddViewModel(container: AppContainer) : ViewModel() {

    private val model = BulkAddScreenModel(
        scope = viewModelScope,
        photos = PhonePhotoWork(container),
        source = DatabaseBulkAddSource(container.garmentWrites, container.photos::delete, Dispatchers.IO),
    )

    val state: StateFlow<BulkAddScreenState> = model.state

    fun onPhotosPicked(sources: List<Uri>) = model.onPhotosPicked(sources)
    fun onCategorySelected(category: String) = model.onCategorySelected(category)
    fun onSubcategoryToggled(subcategory: String) = model.onSubcategoryToggled(subcategory)
    fun onBrandChanged(brand: String) = model.onBrandChanged(brand)
    fun onPhotoCropped(source: Uri) = model.onPhotoCropped(source)
    fun onCropFailed() = model.onCropFailed()
    fun onRemoveBackground() = model.onRemoveBackground()
    fun onUndoBackground() = model.onUndoBackground()
    fun onSaveRequested() = model.onSaveRequested()
    fun onSkipRequested() = model.onSkipRequested()
    fun onErrorDismissed() = model.onErrorDismissed()
}
