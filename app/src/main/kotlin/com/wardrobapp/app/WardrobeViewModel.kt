package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.WardrobeFacets
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WardrobeView
import com.wardrobapp.presentation.wardrobeFacets
import com.wardrobapp.presentation.filterBy
import com.wardrobapp.presentation.orderedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wardrobe list.
 *
 * Holds state and moves work off the main thread. It decides nothing: which
 * garments a filter keeps and in what order they appear comes from
 * :presentation, and the reading from :data.
 */
class WardrobeViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val garments: List<GarmentRecord> = emptyList(),
        /** Everything the screen is narrowing by. */
        val query: WardrobeQuery = WardrobeQuery(),
        /** Whether the filter panel is open. Kept here so it survives a tab switch. */
        val filtersExpanded: Boolean = false,
        /**
         * Set when loading failed. Shown rather than swallowed: the React Native
         * app logged the error and left the list at its previous value, so a
         * failure looked exactly like an empty wardrobe.
         */
        val error: String? = null,
        /**
         * Rows or cells, and how many across.
         *
         * Alongside the query rather than inside it: it changes how the same
         * garments are drawn, not which ones they are, so it never triggers a
         * re-read. Persisted, so it is the same wardrobe you left.
         */
        val view: WardrobeView = WardrobeView(),
        /**
         * What the filter panel has to offer, from what the list holds.
         *
         * Derived when the list is, rather than in the composable: which values a
         * wardrobe contains is a fact about the wardrobe, and a screen that worked
         * it out per frame would recompute it on every scroll.
         */
        val facets: WardrobeFacets = WardrobeFacets(),
    ) {
        /** True only when the wardrobe really is empty, not when a read failed. */
        val isEmpty: Boolean get() = !loading && error == null && garments.isEmpty()

        /**
         * True when nothing matched but something would have.
         *
         * Worth distinguishing: "no garments yet" and "nothing matches these
         * filters" call for different things to do next, and the second is
         * reached by narrowing rather than by having an empty wardrobe.
         */
        val isFilteredEmpty: Boolean get() = isEmpty && query.isNarrowed
    }

    private val _state = MutableStateFlow(State(view = container.wardrobeView.view))
    val state: StateFlow<State> = _state.asStateFlow()

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
        viewModelScope.launch { reload() }
    }

    /** Reload the list, reporting a failure rather than leaving the old one. */
    private suspend fun reload() {
        val query = _state.value.query
        _state.update { it.copy(loading = true, error = null) }

        try {
            val garments = withContext(Dispatchers.IO) {
                // The database applies what it can express; :presentation
                // applies the rest and the ordering.
                container.garments
                    .allGarments(
                        GarmentQueries.Filters(
                            category = query.category,
                            // Null would mean available-only. Only asked for when
                            // the list is showing retired garments too.
                            availableOnly = if (query.includeRetired) false else null,
                            search = query.searchTerm,
                        )
                    )
                    .filterBy(query.garmentFilter())
                    .orderedBy(query.sort)
            }
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
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
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
     * database to lay the same rows out in two columns would be work for nothing.
     * Written through as it is chosen, because a preference that is only saved on
     * the way out is a preference that is lost when the app is killed.
     */
    fun onViewSelected(choice: WardrobeView) = _state.update {
        val view = it.view.withChoice(choice)
        container.wardrobeView.view = view
        it.copy(view = view)
    }

    fun onSortToggled() = narrow { it.withSortToggled() }

    fun onFiltersCleared() = narrow { it.cleared() }

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
        pendingReload = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            reload()
        }
    }

    private companion object {
        /**
         * How long a pause in typing has to be before the wardrobe is re-read.
         *
         * Long enough that ordinary typing does not trigger a read per letter,
         * short enough not to feel like lag on the last character.
         */
        const val TYPING_PAUSE_MS = 250L
    }
}
