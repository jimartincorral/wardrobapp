package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.BulkAddScreenModel
import com.wardrobapp.presentation.BulkAddScreenState
import com.wardrobapp.presentation.GarmentFormScreenModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Cataloguing several garments from several photos.
 *
 * What it does lives in BulkAddScreenModel, in common code, so the browser can
 * run it too: a queue of photos, and the ordinary garment form opened on each.
 * This is the Android half -- the scope that ends with the screen, PhonePhotoWork
 * for the photos, and the same database source and importer the single form is
 * given, since each garment here *is* that form.
 *
 * The garment on screen is handed out as its model, the way GarmentFormViewModel
 * hands out its own, for the reason given there: MainActivity drives both through
 * one composable.
 */
class BulkAddViewModel(container: AppContainer) : ViewModel() {

    private val model = BulkAddScreenModel(
        scope = viewModelScope,
        photos = PhonePhotoWork(container),
        source = garmentFormSource(container),
        importer = garmentImporter(container),
    )

    val state: StateFlow<BulkAddScreenState> = model.state

    /** The garment on screen; see BulkAddScreenModel.form. */
    val form: StateFlow<GarmentFormScreenModel<Uri>?> = model.form

    fun onPhotosPicked(sources: List<Uri>) = model.onPhotosPicked(sources)
    fun onSkipRequested() = model.onSkipRequested()
    fun onErrorDismissed() = model.onErrorDismissed()

    /**
     * Whatever this screen saved -- new garments, their photos -- has no vector
     * yet. Asked for as the screen goes, saved or not, since a pass with nothing
     * to do costs two queries and this way the save path needs to know nothing
     * about the style model.
     */
    private val styleIndex = container.styleIndex

    override fun onCleared() {
        styleIndex.refresh()
    }
}
