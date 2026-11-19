package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.presentation.DatabaseGarmentFormSource
import com.wardrobapp.presentation.FetchingGarmentImporter
import com.wardrobapp.presentation.GarmentFormScreenModel
import com.wardrobapp.presentation.GarmentFormSource
import com.wardrobapp.presentation.GarmentImporter
import kotlinx.coroutines.Dispatchers

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
 *
 * Unlike the other ViewModels here it hands out its [model] rather than
 * forwarding each call. The form is drawn from two places now -- on its own,
 * and as each garment of bulk add's queue, where BulkAddViewModel holds the
 * models -- and MainActivity wires both through one composable that takes the
 * screen model. Forwarders here would be a second list of the form's callbacks
 * to keep in step with that one, and a list kept in step by hand is how bulk add
 * once ended up with a form missing most of its fields.
 */
class GarmentFormViewModel(
    container: AppContainer,
    /** Null when adding. */
    garmentId: String?,
    /** What a gap suggested, when the form was opened from one. */
    wanted: PhantomGarment? = null,
) : ViewModel() {

    val model: GarmentFormScreenModel<Uri> = GarmentFormScreenModel(
        scope = viewModelScope,
        photos = PhonePhotoWork(container),
        source = garmentFormSource(container),
        importer = garmentImporter(container),
        garmentId = garmentId,
        wanted = wanted,
    )

    /**
     * Whatever this screen saved -- a new garment, a new photo -- has no
     * vector yet. Asked for as the screen goes, saved or not, since a pass
     * with nothing to do costs two queries and this way the save path needs
     * to know nothing about the style model.
     */
    private val styleIndex = container.styleIndex

    override fun onCleared() {
        styleIndex.refresh()
    }
}

/** Where a garment form reads and writes on the phone: its own database. Shared with bulk add's forms. */
internal fun garmentFormSource(container: AppContainer): GarmentFormSource = DatabaseGarmentFormSource(
    garments = container.garments,
    garmentWrites = container.garmentWrites,
    duplicates = container.duplicates,
    deletePhoto = container.photos::delete,
    io = Dispatchers.IO,
)

/** URL import for a garment form, through :net's fetcher. Shared with bulk add's forms. */
internal fun garmentImporter(container: AppContainer): GarmentImporter =
    FetchingGarmentImporter(container::importPages, container.importImages, Dispatchers.IO)
