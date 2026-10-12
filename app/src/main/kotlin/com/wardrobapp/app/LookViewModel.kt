package com.wardrobapp.app

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.InspirationRecord
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.presentation.DatabaseLookSource
import com.wardrobapp.presentation.LOOK_PIECES_PROMPT
import com.wardrobapp.presentation.LookPiece
import com.wardrobapp.presentation.LookReader
import com.wardrobapp.presentation.LookScreenModel
import com.wardrobapp.presentation.LookScreenState
import com.wardrobapp.presentation.parseLookPieces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Room for eight short "type | colour" lines, which is all a look is read as. */
private const val LOOK_TOKENS = 160

/**
 * One saved look, taken apart on the phone: the shared screen model over the
 * phone's database, with Gemini Nano reading the look's photo. Opened on any
 * phone; one where Gemini Nano is not ready says so on the screen, with the
 * download AICore offers, rather than leaving the look untappable.
 */
class LookViewModel(private val container: AppContainer, lookId: String) : ViewModel() {

    private val source = DatabaseLookSource(
        style = StyleQueries(container.database),
        garments = container.garments,
        outfitWrites = container.outfitWrites,
        imageDirectory = container.imageDirectory,
        readLook = ::read,
        readerStatus = { container.geminiNano.status().asReader() },
        // In the app's scope, so leaving the screen does not abandon a download
        // AICore is in the middle of; this screen only waits for it.
        fetchReader = { container.appScope.async { container.geminiNano.download() }.await().asReader() },
        io = Dispatchers.IO,
    )

    private val model = LookScreenModel(scope = viewModelScope, source = source, lookId = lookId)

    val state: StateFlow<LookScreenState> = model.state

    fun onRetry() = model.onRetry()
    fun onSaveRequested() = model.onSaveRequested()
    fun onSavedOutfitOpened() = model.onSavedOutfitOpened()
    fun onDownloadReader() = model.onDownloadReader()

    private suspend fun read(look: InspirationRecord): List<LookPiece>? = withContext(Dispatchers.IO) {
        val picture = container.photos.bitmapFor(look.imageUri.toUri())?.let(::pictureForNano) ?: return@withContext null
        container.geminiNano.ask(picture, LOOK_PIECES_PROMPT, LOOK_TOKENS)?.let(::parseLookPieces)
    }
}

private fun GeminiNano.Status.asReader(): LookReader = when (this) {
    GeminiNano.Status.AVAILABLE -> LookReader.READY
    GeminiNano.Status.DOWNLOADABLE -> LookReader.DOWNLOADABLE
    GeminiNano.Status.DOWNLOADING -> LookReader.DOWNLOADING
    GeminiNano.Status.UNAVAILABLE -> LookReader.UNAVAILABLE
}

