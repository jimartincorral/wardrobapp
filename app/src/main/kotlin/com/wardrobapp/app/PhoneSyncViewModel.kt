package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.presentation.PhoneSyncModel
import com.wardrobapp.presentation.PhoneSyncSource
import com.wardrobapp.presentation.PhoneSyncState
import com.wardrobapp.presentation.SyncFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings' Home Assistant section: PhoneSyncModel, held for as long as the
 * screen is.
 *
 * The syncs it starts are not, though. They run in the container's scope and
 * are only awaited here, so leaving Settings -- to watch the wardrobe fill up,
 * which is the natural thing to do after pressing Sync now -- leaves the sync
 * running rather than cancelling it halfway.
 */
class PhoneSyncViewModel(container: AppContainer) : ViewModel() {

    private val outliving = object : PhoneSyncSource by container.sync {
        override suspend fun sync(): SyncFailure? =
            container.appScope.async { container.sync.sync() }.await()
    }

    private val model = PhoneSyncModel(viewModelScope, outliving)

    val state: StateFlow<PhoneSyncState> = model.state

    fun onConnect(address: String, code: String) = model.onConnect(address, code)
    fun onFormEdited() = model.onFormEdited()
    fun onSyncNow() = model.onSyncNow()
    fun onDisconnect() = model.onDisconnect()
    fun onBackgroundChanged(enabled: Boolean) = model.onBackgroundChanged(enabled)
    fun onWifiOnlyChanged(enabled: Boolean) = model.onWifiOnlyChanged(enabled)
    fun onPairingLinkReceived(text: String?) = model.onPairingLinkReceived(text)
    fun onOfferTaken() = model.onOfferTaken()
    fun onScannerUnavailable() = model.onScannerUnavailable()
}
