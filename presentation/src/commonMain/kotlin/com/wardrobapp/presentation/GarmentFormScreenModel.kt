package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.GarmentAttributes
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.domain.seasonsForSubcategories
import com.wardrobapp.domain.splitStructuredTags
import com.wardrobapp.presentation.GarmentFormScreenState.ImportProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the garment form's garment, brands and duplicates come from, and where it is saved. */
interface GarmentFormSource {
    /** Null when there is no such garment. */
    suspend fun garment(id: String): GarmentRecord?

    /** Every brand the wardrobe already has, for the field's suggestions. */
    suspend fun brands(): List<String>

    /** Garments this one would duplicate, if it were saved. */
    suspend fun duplicatesOf(candidate: DuplicateCandidate): List<DuplicateGarment>

    /**
     * Store the form as a new garment ([garmentId] null) or over an existing one,
     * then delete what the save orphaned -- the originals collapsed away for their
     * cut-outs, and anything in [previouslyStored] the garment no longer
     * references. Only after the row is written: deleting sooner would break a
     * garment whose edit was abandoned.
     */
    suspend fun save(garmentId: String?, form: GarmentFormState, previouslyStored: List<String>)
}

/** Filling the form in from a product page. */
interface GarmentImporter {
    /**
     * [url] as it would be fetched, or an UnsafeUrlException saying why it will
     * not be. Asked before anything is fetched, so an address on the local
     * network is declined rather than offered as a choice.
     */
    fun check(url: String): String

    /**
     * Fetch the page and its photos. The photos are stored as they arrive, the
     * way a picked photo is, and come back as stored references.
     *
     * Throws UnsafeUrlException for an address refused on the way -- a redirect
     * onto the local network included -- GarmentImportException for a page that
     * is not a garment, and whatever the network says for the rest.
     */
    suspend fun import(url: String): ImportedGarmentPreview
}

/**
 * Adding or editing a garment: what GarmentFormViewModel did, in common code.
 * See ScreenModels.kt, and PhotoWork for why it is generic over [Picked].
 *
 * The form's rules are in :presentation, as pure transitions over
 * [GarmentFormState]; this holds the current one, has the photo and storage
 * work done, and decides nothing.
 *
 * Editing and adding are the same screen with a different starting state and a
 * different write at the end, which is how the React Native app has it too --
 * the alternative is two screens that drift.
 */
