package com.wardrobapp.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The home screen's three numbers, from one trip to wherever the wardrobe is.
 *
 * A type rather than a Triple: three Longs positionally is exactly the shape
 * where a transposition compiles and shows the wrong count on the wrong card.
 */
@Serializable
data class HomeCounts(val items: Long, val archived: Long, val rated: Long)

/** Where the home screen's numbers come from. See ScreenModels.kt. */
fun interface HomeSource {
    suspend fun counts(): HomeCounts
}

/** What HomeViewModel did, in common code. See ScreenModels.kt. */
class HomeScreenModel(
    private val scope: CoroutineScope,
    private val source: HomeSource,
) {
    private val _state = MutableStateFlow(HomeScreenState())
    val state: StateFlow<HomeScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }

        scope.launch {
            attempt { source.counts() }
                .onSuccess { counts ->
                    _state.update {
                        it.copy(
                            loading = false,
                            items = counts.items,
                            archived = counts.archived,
                            rated = counts.rated,
                            error = null,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
        }
    }
}
