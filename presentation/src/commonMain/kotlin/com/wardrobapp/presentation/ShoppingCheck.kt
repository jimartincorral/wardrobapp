package com.wardrobapp.presentation

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.SAME_COLOR_DELTA_E
import com.wardrobapp.domain.colorDistance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Before you buy: a photo of something in a shop, and what the wardrobe says
 * about it.
 *
 * Two questions, both ones the app could already answer about a garment it
 * had, and had no way to be asked about one it did not: do I have this
 * already, and what would I wear it with? The photo is read the way the add
 * form reads one (PhotoWork.read: Gemini Nano, else the style model), which
 * turns it into a garment that does not exist -- a PhantomGarment, the gap
 * analysis's word for exactly that -- and from there it is the duplicate rule
 * and the suggestion engine, unchanged.
 *
 * Nothing is saved. The photo is stored only so it can be read upright and
 * drawn, and is deleted when the check is done with; if the reader buys the
 * thing, the add form is one tap away, already filled in.
 */

/** What a shopping check needs from the wardrobe. */
interface ShoppingCheckSource {
    /** The garments in use; a retired one is not something the reader has. */
    suspend fun wardrobe(): List<GarmentRecord>

    /** Outfits around [wanted] from what is owned, [wanted] itself left out of each. */
    suspend fun outfitsWith(wanted: PhantomGarment): List<SuggestedOutfit>
}

/** How many outfits a check shows: as many as the suggestions screen does. */
const val SHOPPING_CHECK_OUTFITS = 3

/**
 * What the wardrobe already has like [wanted]: the same kind in the same
 * colour first, then the same kind in any other.
 *
 * The same rule the gap analysis uses to decide something is already owned
 * (alreadyOwned in :domain) -- same type, and a primary colour within
 * [SAME_COLOR_DELTA_E] -- rather than the duplicate sweep's, which compares
 * whole palettes: a shop photo's palette is the shop's lighting and the rail
 * behind it as much as the garment, and only its main colour can be trusted.
 * Where the reading named a category and no type, any garment in the category
 * is the same kind. Where no colour was read, nothing is claimed to be the
 * same colour, and everything of the kind is in the second list.
 */
fun ownedLike(wanted: PhantomGarment, garments: List<GarmentRecord>): Pair<List<GarmentRecord>, List<GarmentRecord>> {
    val ofKind = garments.filter { garment ->
        garment.isAvailable &&
            garment.category == wanted.category &&
            (wanted.subcategory == null || wanted.subcategory in garment.effectiveSubcategories)
    }
    return ofKind.partition { garment ->
        wanted.colorPrimary.isNotBlank() &&
            (colorDistance(garment.colorPrimary, wanted.colorPrimary) ?: Double.MAX_VALUE) < SAME_COLOR_DELTA_E
    }
}

data class ShoppingCheckState(
    /** The stored photo being checked; null before one is picked. */
    val photo: String? = null,
    /** True while the photo is stored, read and checked: seconds, with Gemini Nano. */
    val checking: Boolean = false,
    /** What the photo was read as; null until it has been, or when it could not be. */
    val wanted: PhantomGarment? = null,
    /** The same kind in the same colour. */
    val same: List<GarmentRecord> = emptyList(),
    /** The same kind in other colours, or in any, when no colour was read. */
    val otherColours: List<GarmentRecord> = emptyList(),
    /** Outfits it would be worn in, each holding only the garments already owned. */
    val outfits: List<SuggestedOutfit> = emptyList(),
    /** The photo was read and nothing could tell what it shows. */
    val unread: Boolean = false,
    val error: String? = null,
)

/**
 * One shopping check at a time: a photo in, the wardrobe's answer out, and
 * the photo thrown away when another is checked or the screen is left
 * ([onLeaving]).
 */
class ShoppingCheckScreenModel<Picked>(
    private val scope: CoroutineScope,
    private val photos: PhotoWork<Picked>,
    private val source: ShoppingCheckSource,
) {
    private val _state = MutableStateFlow(ShoppingCheckState())
    val state: StateFlow<ShoppingCheckState> = _state.asStateFlow()

    /** A photo was picked or taken: store it, read it, and check it against the wardrobe. */
    fun onPhotoPicked(picked: Picked) {
        if (_state.value.checking) return
        val previous = _state.value.photo
        _state.value = ShoppingCheckState(checking = true)
        scope.launch {
            previous?.let { attempt { photos.delete(it) } }
            attempt {
                val photo = photos.store(picked)
                _state.update { it.copy(photo = photo) }
                // Both at once: Gemini Nano takes seconds and the colours do
                // not wait on it.
                val (reading, colours) = coroutineScope {
                    val reading = async { attempt { photos.read(photo) }.getOrNull() }
                    val colours = async { attempt { photos.colors(photo) }.getOrNull() }
                    reading.await() to colours.await()
                }
                val type = reading?.type ?: return@attempt null
                val wanted = PhantomGarment(type.category, type.subcategory, colours?.firstOrNull().orEmpty())
                val (same, others) = ownedLike(wanted, source.wardrobe())
                Checked(wanted, same, others, source.outfitsWith(wanted).take(SHOPPING_CHECK_OUTFITS))
            }
                .onSuccess { checked ->
                    _state.update { state ->
                        if (checked == null) {
                            state.copy(checking = false, unread = true)
                        } else {
                            state.copy(
                                checking = false,
                                wanted = checked.wanted,
                                same = checked.same,
                                otherColours = checked.others,
                                outfits = checked.outfits,
                            )
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(checking = false, error = e.readableMessage()) } }
        }
    }

    private data class Checked(
        val wanted: PhantomGarment,
        val same: List<GarmentRecord>,
        val others: List<GarmentRecord>,
        val outfits: List<SuggestedOutfit>,
    )

    /**
     * The screen is going: the photo goes with it. Its own scope, from the
     * caller, since the screen's is being cancelled as this is asked.
     */
    fun onLeaving(scope: CoroutineScope) {
        val photo = _state.value.photo ?: return
        scope.launch { attempt { photos.delete(photo) } }
    }
}