class GarmentFormScreenModel<Picked>(
    private val scope: CoroutineScope,
    private val photos: PhotoWork<Picked>,
    private val source: GarmentFormSource,
    private val importer: GarmentImporter,
    /** Null when adding. */
    private val garmentId: String?,
    /**
     * What a gap suggested, when the form was opened from one.
     *
     * The same type the statistics page emitted, carried through the route rather
     * than re-derived: whatever the analysis decided is what the form should say,
     * and a second guess at it here could disagree with the card the reader tapped.
     */
    private val wanted: PhantomGarment? = null,
) {
    private val _state = MutableStateFlow(GarmentFormScreenState())
    val state: StateFlow<GarmentFormScreenState> = _state.asStateFlow()

    /**
     * Files this form created, which nothing else can be referencing yet.
     *
     * The distinction that decides when a photo may be deleted. A file this form
     * made -- an imported photo, a cut-out -- is disposable the moment the form
     * stops pointing at it. A file belonging to the garment already stored is
     * not: its row still references it until the next save goes through, so
     * deleting it early means backing out of an edit leaves the garment showing a
     * gap where a photo was.
     */
    private val created = mutableSetOf<String>()

    /** What the garment referenced when it was loaded, for cleanup after a save. */
    private var storedRefs: List<String> = emptyList()

    val isEditing: Boolean = garmentId != null

    init {
        loadBrands()
        if (garmentId != null) {
            load(garmentId)
        } else if (wanted != null) {
            // Only when adding. A prefill on an edit would overwrite the garment
            // being edited with a description of a different one.
            _state.update {
                it.copy(
                    form = it.form.prefilledFor(
                        category = wanted.category,
                        subcategory = wanted.subcategory,
                        colour = wanted.colorPrimary,
                        seasonsFor = ::seasonsForSubcategories,
                    ),
                )
            }
        }
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

    fun onAttributesChanged(attributes: GarmentAttributes) = edit { it.copy(attributes = attributes) }

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
     * draft until it is saved, and the garment it came from is not.
     */
    private fun discardIfOurs(photo: String?) {
        if (photo.isNullOrEmpty() || !created.remove(photo)) return
        scope.launch { attempt { photos.delete(photo) } }
    }

    fun suggestionsFor(brand: String): List<String> =
        brandSuggestions(known = _state.value.brands, typed = brand)

    // ---- photos --------------------------------------------------------------

    /**
     * Import a picked photo.
     *
     * Stored before it reaches the form, so what the form holds is always a file
     * the wardrobe owns rather than something belonging to a picker that may not
     * grant access again after a restart.
     */
    fun onPhotoPicked(picked: Picked) {
        _state.update { it.copy(saving = true, error = null) }

        scope.launch {
            attempt { photos.store(picked) }
                .onSuccess { stored ->
                    created.add(stored)
                    _state.update { it.copy(saving = false, form = it.form.withImage(stored), duplicates = emptyList()) }
                    detectColors()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            saving = false,
                            error = e.message,
                            errorFallback = ErrorFallback.PHOTO_NOT_IMPORTED,
                            errorTitle = ErrorTitle.PHOTO,
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
            importer.check(url)
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
     * The photos are stored as they arrive -- the same way a picked photo is -- so
     * they are registered as [created]: nothing else references them yet, and
     * backing out of the form should not leave them behind.
     */
    private fun runImport(url: String) {
        _state.update { it.copy(urlImport = it.urlImport.copy(running = true, problem = null)) }

        scope.launch {
            attempt { importer.import(url) }
                .onSuccess { preview ->
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
                    // An import brings photos of the garment like any other route in.
                    detectColors()
                }
                .onFailure { error ->
                    failImport(
                        when (error) {
                            is UnsafeUrlException -> ImportProblem.Unsafe(error.reason)
                            is GarmentImportException -> ImportProblem.Failed(error.reason)
                            // The network stack's own words: a DNS failure, a
                            // refused connection, cleartext being blocked. Not
                            // translatable, and better than a shrug.
                            else -> ImportProblem.Foreign(error.message)
                        },
                    )
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
        it.copy(error = null, errorFallback = ErrorFallback.NO_CAMERA, errorTitle = ErrorTitle.PHOTO)
    }

    /**
     * The crop screen gave up on the photo.
     *
     * Reported by the screen for the same reason as the above: what came back from
     * another activity is something only the activity that launched it sees. Only a
     * real failure arrives here -- cancelling a crop simply adds no photo.
     */
    fun onCropFailed() = _state.update {
        it.copy(error = null, errorFallback = ErrorFallback.PHOTO_NOT_CROPPED, errorTitle = ErrorTitle.PHOTO)
    }

    // ---- reading the colours off a photo ---------------------------------------

    /**
     * Read the selected photo's colours into the form.
     *
     * Not a button. It runs when a photo arrives and again when its background is
     * removed, because those are the two moments when there is something new to
     * read and nothing has been said about the colours yet. A button was the React
     * Native app's arrangement and it made the common case -- add a photo, accept
     * what it is -- a tap that nobody should have to know about.
     *
     * Read off whatever the preview is showing, which is the cut-out where the
     * background has been removed. That matters: the count is over the pixels of
     * the image handed in, so on an original photo a large pale background can hold
     * more of the frame than the garment does and win outright. A cut-out's
     * background is transparent and the alpha gate drops it, leaving only the
     * garment to vote. It is also why removing a background reads again: the same
     * garment, a better photo of it.
     *
     * Silent about failure, deliberately. Nobody asked for this, so a photo that
     * will not decode leaves the palette exactly as it was rather than raising a
     * dialog about a job the user did not start.
     */
    private fun detectColors() {
        val form = _state.value.form
        val photo = form.displayedPreviewUri()

        // Chosen colours are not detected over, so there is nothing to read for.
        if (photo.isNullOrEmpty() || form.colorsChosen || _state.value.detectingColor) return

        _state.update { it.copy(detectingColor = true) }

        scope.launch {
            val detected = attempt { photos.colors(photo) }.getOrNull()

            _state.update { state ->
                state.copy(
                    detectingColor = false,
                    // Only if the form is still showing the photo that was read: a
                    // photo can be added, or a background removed, while this was
                    // working, and colours from the previous image are worse than
                    // none. `withDetectedColors` checks the rest.
                    form = if (detected != null && state.form.displayedPreviewUri() == photo) {
                        state.form.withDetectedColors(detected)
                    } else {
                        state.form
                    },
                )
            }
        }
    }

    // ---- background removal --------------------------------------------------

    /**
     * Cut the selected photo out of its background.
     *
     * The original stays in the form, so undo works right up until the garment is
     * saved -- at which point the collapse in [GarmentFormState.imagesToStore]
     * keeps only the cut-out. That is the React Native app's behaviour too,
     * arrived at from both its screens.
     */
    fun onRemoveBackground() {
        val form = _state.value.form
        val photo = form.imageUris.getOrNull(form.selectedImageIndex)
        if (photo.isNullOrEmpty() || _state.value.removingBackground) return

        _state.update { it.copy(removingBackground = true, error = null) }

        scope.launch {
            attempt { photos.cutOut(photo) }
                .onSuccess { cutout ->
                    created.add(cutout)
                    // Onto the photo it was cut from, wherever that is now: the
                    // reader can select, reorder or remove photos while this runs,
                    // and the slot selected at the end may be another photo's.
                    val placed = _state.value.form.withBackgroundRemovedFrom(photo, cutout)
                    if (placed == null) {
                        // Its photo was removed meanwhile, so it belongs to nothing.
                        _state.update { it.copy(removingBackground = false) }
                        discardIfOurs(cutout)
                        return@onSuccess
                    }
                    _state.update { it.copy(removingBackground = false, form = placed, duplicates = emptyList()) }
                    // The cut-out is a better photo of the same garment: only the
                    // garment's own pixels are left in it, so its colours are worth
                    // reading again.
                    detectColors()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            removingBackground = false,
                            error = e.message,
                            errorFallback = ErrorFallback.BACKGROUND_NOT_REMOVED,
                            errorTitle = ErrorTitle.BACKGROUND,
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
            _state.update { it.copy(error = null, errorFallback = ErrorFallback.PHOTO_REQUIRED) }
            return
        }

        _state.update { it.copy(saving = true, error = null) }

        scope.launch {
            attempt {
                if (!isEditing && !force) {
                    val matches = source.duplicatesOf(form.asDuplicateCandidate())
                    if (matches.isNotEmpty()) return@attempt matches
                }
                source.save(garmentId, form, storedRefs)
                emptyList()
            }
                .onSuccess { matches ->
                    _state.update {
                        if (matches.isNotEmpty()) {
                            it.copy(saving = false, duplicates = matches)
                        } else {
                            it.copy(saving = false, saved = true, duplicates = emptyList())
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.readableMessage()) } }
        }
    }

    fun onDuplicateWarningDismissed() {
        _state.update { it.copy(duplicates = emptyList()) }
    }

    fun onErrorDismissed() {
        // Both fields, or the dialog reopens itself. `errorText()` falls back to
        // `errorFallback` whenever `error` is null, which is the normal case --
        // most of what this screen shows is a fallback string, not an exception's
        // own message -- so clearing only `error` left the fallback in place and
        // the same dialog appeared again the instant it closed. Since an
        // AlertDialog is modal, that read as the close button doing nothing and
        // the screen being stuck.
        //
        // The title goes back to its default for a related reason. Only the photo
        // and background failures set one, so whatever error came next inherited
        // the last one's: "A garment needs at least one photo" arrived under
        // "Couldn't use that photo" if the camera had failed earlier -- the
        // mislabelling `errorTitle` exists to prevent.
        _state.update { it.copy(error = null, errorFallback = null, errorTitle = ErrorTitle.SAVE) }
    }

    private fun GarmentFormState.asDuplicateCandidate() = DuplicateCandidate(
        category = category,
        // A duplicate has to be the same kind of thing, so what kind of thing this
        // is about to be has to travel with it. Without this every garment saved
        // would look untyped, and untyped is never a duplicate -- the warning
        // would go quiet rather than wrong, which is worse.
        subcategories = subcategories,
        colorPrimary = colorPalette.firstOrNull() ?: GarmentFormState.DEFAULT_COLOR,
        // Every colour, not just the leading one: a black and red shirt is not a
        // red shirt, and comparing only the dominant colour said it was.
        colorPalette = colorPalette,
    )

    // ---- loading -------------------------------------------------------------

    private fun load(id: String) {
        _state.update { it.copy(loading = true) }

        scope.launch {
            attempt { source.garment(id) }
                .onSuccess { record ->
                    if (record == null) {
                        _state.update { it.copy(loading = false, missing = true) }
                        return@onSuccess
                    }

                    storedRefs = record.displayImageUris + record.displayNoBgImageUris
                    val (customTags, seasons, attributes) = splitStructuredTags(record.tags)
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
                                attributes = attributes,
                                brand = record.brand ?: "",
                                colorPalette = record.palette,
                                // The garment's saved colours are a choice already
                                // made, so nothing detects over them -- including a
                                // background removed on a garment being edited.
                                colorsChosen = true,
                                size = record.size ?: "",
                            ).normalized(),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
        }
    }

    private fun loadBrands() {
        scope.launch {
            // A failure here costs a convenience, not the form: suggestions are
            // an autocomplete, and the field takes anything typed.
            val brands = attempt { source.brands() }.getOrDefault(emptyList())
            _state.update { it.copy(brands = brands) }
        }
    }
}
