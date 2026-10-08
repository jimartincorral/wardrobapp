package com.wardrobapp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wardrobapp.data.AppRelease
import com.wardrobapp.data.ReleaseNote
import com.wardrobapp.data.updateWorthOffering
import com.wardrobapp.presentation.WhatsNewDecision
import com.wardrobapp.presentation.whatsNewDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offering a newer build, once per launch -- and telling somebody what the
 * build they are running changed, once per build.
 *
 * The check runs when this model is created, which is once per process: a notice
 * about a build published while the app was open would interrupt somebody in the
 * middle of something, and the next launch is soon enough for a wardrobe app.
 *
 * Everything worth deciding is decided elsewhere -- :data compares the version
 * codes and remembers nothing, [SkippedUpdate] remembers the declined build,
 * [AndroidAppUpdates] does the network and the installer. What is left here is the
 * order those happen in and what the screen is told.
 *
 * What's new rides on the same request, because it reads the same document:
 * the history the update check fetches carries every recent note with its
 * build, including the ones for the build now running. :presentation's
 * whatsNewDecision decides; [WhatsNewRecord] remembers.
 */
class UpdateViewModel(
    private val updates: AndroidAppUpdates,
    private val skipped: SkippedUpdate,
    private val installedVersionCode: Long,
    private val whatsNewRecord: WhatsNewRecord,
    /** Whether this installation has never been updated: nothing for What's new to be new since. */
    private val freshInstall: Boolean,
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
        /**
         * What the running build changed, to show before anything else; null
         * when there is nothing to show. Shown first because it is about what
         * somebody already has, and an offer of the next build can wait for it.
         */
        val whatsNew: List<ReleaseNote>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                updates.discardInstalled(installedVersionCode)
                updates.latestRelease()
            }

            val whatsNew = when (
                val decision = whatsNewDecision(
                    installed = installedVersionCode,
                    lastSeen = whatsNewRecord.lastSeenBuild,
                    freshInstall = freshInstall,
                    release = latest,
                )
            ) {
                is WhatsNewDecision.Show -> decision.notes
                WhatsNewDecision.NothingNew -> {
                    whatsNewRecord.lastSeenBuild = installedVersionCode
                    null
                }
                // Not recorded: the notes exist, they just could not be read
                // this time. The next launch asks again.
                WhatsNewDecision.NotYet -> null
            }

            val offer = updateWorthOffering(
                installed = installedVersionCode,
                skipped = skipped.versionCode,
                release = latest,
            )

            _state.update { it.copy(available = offer, whatsNew = whatsNew) }
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

    /**
     * What's new has been read, or set aside, or left by following one of its
     * links: all three are seen, and it is not shown again for this build.
     */
    fun onWhatsNewSeen() {
        whatsNewRecord.lastSeenBuild = installedVersionCode
        _state.update { it.copy(whatsNew = null) }
    }

    /** Put the notice back the way it was before a failed download. */
    fun onFailureDismissed() = _state.update { it.copy(failure = null) }
}
