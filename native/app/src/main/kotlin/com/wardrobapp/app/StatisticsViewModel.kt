package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.AnalyticsQueries
import com.wardrobapp.presentation.BrandSort
import com.wardrobapp.presentation.Distribution
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
 * What the wardrobe is made of.
 *
 * The four distinct-count tiles and every bar's length come from
 * [statisticsView]; this reads the counts, holds what the reader has opened, and
 * decides nothing about the arithmetic. It is the screen the colour, brand and
 * subcategory queries were written for -- all three have been tested and
 * unrendered since :data was written.
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
        val brandSort: BrandSort = BrandSort.COUNT,
    )

    /** The counts as read, so re-sorting brands does not re-query for them. */
    private data class Counts(
        val total: Long,
        val categories: List<Distribution>,
        val colors: List<Distribution>,
        val brands: List<Distribution>,
        val subcategories: Map<String, List<Distribution>>,
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
                        total = container.garments.availableCount(),
                        categories = container.analytics.byCategory().asDistributions(),
                        colors = container.analytics.byColor().asDistributions(),
                        brands = container.analytics.byBrand().asDistributions(),
                        subcategories = container.analytics.bySubcategory()
                            .mapValues { (_, subs) -> subs.asDistributions() },
                    )
                }

                counts = read
                _state.update { it.copy(loading = false, view = read.viewSortedBy(it.brandSort), error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
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
        total = total,
        categories = categories,
        colors = colors,
        brands = brands,
        subcategories = subcategories,
        brandSort = sort,
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
