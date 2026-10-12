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
import com.wardrobapp.presentation.LookSource
import com.wardrobapp.presentation.WORN_LOOK_ID
import com.wardrobapp.presentation.WornPhotoSource
import com.wardrobapp.presentation.parseLookPieces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Room for eight short "type | colour" lines, which is all a look is read as. */
private const val LOOK_TOKENS = 160

/**
 * One saved look, taken apart on the phone: the shared screen model over the
 * phone's database, with Gemini Nano reading the look's photo. Opened on any
 * phone; one where Gemini Nano is not ready says so on the screen, with the
 * download AICore offers, rather than leaving the look untappable.
 *
 * With [wornPhoto], the same over a photo of what somebody is wearing rather
 * than a saved look (WornPhotoSource): stored only so it could be read
 * upright, and deleted once the screen is done with it, since the outfit it
 * is saved as is what is worth keeping. A photo left behind by a process that
 * died first is the unreferenced-photo sweep's.
 */
class LookViewModel(
    private val container: AppContainer,
    lookId: String,
    private val wornPhoto: String? = null,
) : ViewModel() {

    private val saved = DatabaseLookSource(
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

    private val source: LookSource = wornPhoto?.let { WornPhotoSource(saved, it) } ?: saved

    private val model = LookScreenModel(
        scope = viewModelScope,
        source = source,
        lookId = if (wornPhoto != null) WORN_LOOK_ID else lookId,
    )

    val state: StateFlow<LookScreenState> = model.state

    fun onRetry() = model.onRetry()
    fun onSaveRequested() = model.onSaveRequested()
    fun onSavedOutfitOpened() = model.onSavedOutfitOpened()
    fun onDownloadReader() = model.onDownloadReader()

    override fun onCleared() {
        wornPhoto?.let { photo -> container.appScope.launch(Dispatchers.IO) { container.photos.delete(photo) } }
    }

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

