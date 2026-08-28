package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.AppRelease
import com.wardrobapp.data.updateWorthOffering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offering a newer build, once per launch.
 *
 * The check runs when this model is created, which is once per process: a notice
 * about a build published while the app was open would interrupt somebody in the
 * middle of something, and the next launch is soon enough for a wardrobe app.
 *
 * Everything worth deciding is decided elsewhere -- :data compares the version
 * codes and remembers nothing, [SkippedUpdate] remembers the declined build,
 * [AndroidAppUpdates] does the network and the installer. What is left here is the
 * order those happen in and what the screen is told.
 */
class UpdateViewModel(
    private val updates: AndroidAppUpdates,
    private val skipped: SkippedUpdate,
    private val installedVersionCode: Long,
) : ViewModel() {

    data class State(
        /** The build worth offering, or null for "nothing to say". */
        val available: AppRelease? = null,
        /** True from the tap on Install until the installer takes over or fails. */
        val downloading: Boolean = false,
        /** 0..1 where the server declared a size, null while it has not. */
        val progress: Float? = null,
        /** What went wrong, in the words of whatever failed. Null when nothing has. */
        val failure: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val offer = withContext(Dispatchers.IO) {
                updateWorthOffering(
                    installed = installedVersionCode,
                    skipped = skipped.versionCode,
                    release = updates.latestRelease(),
                )
            }

            _state.update { it.copy(available = offer) }
        }
    }

    /**
     * Download the offered build and hand it to the installer.
     *
     * The notice stays on screen while this runs, showing progress, because the
     * download is the reason it was tapped. It closes when the installer opens: at
     * that point the system is asking the question and two dialogs about one
     * install is one too many.
     */
    fun onInstallRequested() {
        val release = _state.value.available ?: return
        if (_state.value.downloading) return

        _state.update { it.copy(downloading = true, progress = null, failure = null) }

        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    updates.download(release) { progress ->
                        _state.update { it.copy(progress = progress) }
                    }
                }

                updates.install(apk)
                _state.update { State() }
            } catch (e: Exception) {
                _state.update {
                    it.copy(downloading = false, progress = null, failure = e.message)
                }
            }
        }
    }

    /**
     * Not this build.
     *
     * Remembered across launches, and only for this build: the next one is a new
     * decision. That is [com.wardrobapp.data.updateWorthOffering]'s rule, and this
     * only records the number.
     */
    fun onSkipRequested() {
        val release = _state.value.available ?: return

        skipped.versionCode = release.versionCode
        _state.update { State() }
    }

    /** Not now. Nothing is remembered, so the next launch asks again. */
    fun onDismissed() = _state.update { State() }

    /** Put the notice back the way it was before a failed download. */
    fun onFailureDismissed() = _state.update { it.copy(failure = null) }
}
