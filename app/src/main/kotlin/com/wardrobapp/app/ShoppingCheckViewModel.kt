package com.wardrobapp.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.DatabaseShoppingCheckSource
import com.wardrobapp.presentation.ShoppingCheckScreenModel
import com.wardrobapp.presentation.ShoppingCheckState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * Before you buy, on the phone: the shared screen model over the phone's
 * photo work -- Gemini Nano where it is, the style model where it is not --
 * and its database. The photo is the screen's only, and goes when it does:
 * in the app's scope, since this one is being cancelled as it is cleared.
 */
class ShoppingCheckViewModel(private val container: AppContainer) : ViewModel() {

    private val model = ShoppingCheckScreenModel(
        scope = viewModelScope,
        photos = PhonePhotoWork(container),
        source = DatabaseShoppingCheckSource(
            garments = container.garments,
            suggestions = container.suggestions,
            io = Dispatchers.IO,
        ),
    )

    val state: StateFlow<ShoppingCheckState> = model.state

    fun onPhotoPicked(photo: Uri) = model.onPhotoPicked(photo)

    override fun onCleared() = model.onLeaving(container.appScope)
}
