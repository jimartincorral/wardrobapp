package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * An outfit as the editor hands it over to be stored: its name already decided,
 * its garments in the order they will be shown.
 *
 * What a name falls back to and what order the garments go in are decided in
 * OutfitEditState and tested there; the source only stores the answer.
 */
@Serializable
data class OutfitDraft(
    val name: String,
    val garmentIds: List<String>,
    val occasion: Occasion?,
    val season: Season?,
)

/** Where the outfit editor's garments come from, and where its outfit goes. */
interface OutfitEditSource {
    /**
     * The garments that can go in an outfit: the available ones.
     *
     * An outfit is something to wear, and offering a retired garment would be
     * offering to build one out of clothes that are gone.
     */
    suspend fun wardrobe(): List<GarmentRecord>

    /** Null when there is no such outfit -- deleted, or a link to nothing. */
    suspend fun outfit(id: String): OutfitRecord?

    /**
     * Store a new outfit, built by hand -- so not the engine's idea, which is
     * what the statistics count when they count suggestions.
     */
    suspend fun create(draft: OutfitDraft)

    suspend fun update(id: String, draft: OutfitDraft)
}

/**
 * Building or editing an outfit: what OutfitEditViewModel did, in common code.
 * See ScreenModels.kt.
 */
class OutfitEditScreenModel(
    private val scope: CoroutineScope,
    private val source: OutfitEditSource,
    /** Null when building a new outfit. */
    private val outfitId: String?,
) {
    val isEditing = outfitId != null

    private val _state = MutableStateFlow(OutfitEditScreenState())
    val state: StateFlow<OutfitEditScreenState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null, errorFallback = null) }

        scope.launch {
            attempt {
                val garments = source.wardrobe()
                garments to outfitId?.let { source.outfit(it) }
            }
                .onSuccess { (garments, outfit) ->
                    if (outfitId != null && outfit == null) {
                        _state.update { it.copy(loading = false, missing = true) }
                        return@onSuccess
                    }
                    _state.update { state ->
                        state.copy(
                            loading = false,
                            garments = garments,
                            edit = outfit?.let {
                                outfitEditStateOf(
                                    name = it.name,
                                    garmentIds = it.garmentIds,
                                    occasion = it.occasion,
                                    season = it.season,
                                )
                            } ?: state.edit,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message, errorFallback = ErrorFallback.WARDROBE_UNREADABLE)
                    }
                }
        }
    }

    fun onNameChanged(name: String) = edit { it.withName(name) }

    fun onSearchChanged(search: String) = _state.update { it.copy(search = search) }

    fun onGarmentToggled(garmentId: String) = edit { it.withGarmentToggled(garmentId) }

    fun onOccasionTapped(occasion: Occasion) = edit { it.withOccasion(occasion) }

    fun onSeasonTapped(season: Season) = edit { it.withSeason(season) }

    /**
     * Store the outfit.
     *
     * An outfit with nothing in it is not saved rather than saved empty: an empty
     * outfit is a row nothing can draw and nothing can suggest from.
     */
    fun onSaveRequested() {
        val state = _state.value
        if (state.saving || !state.edit.canSave) return

        _state.update { it.copy(saving = true, error = null, errorFallback = null) }

        val draft = OutfitDraft(
            name = state.edit.nameFor(state.garments),
            garmentIds = state.edit.garmentIds,
            occasion = state.edit.occasion,
            season = state.edit.season,
        )

        scope.launch {
            attempt {
                if (outfitId == null) source.create(draft) else source.update(outfitId, draft)
            }
                .onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(saving = false, error = e.message, errorFallback = ErrorFallback.OUTFIT_NOT_SAVED)
                    }
                }
        }
    }

    fun onErrorDismissed() = _state.update { it.copy(error = null, errorFallback = null) }

    private fun edit(transform: (OutfitEditState) -> OutfitEditState) =
        _state.update { it.copy(edit = transform(it.edit)) }
}
