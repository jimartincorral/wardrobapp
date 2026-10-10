package com.wardrobapp.presentation

import com.wardrobapp.data.InspirationRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the looks the reader likes are kept.
 *
 * A look is a photo from anywhere -- a magazine, a shop, somebody on the
 * street -- that the Home Assistant app's style model embeds beside the
 * wardrobe's own photos, so the suggestions can lean towards outfits that
 * look like it (see StyleTaste in :domain). The photo is stored first, the
 * way a garment's is, and [add] is told where; the source keeps the record
 * and the server embeds it in its own time.
 */
interface InspirationSource {
    suspend fun looks(): List<InspirationRecord>

    /** Keep an already stored [photo], by its reference, as a look; the record. */
    suspend fun add(photo: String): InspirationRecord

    /** Forget a look and its photo. */
    suspend fun delete(id: String)
}

data class InspirationScreenState(
    val looks: List<InspirationRecord> = emptyList(),
    val loading: Boolean = true,
    /** Whether a photo is on its way in: the add button waits, so a double tap is not two looks. */
    val adding: Boolean = false,
    val error: String? = null,
)

/**
 * The Inspiration screen: a grid of looks, one way to add and one to remove.
 *
 * The screen does not know how a photo is picked or stored -- that is the
 * platform's PhotoWork, as on the garment form -- so [onPhotoStored] takes a
 * reference that is already stored and makes a look of it. A delete is
 * immediate and final: a look is a photo the reader can add again in a tap,
 * which is less than an undo is worth.
 */
class InspirationScreenModel(
    private val scope: CoroutineScope,
    private val source: InspirationSource,
) {
    private val _state = MutableStateFlow(InspirationScreenState())
    val state: StateFlow<InspirationScreenState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            attempt { source.looks() }
                .onSuccess { looks -> _state.update { it.copy(looks = looks, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readableMessage()) } }
        }
    }

    /** Called with the stored reference once the platform has stored a picked photo. */
    fun onPhotoStored(photo: String) {
        if (_state.value.adding) return
        _state.update { it.copy(adding = true, error = null) }
        scope.launch {
            attempt { source.add(photo) }
                .onSuccess { look -> _state.update { it.copy(adding = false, looks = listOf(look) + it.looks) } }
                .onFailure { e -> _state.update { it.copy(adding = false, error = e.readableMessage()) } }
        }
    }

    /** The platform is about to pick and store a photo; shown as busy meanwhile. */
    fun onAddStarted() {
        _state.update { it.copy(adding = true, error = null) }
    }

    /** The pick came back with nothing, or the store failed: not busy any more, and said if it failed. */
    fun onAddAbandoned(error: String? = null) {
        _state.update { it.copy(adding = false, error = error) }
    }

    fun onDeleteRequested(id: String) {
        // Gone from the grid at once; put back if the delete fails.
        val before = _state.value.looks
        _state.update { it.copy(looks = before.filterNot { look -> look.id == id }, error = null) }
        scope.launch {
            attempt { source.delete(id) }
                .onFailure { e -> _state.update { it.copy(looks = before, error = e.readableMessage()) } }
        }
    }

    fun onErrorDismissed() {
        _state.update { it.copy(error = null) }
    }
}
