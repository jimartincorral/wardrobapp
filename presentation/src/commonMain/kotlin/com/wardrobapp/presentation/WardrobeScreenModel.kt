package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the wardrobe list comes from. */
fun interface WardrobeSource {
    /**
     * The garments [query] selects, filtered and in its order.
     *
     * The whole query rather than the part a database can express, so the source
     * decides where each part runs: the phone's applies what SQL can say in SQL
     * and the rest with :presentation's rules, all off the main thread, and the
     * server will do the same for the browser.
     */
    suspend fun garments(query: WardrobeQuery): List<GarmentRecord>
}

/**
 * How this device likes the wardrobe drawn: a list or a grid of some width, and
 * what each cell says under its photo.
 *
 * Kept on the device rather than with the wardrobe -- a phone and a browser
 * window are different widths, and a preference for one is not a preference for
 * the other. SharedPreferences on the phone; the browser's own storage there.
 * Plain properties, because both are read and written without waiting.
 */
interface WardrobeViewSettings {
    var view: WardrobeView
    var caption: GarmentCaption
}

/** The wardrobe list: what WardrobeViewModel did, in common code. See ScreenModels.kt. */
class WardrobeScreenModel(
    private val scope: CoroutineScope,
    private val source: WardrobeSource,
    private val settings: WardrobeViewSettings,
) {
    private val _state = MutableStateFlow(
        WardrobeScreenState(view = settings.view, caption = settings.caption),
    )
    val state: StateFlow<WardrobeScreenState> = _state.asStateFlow()

    /**
     * The pending reload for a typed filter.
     *
     * Cancelled and replaced on every keystroke, so a query runs once the typing
     * stops rather than once per character. The React Native app debounces its
     * three text boxes; this one re-read the whole wardrobe on every letter.
     */
    private var pendingReload: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        pendingReload?.cancel()
        scope.launch { reload() }
    }

    /** Reload the list, reporting a failure rather than leaving the old one. */
    private suspend fun reload() {
        val query = _state.value.query
        _state.update { it.copy(loading = true, error = null) }

        attempt { source.garments(query) }
            .onSuccess { garments ->
                _state.update {
                    it.copy(
                        loading = false,
                        garments = garments,
                        // From the garments this query returned, which is what makes
                        // the choices narrow as filters are picked -- and what makes a
                        // retired garment's brand appear exactly when retired garments
                        // are being shown.
                        facets = wardrobeFacets(garments, query),
                        error = null,
                    )
                }
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
    }

    // ---- narrowing -----------------------------------------------------------

    fun onFiltersToggled() {
        _state.update { it.copy(filtersExpanded = !it.filtersExpanded) }
    }

    fun onSearchChanged(search: String) = typed { it.copy(search = search) }

    // Brands and sizes are tapped now rather than typed, so they go through
    // `narrow` like every other chip: there is nothing to debounce about a tap.
    fun onBrandTapped(brand: String) = narrow { it.withBrand(brand) }

    fun onSizeTapped(size: String) = narrow { it.withSize(size) }

    fun onCategoryTapped(id: String) = narrow { it.withCategory(id) }

    fun onSubcategoryTapped(id: String) = narrow { it.withSubcategory(id) }

    fun onSeasonTapped(season: Season) = narrow { it.withSeason(season) }

    fun onOccasionTapped(occasion: Occasion) = narrow { it.withOccasion(occasion) }

    fun onColorTapped(color: String) = narrow { it.withColor(color) }

    fun onRetiredToggled() = narrow { it.copy(includeRetired = !it.includeRetired) }

    /**
     * Draw the same wardrobe differently.
     *
     * Not through [narrow]: nothing about the query changed, so re-reading the
     * wardrobe to lay the same rows out in two columns would be work for nothing.
     * Written through as it is chosen, because a preference that is only saved on
     * the way out is a preference that is lost when the app is killed.
     */
    fun onViewSelected(choice: WardrobeView) {
        // Outside the update, not inside it. `update` re-runs its lambda when two
        // callers race, so a write in there is a write that can happen twice --
        // harmless for a preference being set to the same value, and the shape
        // that has already cost this repo two bugs elsewhere.
        val view = _state.value.view.withChoice(choice)
        settings.view = view
        _state.update { it.copy(view = view) }
    }

    /**
     * Relabel the same cells.
     *
     * Not through [narrow], for the reason above: nothing about the query changed,
     * so re-reading the wardrobe to put a different word under the same photos
     * would be work for nothing.
     */
    fun onCaptionSelected(choice: GarmentCaption) {
        settings.caption = choice
        _state.update { it.copy(caption = choice) }
    }

    fun onSortToggled() = narrow { it.withSortToggled() }

    fun onFiltersCleared() = narrow { it.cleared() }

    /**
     * Show what another screen asked for, and only that.
     *
     * The query arrives built -- [WardrobeQuery.showing] is where the rule about
     * what a link may and may not carry lives -- so this is only the re-read. It
     * replaces the query rather than adding to it, which is what makes the list
     * agree with the number that was tapped.
     */
    fun onQueryRequested(query: WardrobeQuery) = narrow { query }

    /**
     * A tap: change the query and re-read at once.
     *
     * Nothing to wait for -- a chip cannot be half-tapped the way a word can be
     * half-typed.
     */
    private fun narrow(change: (WardrobeQuery) -> WardrobeQuery) {
        _state.update { it.copy(query = change(it.query)) }
        refresh()
    }

    /**
     * A keystroke: show it immediately, read shortly.
     *
     * The text has to land in the state now or the box would not show what was
     * typed, but the query waits for a pause. Cancelling the previous pending
     * reload is what makes it a pause rather than a stream.
     */
    private fun typed(change: (WardrobeQuery) -> WardrobeQuery) {
        _state.update { it.copy(query = change(it.query)) }

        pendingReload?.cancel()
        pendingReload = scope.launch {
            delay(TYPING_PAUSE_MS)
            reload()
        }
    }

    internal companion object {
        /**
         * How long a pause in typing has to be before the wardrobe is re-read.
         *
         * Long enough that ordinary typing does not trigger a read per letter,
         * short enough not to feel like lag on the last character.
         */
        const val TYPING_PAUSE_MS = 250L
    }
}
