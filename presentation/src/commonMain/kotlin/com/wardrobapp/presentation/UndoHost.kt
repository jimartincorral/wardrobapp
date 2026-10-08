package com.wardrobapp.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What was deleted, for the line that offers to undo it. */
enum class Deleted { GARMENT, OUTFIT }

/**
 * A delete that can still be undone: what it was, and the two things that
 * can happen to it -- undone, or let go of once nobody has asked.
 */
class UndoOffer internal constructor(
    val deleted: Deleted,
    internal val undo: suspend () -> Boolean,
    internal val expire: suspend () -> Unit,
)

/**
 * The app's one undo line, and who decides when it is over.
 *
 * Above the screens rather than in them, because the screen that deleted
 * something is usually gone by the time Undo is tapped: a garment's screen
 * closes on delete and the wardrobe is what is showing. One host for the
 * app, drawn by its shell beside whichever screen is up, and any screen
 * model can hand it a delete to offer.
 *
 * The host owns the clock. An offer stands for [windowMillis], then its
 * `expire` runs -- which is when the photos a delete kept are deleted for
 * good -- and the line goes away. Undo cancels that and runs `undo`; a
 * second offer arriving while one stands expires the first, since there is
 * one line and it says one thing. Undo is also what [restored] counts, for
 * screens to read their lists again: a garment that came back is a change
 * to the wardrobe made by nobody's screen, like one a sync brings.
 *
 * Nothing here is told about failures beyond the boolean: an undo that
 * found nothing to undo -- the process restarted, the entry evicted -- is
 * reported by the line going away without a restoration, which the screen
 * cannot distinguish from an expiry, and need not: the garment is gone
 * either way, and a restore is the remaining road.
 */
class UndoHost(
    private val scope: CoroutineScope,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private val _offer = MutableStateFlow<UndoOffer?>(null)
    val offer: StateFlow<UndoOffer?> = _offer.asStateFlow()

    private val _restored = MutableStateFlow(0)
    /** How many deletes have been undone; bumped after each, for screens to refresh on. */
    val restored: StateFlow<Int> = _restored.asStateFlow()

    private var timer: Job? = null

    /** Offer to undo a delete that has just happened. */
    fun offer(deleted: Deleted, undo: suspend () -> Boolean, expire: suspend () -> Unit = {}) {
        val previous = replace(UndoOffer(deleted, undo, expire))
        timer = scope.launch {
            previous?.let { attempt { it.expire() } }
            delay(windowMillis)
            // Only this offer, if it is still the one standing: a tap on Undo
            // in the meantime replaced it with nothing, and a newer offer
            // has a timer of its own.
            val current = _offer.value
            if (current != null && _offer.compareAndSet(current, null)) attempt { current.expire() }
        }
    }

    /** Undo the delete on offer. */
    fun onUndo() {
        val current = replace(null) ?: return
        scope.launch {
            val restored = attempt { current.undo() }.getOrDefault(false)
            if (restored) _restored.update { it + 1 }
        }
    }

    /** The line was swiped away: the delete stands, and what it kept is let go of now. */
    fun onDismissed() {
        val current = replace(null) ?: return
        scope.launch { attempt { current.expire() } }
    }

    private fun replace(next: UndoOffer?): UndoOffer? {
        timer?.cancel()
        timer = null
        val previous = _offer.value
        _offer.value = next
        return previous
    }

    companion object {
        /** Long enough to read the line and reach it; short enough that a delete is a delete. */
        const val WINDOW_MILLIS = 6_000L
    }
}
