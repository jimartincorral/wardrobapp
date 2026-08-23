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
import java.io.InputStream

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
        /** Non-null while a restore is being asked about, run, or reported. */
        val restore: Restore? = null,
    ) {
        /** True only when the wardrobe really is empty, not when a read failed. */
        val isEmpty: Boolean get() = !loading && error == null && garments.isEmpty()
    }

    /**
     * Where a restore has got to.
     *
     * [Confirming] exists because a restore replaces the whole wardrobe, and
     * picking a file is not the same as agreeing to that.
     */
    sealed interface Restore {
        data object Confirming : Restore
        data object Running : Restore
        /** Restored; the count is absent only if the reload afterwards failed. */
        data class Done(val garments: Int?) : Restore
        data class Failed(val message: String) : Restore
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** Reload the list, reporting a failure rather than leaving the old one. */
    private suspend fun reload(): Int? {
        val current = _state.value
        _state.update { it.copy(loading = true, error = null) }

        return try {
            val garments = withContext(Dispatchers.IO) {
                // The database applies what it can express; :presentation
                // applies the rest and the ordering.
                container.garments
                    .allGarments(GarmentQueries.Filters(search = current.search.ifBlank { null }))
                    .filterBy(current.filter)
                    .orderedBy(current.sort)
            }
            _state.update { it.copy(loading = false, garments = garments, error = null) }
            garments.size
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
            null
        }
    }

    fun onRestoreRequested() {
        _state.update { it.copy(restore = Restore.Confirming) }
    }

    fun onRestoreDismissed() {
        _state.update { it.copy(restore = null) }
    }

    /**
     * Restore from an archive, then show what happened.
     *
     * Takes a way to open the archive rather than the archive itself, so nothing
     * here knows about content URIs -- and so the stream is opened on the IO
     * thread that reads it.
     */
    fun onArchivePicked(openArchive: () -> InputStream) {
        _state.update { it.copy(restore = Restore.Running) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { openArchive().use { container.restoreFrom(it) } }
            }

            // Reload either way: a refused archive changes nothing, but the list
            // on screen was loaded before the attempt and saying so costs
            // nothing.
            val garments = reload()

            _state.update {
                it.copy(
                    restore = outcome.fold(
                        onSuccess = { Restore.Done(garments) },
                        onFailure = { error ->
                            Restore.Failed(error.message ?: error.javaClass.simpleName)
                        },
                    )
                )
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
