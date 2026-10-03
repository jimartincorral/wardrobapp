package com.wardrobapp.presentation

import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapWithPhotos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every count the statistics page draws, as read.
 *
 * Kept by the model, so re-sorting brands does not re-read them. Keys rather
 * than the queries' labels -- see Distribution -- since these hold colours and
 * brands as often as categories.
 */
data class StatisticsCounts(
    val inUse: Long,
    val retired: Long,
    val categories: List<Distribution>,
    val colors: List<Distribution>,
    val brands: List<Distribution>,
    val subcategories: Map<String, List<Distribution>>,
    val lifespans: List<LifespanEntry>,
)

/** Where the statistics page's numbers come from. */
interface StatisticsSource {
    suspend fun counts(): StatisticsCounts

    /**
     * The duplicate sweep: every garment compared with every other in its
     * category. By far the most expensive thing the page can ask for, which is
     * why the model only asks when its section is opened.
     */
    suspend fun duplicates(): List<DuplicateGarmentGroup>

    /**
     * What the wardrobe is missing for this time of year.
     *
     * "This time of year" is the source's to decide, because a clock belongs to
     * the side that stores the wardrobe; the analysis only ever sees the season,
     * which is what lets the same wardrobe be told the same thing twice.
     */
    suspend fun gaps(): List<GapWithPhotos>
}

/** The statistics page: what StatisticsViewModel did, in common code. See ScreenModels.kt. */
class StatisticsScreenModel(
    private val scope: CoroutineScope,
    private val source: StatisticsSource,
) {
    private var counts: StatisticsCounts? = null

    private val _state = MutableStateFlow(StatisticsScreenState())
    val state: StateFlow<StatisticsScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        scope.launch {
            attempt { source.counts() }
                .onSuccess { read ->
                    counts = read
                    val reopening = _state.value.openSections
                    _state.update {
                        it.copy(
                            loading = false,
                            view = read.viewSortedBy(it.brandSort),
                            // Dropped rather than recomputed. This runs on every
                            // return to the tab, and the sweep is by far the most
                            // expensive thing the screen can ask for. Doing that to
                            // fill in a section nobody has opened made the whole
                            // page wait for an answer most visits never look at.
                            duplicates = null,
                            // Dropped alongside the duplicates, and for the same
                            // reason: the wardrobe has just been re-read, so an
                            // answer computed from the previous read is stale.
                            gaps = null,
                            error = null,
                        )
                    }

                    // Both sections above were just emptied, and nothing else would
                    // ever ask for them again: they fill in when a section is
                    // opened, and these are already open. This runs on every return
                    // to the tab, so without it, opening a section, tapping through
                    // to a garment and coming back left it spinning forever.
                    if (StatisticsSection.DUPLICATES in reopening) sweepForDuplicates()
                    if (StatisticsSection.GAPS in reopening) lookForGaps()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
        }
    }

    /** Open or close one section of the page. */
    fun onSectionTapped(section: StatisticsSection) {
        val opening = section !in _state.value.openSections

        _state.update {
            it.copy(openSections = if (opening) it.openSections + section else it.openSections - section)
        }

        // The sections that have to go and find their answer. Asked for here
        // rather than in `refresh` so that the cost falls on opening them, and only
        // the first time between one read of the wardrobe and the next.
        if (opening && section == StatisticsSection.DUPLICATES && _state.value.duplicates == null) {
            sweepForDuplicates()
        }
        if (opening && section == StatisticsSection.GAPS && _state.value.gaps == null) {
            lookForGaps()
        }
    }

    /**
     * The sweep in flight, so shutting and reopening the section does not start a
     * second one. Null or finished while nothing is running, which is also how
     * [sweepForDuplicates] knows a previous one finished or failed.
     */
    private var sweep: Job? = null

    private fun sweepForDuplicates() {
        if (sweep?.isActive == true) return

        sweep = scope.launch {
            // A failure leaves the section empty rather than setting `error`: the
            // counts are on screen and correct, and turning a page that mostly
            // worked into an error page would be a worse answer than one section
            // that did not fill in. Null means opening it again tries again.
            val groups = attempt { source.duplicates() }.getOrNull()
            _state.update { it.copy(duplicates = groups) }
        }
    }

    /**
     * The gap analysis in flight. Its own job rather than shared with [sweep]: the
     * two sections are independent, and one cancelling the other would leave
     * whichever lost the race showing a spinner forever.
     */
    private var search: Job? = null

    private fun lookForGaps() {
        if (search?.isActive == true) return

        search = scope.launch {
            // Not `error`, exactly as the duplicate sweep does not.
            val found = attempt { source.gaps() }.getOrNull()
            _state.update { it.copy(gaps = found) }
        }
    }

    /** Open or close one category's subcategory breakdown. */
    fun onCategoryTapped(category: String) {
        _state.update {
            it.copy(expanded = if (category in it.expanded) it.expanded - category else it.expanded + category)
        }
    }

    /**
     * By count or by name.
     *
     * Re-derived from the counts already read rather than re-read: the order is
     * the module's business and the numbers have not changed.
     */
    fun onBrandSortChanged(sort: BrandSort) {
        _state.update { it.copy(brandSort = sort, view = counts?.viewSortedBy(sort) ?: it.view) }
    }

    private fun StatisticsCounts.viewSortedBy(sort: BrandSort): StatisticsView = statisticsView(
        inUse = inUse,
        categories = categories,
        colors = colors,
        brands = brands,
        subcategories = subcategories,
        brandSort = sort,
        retired = retired,
        lifespans = lifespans,
    )
}
