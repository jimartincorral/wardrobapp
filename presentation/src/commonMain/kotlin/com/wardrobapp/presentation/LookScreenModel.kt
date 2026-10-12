package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.InspirationRecord
import com.wardrobapp.domain.garmentLabelFor
import com.wardrobapp.domain.outfitNameFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the look screen needs: the look, what it was read as before, a way to
 * read it now, and the wardrobe to rebuild it from.
 *
 * Reading is the phone's -- Gemini Nano, see PhoneLookSource in :app -- and
 * answers null where it cannot, which the screen says rather than hides.
 */
interface LookSource {
    /** Null when there is no such look: deleted, or a link to nothing. */
    suspend fun look(id: String): InspirationRecord?

    /** What the look was read as before, or null when it never has been. */
    suspend fun storedPieces(id: String): List<LookPiece>?

    /** Read the look's photo now; null when it could not be read at all. */
    suspend fun read(look: InspirationRecord): List<LookPiece>?

    suspend fun keepPieces(id: String, pieces: List<LookPiece>)

    /** Every garment, available or not; [recreateLook] decides which can be worn. */
    suspend fun wardrobe(): List<GarmentRecord>

    /** The look's vector and every garment's, where the style model has made them. */
    suspend fun vectors(id: String): Pair<FloatArray?, Map<String, FloatArray>>

    /** Store an outfit of [garmentIds] called [name]; its id. */
    suspend fun saveOutfit(name: String, garmentIds: List<String>): String
}

data class LookScreenState(
    val look: InspirationRecord? = null,
    val loading: Boolean = true,
    /** True while Gemini Nano reads the photo, which takes seconds. */
    val reading: Boolean = false,
    val rows: List<LookRow> = emptyList(),
    /** The look could not be read, or was read as nothing worn. */
    val unread: Boolean = false,
    /** The look is gone. */
    val missing: Boolean = false,
    val saving: Boolean = false,
    /** Set once the rebuilt outfit is stored, so the screen can open it. */
    val savedOutfitId: String? = null,
    val error: String? = null,
) {
    /** Whether there is enough of the look in the wardrobe to be an outfit: two garments. */
    val canSave: Boolean get() = rows.count { it.garment != null } >= 2 && !saving
}

/**
 * One look, taken apart and put back together from the wardrobe.
 *
 * The look is read once and the pieces kept (LookSource.keepPieces), because
 * reading is seconds of a model's time and the answer does not change; the
 * matching is redone every time the screen opens, because the wardrobe does.
 * Retry reads the photo again, for a look read badly or not at all.
 */
class LookScreenModel(
    private val scope: CoroutineScope,
    private val source: LookSource,
    private val lookId: String,
) {
    private val _state = MutableStateFlow(LookScreenState())
    val state: StateFlow<LookScreenState> = _state.asStateFlow()

    init {
        load(reread = false)
    }

    /** Read the look again, from its photo, rather than from what was kept. */
    fun onRetry() = load(reread = true)

    private fun load(reread: Boolean) {
        _state.update { it.copy(loading = true, unread = false, error = null) }
        scope.launch {
            attempt {
                val look = source.look(lookId) ?: return@attempt null
                _state.update { it.copy(look = look) }
                val kept = if (reread) null else source.storedPieces(lookId)
                val pieces = kept ?: run {
                    _state.update { it.copy(reading = true) }
                    source.read(look)?.also { if (it.isNotEmpty()) source.keepPieces(lookId, it) }
                }
                val (lookVector, garmentVectors) = source.vectors(lookId)
                pieces?.let { recreateLook(it, source.wardrobe(), lookVector, garmentVectors) }
                    .let { rows -> look to rows }
            }
                .onSuccess { found ->
                    _state.update { state ->
                        if (found == null) {
                            state.copy(loading = false, reading = false, missing = true)
                        } else {
                            val rows = found.second.orEmpty()
                            state.copy(loading = false, reading = false, rows = rows, unread = rows.isEmpty())
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, reading = false, error = e.readableMessage()) } }
        }
    }

    /**
     * Store what the wardrobe has of the look as an outfit. Named the way an
     * outfit nobody named is, from its garments (outfitNameFrom), so it reads
     * like every other in the list; renaming is the outfit's own screen's job.
     */
    fun onSaveRequested() {
        val state = _state.value
        if (!state.canSave) return
        val garments = state.rows.mapNotNull { it.garment }
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            attempt {
                val name = outfitNameFrom(garments.map { garmentLabelFor(it.category, it.effectiveSubcategories.firstOrNull()) })
                source.saveOutfit(name, garments.map { it.id })
            }
                .onSuccess { id -> _state.update { it.copy(saving = false, savedOutfitId = id) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.readableMessage()) } }
        }
    }

    /** The screen opened the saved outfit; do not open it again on the way back. */
    fun onSavedOutfitOpened() = _state.update { it.copy(savedOutfitId = null) }
}
