package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.orphanedImageRefs
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.mergeStructuredTags
import com.wardrobapp.domain.seasonsForSubcategories
import com.wardrobapp.domain.splitStructuredTags
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.brandSuggestions
import com.wardrobapp.presentation.toggled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
 */
class GarmentFormViewModel(
    private val container: AppContainer,
    /** Null when adding. */
    private val garmentId: String?,
) : ViewModel() {

    data class State(
        val form: GarmentFormState = GarmentFormState().normalized(),
        val brands: List<String> = emptyList(),
        val loading: Boolean = false,
        val saving: Boolean = false,
        /** Set once the garment is written, so the screen knows to leave. */
        val saved: Boolean = false,
        /**
         * Likely duplicates, shown before anything is written. Only ever set
         * when adding: editing a garment cannot make it a duplicate of itself.
         */
        val duplicates: List<DuplicateGarment> = emptyList(),
        val error: String? = null,
        /** Set when the garment being edited is not there any more. */
        val missing: Boolean = false,
        /**
         * True while the model is cutting a photo out. Separate from [saving]
         * because it takes seconds rather than milliseconds, and the screen says
         * something different about it.
         */
        val removingBackground: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Files this form created, which nothing else can be referencing yet.
     *
     * The distinction that decides when a photo may be deleted. A file this form
     * made -- an imported photo, a cut-out -- is disposable the moment the form
     * stops pointing at it. A file belonging to the garment already in the database
     * is not: its row still references it until the next save goes through, so
     * deleting it early means backing out of an edit leaves the garment showing a
     * gap where a photo was.
     */
    private val created = mutableSetOf<String>()

    /** What the garment referenced when it was loaded, for cleanup after a save. */
    private var storedRefs: List<String> = emptyList()

    val isEditing: Boolean = garmentId != null

    init {
        loadBrands()
        if (garmentId != null) load(garmentId)
    }

    // ---- the form itself ----------------------------------------------------

    private fun edit(transform: (GarmentFormState) -> GarmentFormState) {
        _state.update { it.copy(form = transform(it.form), duplicates = emptyList()) }
    }

    fun onCategorySelected(category: String) = edit {
        // A type belongs to a category, so changing the category drops the type
        // rather than leaving one that no longer applies.
        it.copy(category = category, subcategories = emptyList())
    }

    fun onSubcategoryToggled(subcategory: String) = edit { form ->
        // Choosing a type implies seasons -- a parka is not summerwear -- and
        // withSubcategories fills them in only while none have been chosen, so an
        // explicit choice is never overwritten.
        form.withSubcategories(form.subcategories.toggled(subcategory), ::seasonsForSubcategories)
    }

    fun onSeasonToggled(season: Season) = edit { it.copy(seasons = it.seasons.toggled(season)) }

    fun onColorToggled(color: String) = edit { it.withColorToggled(color) }

    fun onBrandChanged(brand: String) = edit { it.copy(brand = brand) }

    fun onSizeChanged(size: String) = edit { it.copy(size = size) }

    fun onTagsChanged(tags: List<String>) = edit { it.copy(tags = tags) }

    fun onPhotoSelected(index: Int) = edit { it.copy(selectedImageIndex = index) }

    fun onPhotoRemoved(index: Int) {
        val removed = _state.value.form.imageUris.getOrNull(index)
        val removedCutout = _state.value.form.bgRemovedUris.getOrNull(index)

        edit { it.withoutImageAt(index) }

        // Only once it is out of the form, and only if this form made it. Anything
        // the stored garment owns is left alone here and cleaned up after the save,
        // once the row has stopped referring to it.
        discardIfOurs(removed)
        discardIfOurs(removedCutout)
    }

    /**
     * Delete a file, but only one this form created.
     *
     * Nothing is deleted merely because the form stopped showing it: the form is a
     * draft until it is saved, and the garment on disk is not.
     */
    private fun discardIfOurs(uri: String?) {
        if (uri.isNullOrEmpty() || !created.remove(uri)) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.photos.delete(uri) }
        }
    }

    fun suggestionsFor(brand: String): List<String> =
        brandSuggestions(known = _state.value.brands, typed = brand)

    // ---- photos --------------------------------------------------------------

    /**
     * Import a picked photo.
     *
     * Stored before it reaches the form, so what the form holds is always a file
     * this app owns rather than a `content://` URI belonging to a picker that may
     * not grant access again after a restart.
     *
     * Handed to the form resolved rather than as the bare filename it is stored
     * under: a garment being edited arrives with resolved URIs too, and the form
     * has to be able to draw every photo in its gallery the same way. The write
     * boundary reduces them all back to filenames.
     */
    fun onPhotoPicked(source: Uri) {
        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) {
                    container.photos.store(source, UUID.randomUUID().toString())
                }
                val uri = resolveImageRef(stored, container.imageDirectory)
                created.add(uri)
                _state.update {
                    it.copy(saving = false, form = it.form.withImage(uri), duplicates = emptyList())
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(saving = false, error = e.message ?: "That photo could not be imported.")
                }
            }
        }
    }

    // ---- background removal --------------------------------------------------

    /**
     * Cut the selected photo out of its background.
     *
     * The original stays in the form, so undo works right up until the garment is
     * saved -- at which point the collapse in [imagesToStore] keeps only the
     * cut-out. That is the React Native app's behaviour too, arrived at from both
     * its screens.
     */
    fun onRemoveBackground() {
        val form = _state.value.form
        val photo = form.imageUris.getOrNull(form.selectedImageIndex)
        if (photo.isNullOrEmpty() || _state.value.removingBackground) return

        _state.update { it.copy(removingBackground = true, error = null) }

        viewModelScope.launch {
            try {
                val cutout = withContext(Dispatchers.IO) {
                    container.backgrounds.removeBackground(
                        Uri.parse(photo),
                        UUID.randomUUID().toString(),
                    )
                }
                val uri = resolveImageRef(cutout, container.imageDirectory)
                created.add(uri)

                _state.update {
                    it.copy(
                        removingBackground = false,
                        // Against the *current* form rather than the one captured
                        // above: the photo could have been changed while the model
                        // was working, and writing into a stale state would put the
                        // cut-out on the wrong photo.
                        form = it.form.withBackgroundRemoved(uri),
                        duplicates = emptyList(),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        removingBackground = false,
                        error = e.message ?: "The background could not be removed.",
                    )
                }
            }
        }
    }

    /**
     * Put the original photo back.
     *
     * The cut-out file goes with it: nothing points at it any more, and it was
     * only ever written for this form.
     */
    fun onUndoBackground() {
        val form = _state.value.form
        val cutout = form.bgRemovedUris.getOrNull(form.selectedImageIndex)

        edit { it.withBackgroundRemoved("") }

        // Only a cut-out this form made. One that came with a saved garment is
        // still referenced by its row, and would be missing if the edit were
        // abandoned rather than saved.
        discardIfOurs(cutout)
    }

    // ---- saving --------------------------------------------------------------

    /**
     * Check for duplicates, then save.
     *
     * Only when adding, and only once: a second call after the warning has been
     * shown is the user saying they meant it.
     */
    fun onSaveRequested(force: Boolean = false) {
        val form = _state.value.form

        if (form.imageUris.isEmpty()) {
            _state.update { it.copy(error = "A garment needs at least one photo.") }
            return
        }

        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                if (!isEditing && !force) {
                    val matches = withContext(Dispatchers.IO) {
                        container.duplicates.matching(form.asDuplicateCandidate())
                    }
                    if (matches.isNotEmpty()) {
                        _state.update { it.copy(saving = false, duplicates = matches) }
                        return@launch
                    }
                }

                withContext(Dispatchers.IO) { write(form) }
                _state.update { it.copy(saving = false, saved = true, duplicates = emptyList()) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(saving = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun onDuplicateWarningDismissed() {
        _state.update { it.copy(duplicates = emptyList()) }
    }

    fun onErrorDismissed() {
        _state.update { it.copy(error = null) }
    }

    private fun GarmentFormState.asDuplicateCandidate() = DuplicateCandidate(
        category = category,
        // The same tags that will be stored, so the candidate is compared as the
        // garment it is about to become.
        tags = mergeStructuredTags(tags, seasons),
        colorPrimary = colorPalette.firstOrNull() ?: GarmentFormState.DEFAULT_COLOR,
        colorPalette = colorPalette,
        size = size.ifBlank { null },
    )

    private fun write(form: GarmentFormState) {
        val now = isoTimestamp(System.currentTimeMillis())
        val tags = mergeStructuredTags(form.tags, form.seasons)

        // A slot whose background was removed stores the cut-out in both columns
        // and lets the original go. Decided in :presentation, and shared with the
        // React Native app, because both mistakes are silent ones: discard a file
        // still referenced and the garment shows a gap; miss one and it sits on
        // the phone with nothing pointing at it.
        val images = form.imagesToStore()

        if (garmentId == null) {
            container.garmentWrites.insert(
                GarmentWrites.NewGarment(
                    id = UUID.randomUUID().toString(),
                    imageUri = images.imageUris.first(),
                    imageUriNoBg = images.bgRemovedUris.firstOrNull()?.ifEmpty { null },
                    imageUris = images.imageUris,
                    imageUrisNoBg = images.bgRemovedUris,
                    category = form.category,
                    subcategories = form.subcategories,
                    tags = tags,
                    brand = form.brand.ifBlank { null },
                    colorPrimary = form.colorPalette.first(),
                    colorSecondary = form.colorPalette.getOrNull(1),
                    colorPalette = form.colorPalette,
                    size = form.size.ifBlank { null },
                    now = now,
                )
            )
        } else {
            container.garmentWrites.update(
                garmentId,
                GarmentWrites.GarmentEdit(
                    imageUri = images.imageUris.first(),
                    imageUriNoBg = images.bgRemovedUris.firstOrNull() ?: "",
                    imageUris = images.imageUris,
                    imageUrisNoBg = images.bgRemovedUris,
                    category = form.category,
                    subcategories = form.subcategories,
                    tags = tags,
                    brand = form.brand,
                    colorPrimary = form.colorPalette.first(),
                    colorSecondary = form.colorPalette.getOrNull(1) ?: "",
                    colorPalette = form.colorPalette,
                    size = form.size,
                ),
                now = now,
            )
        }

        // Only after the row is written, and all of it at once: the originals this
        // save collapsed away, plus anything the garment referenced before and no
        // longer does -- a photo removed from the form, or a cut-out undone.
        // Deleting any of it sooner would break a garment whose edit was abandoned.
        val kept = images.imageUris + images.bgRemovedUris

        for (orphan in orphanedImageRefs(storedRefs + images.discardable, kept)) {
            container.photos.delete(orphan)
        }
    }

    // ---- loading -------------------------------------------------------------

    private fun load(id: String) {
        _state.update { it.copy(loading = true) }

        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { container.garments.garment(id) }
                if (record == null) {
                    _state.update { it.copy(loading = false, missing = true) }
                    return@launch
                }

                storedRefs = record.displayImageUris + record.displayNoBgImageUris
                val (customTags, seasons) = splitStructuredTags(record.tags)
                _state.update {
                    it.copy(
                        loading = false,
                        form = GarmentFormState(
                            imageUris = record.displayImageUris,
                            bgRemovedUris = record.displayNoBgImageUris,
                            category = record.category,
                            subcategories = record.effectiveSubcategories,
                            tags = customTags,
                            seasons = seasons,
                            brand = record.brand ?: "",
                            colorPalette = record.palette,
                            size = record.size ?: "",
                        ).normalized(),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun loadBrands() {
        viewModelScope.launch {
            // A failure here costs a convenience, not the form: suggestions are
            // an autocomplete, and the field takes anything typed.
            val brands = runCatching {
                withContext(Dispatchers.IO) { container.garments.brands() }
            }.getOrDefault(emptyList())

            _state.update { it.copy(brands = brands) }
        }
    }
}
