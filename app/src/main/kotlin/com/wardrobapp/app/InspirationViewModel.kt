package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.presentation.DatabaseInspirationSource
import com.wardrobapp.presentation.InspirationScreenModel
import com.wardrobapp.presentation.InspirationScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The looks a reader likes, on the phone: the same screen model the browser
 * runs, over the phone's own database and photo directory.
 *
 * The phone has no style model, so a look saved here is only a photo and a
 * row until the next sync, when Home Assistant embeds it and its vector comes
 * back. Which is why adding or removing one asks for a sync: the point of a
 * look is to move the suggestions, and the sooner the server sees it the
 * sooner they move. A phone that is not paired keeps the looks all the same
 * -- they will go the day it pairs -- and PhoneSync declines the sync itself,
 * so nothing here needs to know.
 */
class InspirationViewModel(private val container: AppContainer) : ViewModel() {

    private val model = InspirationScreenModel(
        scope = viewModelScope,
        source = DatabaseInspirationSource(
            style = StyleQueries(container.database),
            imageDirectory = container.imageDirectory,
            deletePhoto = container.photos::delete,
            io = Dispatchers.IO,
        ),
    )

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
