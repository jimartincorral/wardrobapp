package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.isoTimestamp
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

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

        // Only once it is out of the form: a photo deleted before the state was
        // updated would leave a reference to a file that is gone.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                removed?.let { container.photos.delete(it) }
                removedCutout?.takeIf { it.isNotEmpty() }?.let { container.photos.delete(it) }
            }
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

        if (garmentId == null) {
            container.garmentWrites.insert(
                GarmentWrites.NewGarment(
                    id = UUID.randomUUID().toString(),
                    imageUri = form.imageUris.first(),
                    imageUriNoBg = form.bgRemovedUris.firstOrNull()?.ifEmpty { null },
                    imageUris = form.imageUris,
                    imageUrisNoBg = form.bgRemovedUris,
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
                    imageUri = form.imageUris.first(),
                    imageUriNoBg = form.bgRemovedUris.firstOrNull() ?: "",
                    imageUris = form.imageUris,
                    imageUrisNoBg = form.bgRemovedUris,
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
