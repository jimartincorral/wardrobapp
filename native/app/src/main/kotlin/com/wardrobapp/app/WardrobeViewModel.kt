package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.GarmentFilter
import com.wardrobapp.presentation.GarmentSort
import com.wardrobapp.presentation.filterBy
import com.wardrobapp.presentation.orderedBy
import kotlinx.coroutines.Dispatchers
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
        val search: String = "",
        val sort: GarmentSort = GarmentSort.NEWEST,
        val filter: GarmentFilter = GarmentFilter(),
        /**
         * Set when loading failed. Shown rather than swallowed: the React Native
         * app logged the error and left the list at its previous value, so a
         * failure looked exactly like an empty wardrobe.
         */
        val error: String? = null,
    ) {
        /** True only when the wardrobe really is empty, not when a read failed. */
        val isEmpty: Boolean get() = !loading && error == null && garments.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val current = _state.value
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val garments = withContext(Dispatchers.IO) {
                    // The database applies what it can express; :presentation
                    // applies the rest and the ordering.
                    container.garments
                        .allGarments(GarmentQueries.Filters(search = current.search.ifBlank { null }))
                        .filterBy(current.filter)
                        .orderedBy(current.sort)
                }
                _state.update { it.copy(loading = false, garments = garments, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun onSearchChanged(search: String) {
        _state.update { it.copy(search = search) }
        refresh()
    }

    fun onSortToggled() {
        _state.update {
            it.copy(sort = if (it.sort == GarmentSort.NEWEST) GarmentSort.OLDEST else GarmentSort.NEWEST)
        }
        refresh()
    }
}
