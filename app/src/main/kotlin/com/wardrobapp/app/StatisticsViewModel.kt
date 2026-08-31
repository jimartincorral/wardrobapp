package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapWithPhotos
import com.wardrobapp.domain.seasonOfMonth
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.Distribution
import com.wardrobapp.presentation.LifespanEntry
import com.wardrobapp.presentation.StatisticsView
import com.wardrobapp.presentation.statisticsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * What the wardrobe is made of, and how long the things you stop wearing lasted.
 *
 * Every tile and every bar's length comes from [statisticsView]; this reads the
 * counts, holds what the reader has opened, and decides nothing about the
 * arithmetic.
 *
 * It reads seven things where it used to read five, because `AnalyticsViewModel`
 * is gone: the retired count and the lifespans were the only numbers that screen
 * had of its own, and they are two more reads on the same trip rather than a
 * second model on a second page.
 */
class StatisticsViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val view: StatisticsView? = null,
        /** Reported rather than swallowed: an empty chart is not a failed read. */
        val error: String? = null,
        /**
         * Which categories are showing their subcategory breakdown.
         *
         * Here rather than in :presentation because it is not part of the answer:
         * which rows are open says nothing about what they contain, and the pure
         * module stays a function of the wardrobe alone.
         */
        val expanded: Set<String> = emptySet(),
        /**
         * Which sections are showing their bars.
         *
         * Empty to begin with, which is the page shut: tiles, then four headings.
         * Not persisted -- unlike the wardrobe's layout, this is where you are in
         * a page rather than how you like it drawn, and it survives a tab switch
         * for the same reason [expanded] does.
         */
        val openSections: Set<StatisticsSection> = emptySet(),
        val brandSort: BrandSort = BrandSort.COUNT,
        /**
         * Garments that look like each other, or null before anyone has asked.
         *
         * Beside the counts rather than inside [StatisticsView], because it is not
         * arithmetic over the wardrobe: it is a comparison of every garment with
         * every other, and the pure view builder stays a function of the tallies.
         *
         * Null rather than empty, and the difference is the whole reason this is
         * lazy: "nobody has looked yet" and "nothing in your wardrobe matches" are
         * different answers, and showing the second while the first is true tells
         * somebody their wardrobe is clean when nothing has checked.
         */
        val duplicates: List<DuplicateGarmentGroup>? = null,
        /**
         * What the wardrobe cannot finish, or null before anyone has asked.
         *
         * Lazy and null-until-asked for the same reasons as [duplicates], only
         * more so: the analysis runs the suggestion engine once per candidate
         * garment, which is the most expensive thing this app computes. Paying
         * for it on every return to the tab, to fill in a section most visits
         * never open, would make the whole page wait.
         *
         * Null rather than empty because the two are different answers, and the
         * wrong one is worse here than anywhere else on the page: "your wardrobe
         * has no gaps" is a claim, and showing it before anything has looked
         * would be making that claim on no evidence.
         */
        val gaps: List<GapWithPhotos>? = null,
    )

    /** The counts as read, so re-sorting brands does not re-query for them. */
    private data class Counts(
        val inUse: Long,
        val retired: Long,
        val categories: List<Distribution>,
        val colors: List<Distribution>,
        val brands: List<Distribution>,
        val subcategories: Map<String, List<Distribution>>,
        val lifespans: List<LifespanEntry>,
    )

    private var counts: Counts? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val read = withContext(Dispatchers.IO) {
                    Counts(
                        inUse = container.garments.availableCount(),
                        retired = container.garments.unavailableCount(),
                        categories = container.analytics.byCategory().asDistributions(),
                        colors = container.analytics.byColor().asDistributions(),
                        brands = container.analytics.byBrand().asDistributions(),
                        subcategories = container.analytics.bySubcategory()
                            .mapValues { (_, subs) -> subs.asDistributions() },
                        lifespans = container.analytics.lifespans(container.imageDirectory)
                            .map { lifespan ->
                                LifespanEntry(
                                    garmentId = lifespan.garment.id,
                                    category = lifespan.garment.category,
                                    subcategories = lifespan.garment.effectiveSubcategories,
                                    days = lifespan.days,
                                )
                            },
                    )
                }

                counts = read
                val reopening = _state.value.openSections
                _state.update {
                    it.copy(
                        loading = false,
                        view = read.viewSortedBy(it.brandSort),
                        // Dropped rather than recomputed. This runs on every return
                        // to the tab, and the sweep is by far the most expensive
                        // thing the screen can ask for: it loads every garment and
                        // compares each against every other in its category. Doing
                        // that to fill in a section nobody has opened made the whole
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
                // ever ask for them again: they fill in when a section is opened,
                // and these are already open. This runs on every return to the tab
                // -- see RefreshOnReturn -- so without it, opening a section,
                // tapping through to a garment and coming back left it spinning
                // forever with nothing on the way.
                if (StatisticsSection.DUPLICATES in reopening) sweepForDuplicates()
                if (StatisticsSection.GAPS in reopening) lookForGaps()
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Open or close one section of the page. */
    fun onSectionTapped(section: StatisticsSection) {
        val opening = section !in _state.value.openSections

        _state.update {
            it.copy(
                openSections = if (opening) {
                    it.openSections + section
                } else {
                    it.openSections - section
                }
            )
        }

        // The only section that has to go and find its answer. Asked for here
        // rather than in `refresh` so that the cost falls on opening it, and only
        // the first time it is opened between one read of the wardrobe and the
        // next.
        if (opening && section == StatisticsSection.DUPLICATES && _state.value.duplicates == null) {
            sweepForDuplicates()
        }

        if (opening && section == StatisticsSection.GAPS && _state.value.gaps == null) {
            lookForGaps()
        }
    }

    /**
     * The sweep in flight, so shutting and reopening the section does not start a
     * second one. It stays null while nothing is running, which is also how
     * [sweepForDuplicates] knows a previous one finished or failed.
     */
    private var sweep: Job? = null

    private fun sweepForDuplicates() {
        if (sweep?.isActive == true) return

        sweep = viewModelScope.launch {
            try {
                val groups = withContext(Dispatchers.IO) { container.duplicates.groups() }
                _state.update { it.copy(duplicates = groups) }
            } catch (e: Exception) {
                // Deliberately not `error`: the counts are on screen and correct,
                // and turning a page that mostly worked into an error page would be
                // a worse answer than one section that did not fill in. Leaving it
                // null means opening the section again tries again.
                _state.update { it.copy(duplicates = null) }
            }
        }
    }

    /**
     * The gap analysis in flight, so shutting and reopening the section does not
     * start a second one. Its own field rather than shared with [sweep]: the two
     * sections are independent, and one cancelling the other would leave whichever
     * lost the race showing a spinner forever.
     */
    private var search: Job? = null

    private fun lookForGaps() {
        if (search?.isActive == true) return

        search = viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    container.gaps.analyze(
                        // Read here because this is the layer allowed a clock. The
                        // analysis only ever sees the answer, which is what lets
                        // the same wardrobe be told the same thing twice.
                        currentSeason = seasonOfMonth(Calendar.getInstance().get(Calendar.MONTH)),
                    )
                }
                _state.update { it.copy(gaps = found) }
            } catch (e: Exception) {
                // Not `error`, exactly as the duplicate sweep does not: the counts
                // above are on screen and correct, and turning a page that mostly
                // worked into an error page is a worse answer than one section
                // that did not fill in. Null means opening it again tries again.
                _state.update { it.copy(gaps = null) }
            }
        }
    }

    /** Open or close one category's subcategory breakdown. */
    fun onCategoryTapped(category: String) {
        _state.update {
            it.copy(
                expanded = if (category in it.expanded) it.expanded - category else it.expanded + category
            )
        }
    }

    /**
     * By count or by name.
     *
     * Re-derived from the counts already read rather than re-queried: the order
     * is the module's business and the numbers have not changed.
     */
    fun onBrandSortChanged(sort: BrandSort) {
        _state.update { it.copy(brandSort = sort, view = counts?.viewSortedBy(sort) ?: it.view) }
    }

    private fun Counts.viewSortedBy(sort: BrandSort): StatisticsView = statisticsView(
        inUse = inUse,
        categories = categories,
        colors = colors,
        brands = brands,
        subcategories = subcategories,
        brandSort = sort,
        retired = retired,
        lifespans = lifespans,
    )

    /**
     * The queries call every key a `label`; the module calls a key a key.
     *
     * The same adapter the React Native screen needs, for the same reason: these
     * distributions hold colours and brands as often as they hold categories.
     */
    private fun List<AnalyticsQueries.Count>.asDistributions(): List<Distribution> =
        map { Distribution(key = it.label, count = it.count) }
}
