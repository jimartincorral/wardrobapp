package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.MATERIALS
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
 *
 * Adding several is the same screen again: bulk add opens one of these per
 * photo in its queue (see BulkAddScreenModel). It had a form of its own once,
 * asking only for a category, a type and a brand, and that is exactly the drift
 * this paragraph is about -- every field added here since was a field bulk add
 * did not have.
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
    /**
     * A photo already in the wardrobe's storage, for the form to start with:
     * one of bulk add's queue, which copies every picked photo in as it arrives
     * and opens a form like this one per photo. Treated as one this form made,
     * so removing it from the form, or skipping the garment, deletes the file --
     * nothing else points at it until the garment is saved.
     */
    private val photo: String? = null,
    /**
     * Called once the garment is written, beside [GarmentFormScreenState.saved].
     *
     * The single form's screen leaves when it sees `saved`; bulk add's queue
     * opens the next garment instead, and a callback is how it hears without
     * keeping a collector running on every form it has ever opened.
     */
    private val onSaved: () -> Unit = {},
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
        } else {
            if (wanted != null) {
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
            // Also only when adding: an edit's photos are the garment's own.
            photo?.let(::addStored)
        }
    }

    // ---- the form itself ----------------------------------------------------

    private fun edit(transform: (GarmentFormState) -> GarmentFormState) {
        _state.update { it.copy(form = transform(it.form), duplicates = emptyList()) }
    }

    fun onCategorySelected(category: String) = edit {
        // A type belongs to a category, so changing the category drops the type
        // rather than leaving one that no longer applies.
        it.copy(category = category, subcategories = emptyList(), typeChosen = true)
    }

    fun onSubcategoryToggled(subcategory: String) = edit { form ->
        // Choosing a type implies seasons -- a parka is not summerwear -- and
        // withSubcategories fills them in only while none have been chosen, so an
        // explicit choice is never overwritten.
        form.withSubcategories(form.subcategories.toggled(subcategory), ::seasonsForSubcategories)
            .copy(typeChosen = true)
    }

    fun onSeasonToggled(season: Season) = edit { it.copy(seasons = it.seasons.toggled(season)) }

    fun onColorToggled(color: String) = edit { it.withColorToggled(color) }

    fun onBrandChanged(brand: String) = edit { it.copy(brand = brand) }

    fun onSizeChanged(size: String) = edit { it.copy(size = size) }

    fun onTagsChanged(tags: List<String>) = edit { it.copy(tags = tags) }

    /** Add [material] to what the garment is made of, or take it off; kept in the catalogue's order. */
    fun onMaterialToggled(material: String) = edit { form ->
        val toggled = form.materials.toggled(material)
        form.copy(materials = MATERIALS.filter { it in toggled })
    }

    fun onAttributesChanged(attributes: GarmentAttributes) = edit { it.copy(attributes = attributes, attributesChosen = true) }

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
                    _state.update { it.copy(saving = false) }
                    addStored(stored)
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

    /**
     * A stored photo joins the form: shown, and read for its colours and what it
     * is. Whether it was picked just now or queued by bulk add before this form
     * existed, from here on it is the same photo.
     */
    private fun addStored(stored: String) {
        created.add(stored)
        _state.update { it.copy(form = it.form.withImage(stored), duplicates = emptyList()) }
        detectColors()
        readPhoto()
    }

    /**
     * Put a re-cropped photo in place of the one it was cropped from.
     *
     * Bulk add's photos skip the crop screen on the way in -- thirty crop screens
     * in a row is the tedium that screen exists to remove -- so the form offers a
     * crop per photo while it is one of a queue. The crop screen writes to a
     * scratch file the next crop overwrites, so the result is stored like any
     * picked photo before the form points at it.
     *
     * The photo it replaces is deleted only once the new one is in place, and
     * only if this form made it: a delete first and a failure second would leave
     * the form pointing at nothing, and a photo the stored garment owns is the
     * row's until the next save, the same as everywhere else here.
     */
    fun onPhotoCropped(cropped: Picked) {
        val form = _state.value.form
        val previous = form.imageUris.getOrNull(form.selectedImageIndex)?.takeIf { it.isNotEmpty() } ?: return
        val previousCutout = form.bgRemovedUris.getOrNull(form.selectedImageIndex)

        _state.update { it.copy(saving = true, error = null) }

        scope.launch {
            attempt { photos.store(cropped) }
                .onSuccess { stored ->
                    created.add(stored)
                    val placed = _state.value.form.withPhotoReplaced(previous, stored)
                    if (placed == null) {
                        // Its photo was removed while this was storing, so the
                        // crop belongs to nothing.
                        _state.update { it.copy(saving = false) }
                        discardIfOurs(stored)
                        return@onSuccess
                    }
                    _state.update { it.copy(saving = false, form = placed, duplicates = emptyList()) }
                    discardIfOurs(previous)
                    discardIfOurs(previousCutout)
                    // A crop is a different set of pixels: what was read off the
                    // old framing -- a doorframe's colour, a second garment at the
                    // edge -- is an answer about a photo that no longer exists.
                    detectColors()
                    readPhoto()
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

    /**
     * Throw the garment away unsaved: every file this form made is deleted,
     * since no row will ever point at any of them.
     *
     * Bulk add's skip. Suspending rather than launched on this form's scope, so
     * that the caller -- which is done with this form and about to open the next
     * one -- decides where the deleting happens and can wait for it. Only what
     * this form made: an edit's own photos are the stored garment's.
     */
    suspend fun discard() {
        val files = created.toList()
        created.clear()
        // A file left behind is not worth stopping for, and Optimize storage
        // sweeps photos nothing points at.
        for (file in files) attempt { photos.delete(file) }
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
                    // The page first: what the shop says the garment is beats
                    // what its photo looks like, and every reading of the photos
                    // after this fills only what the page left open.
                    pageReading = if (isEditing) null else attempt { photos.readPage(preview.title, preview.details) }.getOrNull()
                    _state.update {
                        it.copy(
                            form = it.form.withImportedPreview(preview.downloadedImageUris, preview.brand)
                                .let { form -> pageReading?.let { page -> form.withReading(page, ::seasonsForSubcategories) } ?: form },
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
                    readPhoto()
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

    // ---- reading what the photo shows ------------------------------------------

    /** How many readings are under way; see readPhoto. */
    private var readsRunning = 0

    /**
     * What the shop page the garment was imported from says about it, kept so
     * that each reading of its photos only adds to it; null for a garment not
     * imported. See runImport.
     */
    private var pageReading: GarmentReading? = null

    /**
     * Read the selected photo's type, attributes, material and brand into the
     * form, wherever nobody has said anything yet.
     *
     * [detectColors]' twin, at the same three moments and for the same reasons
     * -- a photo arrives, or a cut-out makes a better one of it -- with the same
     * guard against a photo that changed while it ran. Only when adding: an
     * existing garment's type and attributes are what was saved, the same way
     * its colours are, and a photo added to it later is a photo of the same
     * garment, not a question about what it is.
     *
     * Silent about failure too. A phone without Gemini Nano and without the
     * style model reads nothing, and the form is exactly what it was.
     */
    private fun readPhoto() {
        if (isEditing) return
        val photo = _state.value.form.displayedPreviewUri()
        if (photo.isNullOrEmpty()) return

        readsRunning++
        _state.update { it.copy(readingPhoto = true) }

        scope.launch {
            val reading = attempt { photos.read(photo) }.getOrNull()
            // Counted rather than flagged: a cut-out made while the original
            // is still being read starts a second reading, and the first one
            // landing must not say the wait is over.
            readsRunning--

            _state.update { state ->
                state.copy(
                    readingPhoto = readsRunning > 0,
                    form = if (reading != null && state.form.displayedPreviewUri() == photo) {
                        state.form.withReading(pageReading?.orElse(reading) ?: reading, ::seasonsForSubcategories)
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
                    readPhoto()
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
                    if (matches.isEmpty()) {
                        // The files are the garment's now, so nothing this form
                        // made is its to delete any more -- a discard() after
                        // this must not take the saved garment's photos with it.
                        created.clear()
                        onSaved()
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
                    val (customTags, seasons, attributes, materials) = splitStructuredTags(record.tags)
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
                                materials = materials,
                                brand = record.brand ?: "",
                                colorPalette = record.palette,
                                // The garment's saved colours are a choice already
                                // made, so nothing detects over them -- including a
                                // background removed on a garment being edited.
                                colorsChosen = true,
                                // So are its type and attributes, which matters
                                // less than it sounds: nothing reads the photo of
                                // a garment being edited (see readPhoto).
                                typeChosen = true,
                                attributesChosen = true,
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
