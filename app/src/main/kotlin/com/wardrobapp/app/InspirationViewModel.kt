package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.InspirationRecord
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.presentation.DatabaseInspirationSource
import com.wardrobapp.presentation.InspirationScreenModel
import com.wardrobapp.presentation.InspirationScreenState
import com.wardrobapp.presentation.InspirationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The looks a reader likes, on the phone: the same screen model the browser
 * runs, over the phone's own database and photo directory.
 *
 * The phone embeds a look itself, with the model bundled in the app (see
 * PhoneStyleIndex), so a look moves the phone's suggestions whether or not it
 * is paired with Home Assistant. Adding or removing one also asks for a sync,
 * so a paired Home Assistant has it too; PhoneSync declines that sync on a
 * phone that is not paired, so nothing here needs to know.
 */
class InspirationViewModel(private val container: AppContainer) : ViewModel() {

    private val stored = DatabaseInspirationSource(
        style = StyleQueries(container.database),
        imageDirectory = container.imageDirectory,
        deletePhoto = container.photos::delete,
        io = Dispatchers.IO,
    )

    /**
     * The phone's looks, with one thing added: a look is embedded as soon as
     * its row exists. After the add rather than on the tap, since the pass
     * looks for rows and a pass that ran first would find none.
     */
    private val source = object : InspirationSource by stored {
        override suspend fun add(photo: String): InspirationRecord =
            stored.add(photo).also { container.styleIndex.refresh() }
    }

    private val model = InspirationScreenModel(scope = viewModelScope, source = source)

    val state: StateFlow<InspirationScreenState> = model.state

    fun refresh() = model.refresh()
    fun onAddStarted() = model.onAddStarted()
    fun onAddAbandoned(error: String? = null) = model.onAddAbandoned(error)
    fun onErrorDismissed() = model.onErrorDismissed()

    fun onPhotoStored(photo: String) {
        model.onPhotoStored(photo)
        syncSoon()
    }

    fun onDeleteRequested(id: String) {
        model.onDeleteRequested(id)
        syncSoon()
    }

    /**
     * In the app's scope rather than this screen's, so leaving the screen a
     * second after adding a look does not cancel the sync that carries it.
     */
    private fun syncSoon() {
        container.appScope.launch { container.sync.sync() }
    }
}
