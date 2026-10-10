package com.wardrobapp.web

import androidx.compose.runtime.mutableStateListOf
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.ui.HOME
import com.wardrobapp.ui.OUTFITS
import com.wardrobapp.ui.SETTINGS
import com.wardrobapp.ui.STATISTICS
import com.wardrobapp.ui.WARDROBE
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Somewhere the app can be. */
sealed interface Destination {
    /** One of the bottom bar's tabs, by the route :ui's bar knows it by. */
    sealed class Tab(val route: String) : Destination

    data object Home : Tab(HOME)
    data object Wardrobe : Tab(WARDROBE)
    data object Outfits : Tab(OUTFITS)
    data object Statistics : Tab(STATISTICS)
    data object Settings : Tab(SETTINGS)

    data class Garment(val id: String) : Destination
    data class GarmentAdd(val wanted: PhantomGarment? = null) : Destination
    data class GarmentEdit(val id: String) : Destination
    data object BulkAdd : Destination
    data class Outfit(val id: String) : Destination
    data object OutfitBuild : Destination
    /** A training session: ten outfit ideas rated in a row. */
    data object TasteTraining : Destination
    /** The looks the reader likes; see InspirationScreen. */
    data object Inspiration : Destination
    data class OutfitEdit(val id: String) : Destination
}

/**
 * One visit to a destination: the screen's model, and the scope it runs in.
 *
 * What a NavBackStackEntry and its ViewModelStore are on the phone. The model
 * is made once per entry and kept while the entry is on the stack, so coming
 * back to a screen finds it as it was left -- the list scrolled, the filters
 * set -- and the scope is cancelled when the entry leaves the stack for good,
 * which stops whatever its model was still doing.
 */
class Entry(val destination: Destination) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether the screen has been on top before, so a return can be told from an arrival. */
    var shown: Boolean = false

    /** What its screen re-reads when it is returned to, set by the screen with its model. */
    var onReturn: (() -> Unit)? = null

    private var model: Any? = null

    /** Whatever else the screen keeps for as long as the entry lives; see [keep]. */
    private val kept = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <M : Any> model(create: (CoroutineScope) -> M): M = (model ?: create(scope).also { model = it }) as M

    /**
     * Something besides the model that has to outlive the composition -- the
     * garment open in the desktop's pane, and that garment's own model.
     *
     * Here rather than in `remember`, because the composition does not live as
     * long as the entry: switching tabs disposes it, and so does the window
     * crossing the desktop breakpoint, and neither should close the garment
     * somebody was looking at.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(key: String, create: () -> T): T = kept.getOrPut(key, create) as T

    fun close() = scope.cancel()
}

/**
 * The back stack, and the browser's back button.
 *
 * Shaped like the phone's: Home at the bottom, at most one other tab above it,
 * and whatever was opened from there above that. Switching tabs does not stack
 * them, and a tab left and come back to is the same entry, as `saveState` and
 * `restoreState` make it on the phone.
 *
 * Every screen opened on top of a tab is also an entry in the browser's
 * history, so the browser's back button closes it the way Android's back
 * gesture does, rather than leaving the app -- which inside Home Assistant
 * would mean leaving the panel. The history entries carry nothing: the stack
 * here is the truth, and history is only how the browser asks for a step back.
 */
class Navigator {
    val stack = mutableStateListOf(Entry(Destination.Home))

    /** Tabs left for another, kept to be returned to. */
    private val savedTabs = mutableMapOf<Destination.Tab, Entry>()

    /** How many entries above the tab have a history entry of their own. */
    private val opened: Int get() = stack.size - stack.indexOfLast { it.destination is Destination.Tab } - 1

    /** Set while the history is being unwound by [switchTo], whose own pop event is not a back. */
    private var unwinding = false

    val current: Entry get() = stack.last()

    /**
     * The tab the screen on top was opened from: the desktop rail's selection,
     * which goes on saying "Wardrobe" while a garment's form is open over it.
     * There is always one, since Home is never taken off the bottom.
     */
    val tab: Destination.Tab get() = stack.last { it.destination is Destination.Tab }.destination as Destination.Tab

    init {
        window.addEventListener("popstate") {
            if (unwinding) {
                unwinding = false
            } else if (opened > 0) {
                stack.removeAt(stack.lastIndex).close()
            }
        }
    }

    /** Open [destination] on top of whatever is showing. */
    fun open(destination: Destination) {
        stack.add(Entry(destination))
        window.history.pushState(null, "")
    }

    /**
     * Close the screen on top, as its back arrow asks. Through the browser's
     * history rather than directly, so the arrow and the browser's back button
     * are one step taken one way, and the history never holds an entry for a
     * screen that is gone.
     */
    fun back() {
        if (opened > 0) window.history.back()
    }

    /** Go to a tab, closing everything opened on top of the one showing. */
    fun switchTo(tab: Destination.Tab) {
        val unwound = opened
        if (unwound > 0) {
            unwinding = true
            window.history.go(-unwound)
        }
        repeat(unwound) { stack.removeAt(stack.lastIndex).close() }

        val showing = stack.last()
        if (showing.destination == tab) return

        // Home stays at the bottom; the tab above it, if any, is put aside
        // rather than closed, to be found as it was left.
        if (showing.destination != Destination.Home) {
            savedTabs[showing.destination as Destination.Tab] = stack.removeAt(stack.lastIndex)
        }
        if (tab != Destination.Home) {
            stack.add(savedTabs.remove(tab) ?: Entry(tab))
        }
    }
}

/**
 * A model for whichever one thing a pane is showing, replaced when that changes.
 *
 * The desktop wardrobe shows one garment at a time beside its grid, and picking
 * another means a different [com.wardrobapp.presentation.GarmentDetailScreenModel]:
 * they are made for one garment each. Each gets a scope of its own under the
 * entry's, cancelled when the next one replaces it, so a slow read of the garment
 * that was open cannot land after somebody has moved on to the next one; and the
 * entry's own scope still ends them all when the wardrobe leaves the stack.
 */
class PaneModel<M : Any>(private val parent: CoroutineScope) {
    private var key: Any? = null
    private var scope: CoroutineScope? = null

    /** The model showing now, if any: refreshed with the screen when it is returned to. */
    var current: M? = null
        private set

    fun modelFor(key: Any, create: (CoroutineScope) -> M): M {
        current?.takeIf { this.key == key }?.let { return it }

        scope?.cancel()
        val child = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        return create(child).also {
            this.key = key
            scope = child
            current = it
        }
    }
}
