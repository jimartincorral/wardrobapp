package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.HomeScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three counts and a set of doors.
 *
 * No pure module behind this one, deliberately: there is no arithmetic to get
 * wrong. Each count is a single query that Analytics and Settings already read,
 * and everything else on the screen is navigation.
 *
 * The third is not on screen anywhere. It is what the first-steps card's last row
 * asks -- has anybody rated an outfit -- and it is read here rather than by the
 * card, because deciding what the card says from a number this model already
 * fetches is cheaper than a second model and a second read.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * Three numbers from one trip to the database.
     *
     * A type rather than a Triple: three Longs positionally is exactly the shape
     * where a transposition compiles and shows the wrong count on the wrong card.
     */
    private data class Counts(val items: Long, val archived: Long, val rated: Long)

    private val _state = MutableStateFlow(HomeScreenState())
    val state: StateFlow<HomeScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            try {
                val counts = withContext(Dispatchers.IO) {
                    Counts(
                        items = container.garments.availableCount(),
                        archived = container.garments.unavailableCount(),
                        rated = container.outfits.ratedCount(),
                    )
                }
                _state.update {
                    it.copy(
                        loading = false,
                        items = counts.items,
                        archived = counts.archived,
                        rated = counts.rated,
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
}
