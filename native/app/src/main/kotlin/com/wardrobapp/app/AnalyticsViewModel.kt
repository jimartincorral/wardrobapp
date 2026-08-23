package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.AnalyticsView
import com.wardrobapp.presentation.LifespanEntry
import com.wardrobapp.presentation.analyticsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wardrobe in numbers.
 *
 * Reads five counts and hands them to :presentation, which decides how long each
 * bar is. Nothing about the arithmetic is decided here.
 */
class AnalyticsViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val view: AnalyticsView? = null,
        /** Reported rather than swallowed: an empty chart is not a failed read. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val view = withContext(Dispatchers.IO) {
                    analyticsView(
                        totalItems = container.garments.availableCount(),
                        archivedItems = container.garments.unavailableCount(),
                        categoryCounts = container.analytics.byCategory()
                            .map { it.label to it.count },
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
                _state.update { it.copy(loading = false, view = view, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
