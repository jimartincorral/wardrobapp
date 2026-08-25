package com.wardrobapp.app

import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.orphanedImageRefs
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportWarning
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.domain.importGarmentFromUrl
import com.wardrobapp.domain.safeImportUrl
import com.wardrobapp.domain.mergeStructuredTags
import com.wardrobapp.domain.seasonsForSubcategories
import com.wardrobapp.domain.splitStructuredTags
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.brandSuggestions
import com.wardrobapp.presentation.dominantGarmentColor
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
        /** What the exception said, which is not translated and may be null. */
        val error: String? = null,
        /**
         * What the app was doing, for when the exception says nothing useful.
         *
         * A resource id rather than a sentence: the model has no Context, and the
         * screen is where the reader's language is known.
         */
        @StringRes val errorFallback: Int? = null,
        /**
         * What the dialog is titled.
         *
         * Defaults to "Couldn't save", which is what every error on this screen
         * used to be called -- including a failed background removal, a colour
         * that could not be read and a missing camera, none of which are saves.
         * A bug reported as "Couldn't save. That photo could not be opened" is a
         * bug report about the wrong thing, so the ones that are not saves say so.
         */
        @StringRes val errorTitle: Int = R.string.form_error_title,
        /** Set when the garment being edited is not there any more. */
        val missing: Boolean = false,
        /**
         * True while the model is cutting a photo out. Separate from [saving]
         * because it takes seconds rather than milliseconds, and the screen says
         * something different about it.
         */
        val removingBackground: Boolean = false,
        /** Where URL import has got to, if it is anywhere. */
        val urlImport: UrlImport = UrlImport(),
        /**
         * True while a photo's colour is being read.
         *
         * Separate from [saving] like [removingBackground] is, and for the same
         * reason: it is its own wait with its own thing to say about it.
         */
        val detectingColor: Boolean = false,
    )

    /**
     * URL import, as the form sees it.
     *
     * Its own type rather than six more fields on [State]: it is a self-contained
     * side conversation -- paste, confirm, wait, read what happened -- and the rest
     * of the form carries on regardless of where it has got to.
     */
    data class UrlImport(
        /** What has been typed or pasted. */
        val url: String = "",
        /**
         * An address handed over by something else, waiting for a tap.
         *
         * Present means the confirmation is on screen. A deep link or a share can
         * carry an address, and any web page, message or QR code can produce
         * either -- so fetching it unasked would let a page use this app's position
         * inside the user's network to reach whatever it names. The host is shown
         * and nothing is fetched until someone agrees.
         */
        val awaitingConfirmation: String? = null,
        val running: Boolean = false,
        /** The shop an import came from, once one has succeeded. */
        val source: String? = null,
        /** How many photos arrived, for the line under the field. */
        val imported: Int? = null,
        val warnings: List<ImportWarning> = emptyList(),
        val problem: ImportProblem? = null,
    )

    /**
     * Why an import did not happen.
     *
     * Reasons rather than sentences, so the screen can say them in the reader's
     * language -- the same arrangement as the archive failures. [Foreign] is the
     * exception that proves it: words from the network stack, which this app did
     * not write and cannot translate.
     */
    sealed interface ImportProblem {
        data class Unsafe(val reason: UnsafeUrlReason) : ImportProblem
        data class Failed(val reason: ImportFailureReason) : ImportProblem
        data class Foreign(val text: String?) : ImportProblem
    }

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
                    it.copy(
                        saving = false,
                        error = e.message,
                        errorFallback = R.string.error_photo_not_imported,
                        errorTitle = R.string.error_title_photo,
                    )
                }
            }
        }
    }

    // ---- URL import -----------------------------------------------------------

    fun onImportUrlChanged(url: String) = _state.update {
        // Clearing the problem as soon as the address changes: a refusal is about
        // the address it named, and leaving it up next to a different one reads as
        // a verdict on the new one.
        it.copy(urlImport = it.urlImport.copy(url = url, problem = null))
    }

    /** Import what has been typed, now. */
    fun onImportRequested() {
        val url = _state.value.urlImport.url
        if (url.isBlank() || _state.value.urlImport.running) return
        runImport(url)
    }

    /**
     * An address arrived from somewhere else -- a share, or a link.
     *
     * Checked immediately and confirmed before anything is fetched. A refusal
     * happens here, unasked: an address on the local network is not something to
     * offer a choice about, it is something to decline.
     */
    fun onSharedLinkReceived(url: String) {
        val checked = try {
            safeImportUrl(url)
        } catch (error: UnsafeUrlException) {
            _state.update {
                it.copy(urlImport = it.urlImport.copy(url = url, problem = ImportProblem.Unsafe(error.reason)))
            }
            return
        }

        _state.update {
            it.copy(urlImport = it.urlImport.copy(url = checked, awaitingConfirmation = checked, problem = null))
        }
    }

    fun onSharedLinkConfirmed() {
        val url = _state.value.urlImport.awaitingConfirmation ?: return
        _state.update { it.copy(urlImport = it.urlImport.copy(awaitingConfirmation = null)) }
        runImport(url)
    }

    fun onSharedLinkDismissed() = _state.update {
        it.copy(urlImport = it.urlImport.copy(awaitingConfirmation = null))
    }

    fun onImportProblemDismissed() = _state.update {
        it.copy(urlImport = it.urlImport.copy(problem = null))
    }

    /**
     * Fetch a page and fill the form in from it.
     *
     * The photos are written into the wardrobe as they arrive -- through the same
     * path a picked photo takes -- so they are registered as [created]: nothing
     * else references them yet, and backing out of the form should not leave them
     * behind.
     */
    private fun runImport(url: String) {
        _state.update { it.copy(urlImport = it.urlImport.copy(running = true, problem = null)) }

        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    // `use`, because :domain judges the response's headers before
                    // asking for its body and may never ask -- so the connection
                    // has to be closed by whoever opened it.
                    container.importPages().use { pages ->
                        importGarmentFromUrl(url, pages, container.importImages)
                    }
                }

                created.addAll(preview.downloadedImageUris)
                _state.update {
                    it.copy(
                        form = it.form.withImportedPreview(preview.downloadedImageUris, preview.brand),
                        duplicates = emptyList(),
                        urlImport = it.urlImport.copy(
                            running = false,
                            url = preview.sourceUrl,
                            source = preview.brand,
                            imported = preview.downloadedImageUris.size,
                            warnings = preview.warnings,
                        ),
                    )
                }
            } catch (error: UnsafeUrlException) {
                failImport(ImportProblem.Unsafe(error.reason))
            } catch (error: GarmentImportException) {
                failImport(ImportProblem.Failed(error.reason))
            } catch (error: Exception) {
                // The network stack's own words: a DNS failure, a refused
                // connection, cleartext being blocked. Not translatable, and
                // better than a shrug.
                failImport(ImportProblem.Foreign(error.message))
            }
        }
    }

    private fun failImport(problem: ImportProblem) = _state.update {
        it.copy(urlImport = it.urlImport.copy(running = false, problem = problem))
    }

    /**
     * There was no camera app to ask.
     *
     * Reported by the screen rather than found here: whether an intent resolves is
     * something only the activity can know, and it finds out by the launch throwing.
     */
    fun onCameraUnavailable() = _state.update {
        it.copy(
            error = null,
            errorFallback = R.string.error_no_camera,
            errorTitle = R.string.error_title_photo,
        )
    }

    /**
     * The crop screen gave up on the photo.
     *
     * Reported by the screen for the same reason as the above: what came back from
     * another activity is something only the activity that launched it sees. Only a
     * real failure arrives here -- cancelling a crop simply adds no photo.
     */
    fun onCropFailed() = _state.update {
        it.copy(
            error = null,
            errorFallback = R.string.error_crop_failed,
            errorTitle = R.string.error_title_photo,
        )
    }

    // ---- reading a colour off a photo -----------------------------------------

    /**
     * Suggest a colour from the selected photo.
     *
     * A suggestion, not a correction: the detected colour goes to the front of the
     * palette and anything already chosen stays, which is what `withDetectedColor`
     * does. That is why this is a button rather than something that fires on every
     * photo -- the app this replaced offers it the same way.
     *
     * Read off whatever the preview is showing, which is the cut-out where the
     * background has been removed. That matters: the count is over the pixels of
     * the image handed in, so on an original photo a large pale background can hold
     * more of the frame than the garment does and win outright. A cut-out's
     * background is transparent and the alpha gate drops it, leaving only the
     * garment to vote.
     */
    fun onDetectColorRequested() {
        val form = _state.value.form
        val photo = form.displayedPreviewUri()
        if (photo.isNullOrEmpty() || _state.value.detectingColor) return

        _state.update { it.copy(detectingColor = true, error = null) }

        viewModelScope.launch {
            try {
                val detected = withContext(Dispatchers.IO) {
                    container.photos
                        .pixelsFor(photo.toUri(), COLOR_SAMPLE_WIDTH)
                        ?.let { dominantGarmentColor(it) }
                }

                _state.update { state ->
                    state.copy(
                        detectingColor = false,
                        // A photo that would not decode is not an error worth a
                        // dialog: nothing was lost and nothing was changed.
                        form = detected?.let { state.form.withDetectedColor(it) } ?: state.form,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        detectingColor = false,
                        error = e.message,
                        errorFallback = R.string.error_colors_not_read,
                        errorTitle = R.string.error_title_colors,
                    )
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
                        photo.toUri(),
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
                        error = e.message,
                        errorFallback = R.string.error_background_not_removed,
                        errorTitle = R.string.error_title_background,
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
            _state.update { it.copy(error = null, errorFallback = R.string.error_photo_required) }
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

    /**
     * Write the row, then delete what the save orphaned.
     *
     * Internal rather than private so a test can call it with a real database and
     * real files. What it does to the filesystem is the half of this that
     * :presentation cannot see: the collapse rule is tested there, and until this
     * was reachable nothing anywhere proved the original actually left the disk.
     */
    internal fun write(form: GarmentFormState) {
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

    private companion object {
        /**
         * How wide a photo is decoded to before its colour is read.
         *
         * The same 64 pixels `detectDominantColor` resized to on the other side. The
         * exact number is not what matters -- two decoders never see identical
         * pixels -- but reading a thumbnail rather than a photograph is, because it
         * is what makes the answer about the garment rather than about its weave.
         */
        const val COLOR_SAMPLE_WIDTH = 64
    }
}
