package com.wardrobapp.app

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.InspirationRecord
import com.wardrobapp.data.StyleQueries
import com.wardrobapp.presentation.DatabaseLookSource
import com.wardrobapp.presentation.LOOK_PIECES_PROMPT
import com.wardrobapp.presentation.LookPiece
import com.wardrobapp.presentation.LookScreenModel
import com.wardrobapp.presentation.LookScreenState
import com.wardrobapp.presentation.parseLookPieces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Room for eight short "type | colour" lines, which is all a look is read as. */
private const val LOOK_TOKENS = 160

/**
 * One saved look, taken apart on the phone: the shared screen model over the
 * phone's database, with Gemini Nano reading the look's photo. Only opened on
 * a phone that has Gemini Nano (see MainActivity's looks screen), so a look
 * that reads as nothing here is a look the model could not make out, not a
 * phone without one.
 */
class LookViewModel(private val container: AppContainer, lookId: String) : ViewModel() {

    private val source = DatabaseLookSource(
        style = StyleQueries(container.database),
        garments = container.garments,
        outfitWrites = container.outfitWrites,
        imageDirectory = container.imageDirectory,
        readLook = ::read,
        io = Dispatchers.IO,
    )

    private val model = LookScreenModel(scope = viewModelScope, source = source, lookId = lookId)

    val state: StateFlow<LookScreenState> = model.state

    fun onRetry() = model.onRetry()
    fun onSaveRequested() = model.onSaveRequested()
    fun onSavedOutfitOpened() = model.onSavedOutfitOpened()

    private suspend fun read(look: InspirationRecord): List<LookPiece>? = withContext(Dispatchers.IO) {
        val picture = container.photos.bitmapFor(look.imageUri.toUri())?.let(::pictureForNano) ?: return@withContext null
        container.geminiNano.ask(picture, LOOK_PIECES_PROMPT, LOOK_TOKENS)?.let(::parseLookPieces)
    }
}
