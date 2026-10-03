package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.OutfitQueries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen's three numbers, from one trip to wherever the wardrobe is.
 *
 * A type rather than a Triple: three Longs positionally is exactly the shape
 * where a transposition compiles and shows the wrong count on the wrong card.
 */
data class HomeCounts(val items: Long, val archived: Long, val rated: Long)

/** Where the home screen's numbers come from. See ScreenModels.kt. */
fun interface HomeSource {
    suspend fun counts(): HomeCounts
}

/**
 * The home screen's source on a device that holds the wardrobe itself -- the
 * phone now, and the Home Assistant server later, both over the same queries.
 *
 * The queries block, so they run on [io]: Dispatchers.IO on the JVM. It is an
 * argument rather than named here because Dispatchers.IO is not common code.
 */
class DatabaseHomeSource(
    private val garments: GarmentQueries,
    private val outfits: OutfitQueries,
    private val io: CoroutineDispatcher,
) : HomeSource {
    override suspend fun counts(): HomeCounts = withContext(io) {
        HomeCounts(
            items = garments.availableCount(),
            archived = garments.unavailableCount(),
            rated = outfits.ratedCount(),
        )
    }
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
            val counts = reportingFailure(
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } },
            ) { source.counts() } ?: return@launch

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
    }
}
