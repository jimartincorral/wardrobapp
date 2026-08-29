package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.Distribution
import com.wardrobapp.presentation.LifespanEntry
import com.wardrobapp.presentation.StatisticsView
import com.wardrobapp.presentation.statisticsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
         * Garments that look like each other.
         *
         * Beside the counts rather than inside [StatisticsView], because it is not
         * arithmetic over the wardrobe: it is a comparison of every garment with
         * every other, and the pure view builder stays a function of the tallies.
         */
        val duplicates: List<DuplicateGarmentGroup> = emptyList(),
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

                // On the same trip as the counts rather than a second one. It is
                // the slowest thing this screen asks for -- every garment against
                // every other within its category -- and a separate read would
                // mean the page arriving in two pieces.
                val duplicates = withContext(Dispatchers.IO) { container.duplicates.groups() }

                counts = read
                _state.update {
                    it.copy(
                        loading = false,
                        view = read.viewSortedBy(it.brandSort),
                        duplicates = duplicates,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Open or close one section of the page. */
    fun onSectionTapped(section: StatisticsSection) {
        _state.update {
            it.copy(
                openSections = if (section in it.openSections) {
                    it.openSections - section
                } else {
                    it.openSections + section
                }
            )
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
