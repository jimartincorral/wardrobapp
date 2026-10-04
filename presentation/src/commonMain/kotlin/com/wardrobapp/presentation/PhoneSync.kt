package com.wardrobapp.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The phone's side of syncing with Home Assistant, as Settings shows it.
 *
 * What a sync does to the two wardrobes is :data's (WardrobeSync.kt), and how
 * the phone talks to the server is :api's (PhoneSync); this is what the screen
 * knows: whether the phone is paired, how the last sync went, and the switches.
 * In common code so it is tested where everything else is, without a phone.
 */

/** Why a sync, or the check that comes before pairing, did not go through. */
sealed interface SyncFailure {
    /**
     * The server answered and turned the code down: mistyped, or replaced by
     * "Make a new code" since.
     */
    data object NotPaired : SyncFailure

    /**
     * Nothing answered at that address, or what answered is not Wardrobapp's
     * sync port -- Home Assistant's own port, say, which is the likeliest
     * mistake, and which answers in a way that means nothing here.
     */
    data object Unreachable : SyncFailure

    /**
     * The phone restored a backup, which replaces the wardrobe in Home
     * Assistant too, and the app there is too old to be asked to. Until it is
     * updated nothing syncs: an ordinary sync would undo the restore.
     */
    data object ServerTooOld : SyncFailure

    /** Anything else, in the words of whatever failed. */
    data class Other(val message: String) : SyncFailure
}

/**
 * [failure] written down, to survive until somebody opens Settings: the sync
 * that failed may have run in the background, hours before.
 */
fun storedSyncFailure(failure: SyncFailure): String = when (failure) {
    SyncFailure.NotPaired -> STORED_NOT_PAIRED
    SyncFailure.Unreachable -> STORED_UNREACHABLE
    SyncFailure.ServerTooOld -> STORED_SERVER_TOO_OLD
    is SyncFailure.Other -> STORED_OTHER + failure.message
}

/** The failure [stored] by [storedSyncFailure], or null for nothing stored. */
fun syncFailureFor(stored: String?): SyncFailure? = when {
    stored == null -> null
    stored == STORED_NOT_PAIRED -> SyncFailure.NotPaired
    stored == STORED_UNREACHABLE -> SyncFailure.Unreachable
    stored == STORED_SERVER_TOO_OLD -> SyncFailure.ServerTooOld
    stored.startsWith(STORED_OTHER) -> SyncFailure.Other(stored.removePrefix(STORED_OTHER))
    // Written by some later build, in a form this one does not know. Still a
    // failure, rather than read as success because it could not be read.
    else -> SyncFailure.Other(stored)
}

private const val STORED_NOT_PAIRED = "not-paired"
private const val STORED_UNREACHABLE = "unreachable"
private const val STORED_SERVER_TOO_OLD = "server-too-old"
private const val STORED_OTHER = "other:"

/** The port the server listens for phones on, unless Home Assistant maps it elsewhere. */
const val DEFAULT_SYNC_PORT = 8100

/**
 * What somebody typed as Home Assistant's address, as the address the phone
 * will sync with -- `http://host:port/` -- or null if it is not one.
 *
 * Forgiving of what people type, because they type it on a phone keyboard,
 * reading it off another screen:
 *
 *  - No scheme is http. Home Assistant on a home network is http unless
 *    somebody has gone out of their way, and whoever did will type `https://`.
 *  - No port is the sync port, for http: the address people know is the one
 *    they open Home Assistant at, and the port it is reached on there (8123)
 *    is never the right one, so the likeliest port is the one the sync port
 *    is mapped to by default. Not for https, where no port means 443 -- a
 *    reverse proxy, put there by somebody who knows which port they meant.
 *  - A path is dropped, so an address pasted from the browser's bar, ingress
 *    path and all, still finds the host.
 *
 * Only http and https, and only a host with no user information: an address
 * with `user@` in it is either a mistake or somebody else's idea.
 */
fun syncAddressOf(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

    val match = SCHEME.find(trimmed)
    val scheme = match?.groupValues?.get(1)?.lowercase() ?: "http"
    if (scheme != "http" && scheme != "https") return null

    val authority = trimmed.removeRange(0, match?.value?.length ?: 0).takeWhile { it !in "/?#" }
    if (authority.isEmpty() || '@' in authority) return null

    val (host, port) = if (authority.startsWith('[')) {
        // An IPv6 literal, whose colons are not a port.
        val close = authority.indexOf(']')
        if (close < 0) return null
        authority.substring(0, close + 1) to authority.substring(close + 1).removePrefix(":").ifEmpty { null }
    } else {
        if (authority.count { it == ':' } > 1) return null
        authority.substringBefore(':') to authority.substringAfter(':', "").ifEmpty { null }
    }
    if (host.isEmpty() || host == "[]" || !HOST.matches(host)) return null

    val portNumber = when {
        port != null -> port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        scheme == "http" -> DEFAULT_SYNC_PORT
        else -> null
    }

    return "$scheme://${host.lowercase()}${portNumber?.let { ":$it" } ?: ""}/"
}

private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://""")

/** A name, an IPv4 address, or a bracketed IPv6 one; nothing a URL would read as something else. */
private val HOST = Regex("""^(\[[0-9A-Fa-f:.]+]|[A-Za-z0-9]([A-Za-z0-9\-.]*[A-Za-z0-9])?)$""")

/** How the phone stands with Home Assistant, as the side that does the syncing knows it. */
data class PhoneSyncStatus(
    /** Where the phone syncs with, as [syncAddressOf] wrote it; null while it is not paired. */
    val address: String? = null,
    /** When a sync last went through, as a stored timestamp; null until one has. */
    val lastSyncedAt: String? = null,
    /**
     * Why the last sync failed, or null if it went through. A sync that failed
     * after one that worked leaves [lastSyncedAt] where it was, so both show:
     * when the phone was last in step, and why it is not now.
     */
    val lastFailure: SyncFailure? = null,
    /** Whether a sync is running now, from Settings, on opening the app or in the background. */
    val syncing: Boolean = false,
    /** Whether the phone syncs on its own while nobody has the app open. */
    val background: Boolean = true,
    /** Whether syncing on its own waits for Wi-Fi. */
    val wifiOnly: Boolean = true,
    /**
     * A backup was restored here and has not reached Home Assistant yet: the
     * next sync replaces the wardrobe there rather than merging with it.
     */
    val restorePending: Boolean = false,
) {
    val paired: Boolean get() = address != null
}

/**
 * Where Settings' sync section gets the phone's sync from: the status, kept up
 * to date as syncs start and finish wherever they were started, and what each
 * control does.
 */
interface PhoneSyncSource {
    val status: StateFlow<PhoneSyncStatus>

    /**
     * Check that [address] answers to [code], and pair with it if it does;
     * null on success, or why not, and then nothing is stored.
     */
    suspend fun connect(address: String, code: String): SyncFailure?

    /** Sync now; null on success, or why not. Recorded in [status] either way. */
    suspend fun sync(): SyncFailure?

    /** Forget the address and the code, and stop syncing. The wardrobe stays as it is. */
    fun disconnect()

    fun setBackground(enabled: Boolean)

    fun setWifiOnly(enabled: Boolean)
}

/** Settings' sync section: the phone's status, and the form that pairs it. */
data class PhoneSyncState(
    val status: PhoneSyncStatus = PhoneSyncStatus(),
    /** Checking an address and a code before pairing with them. */
    val connecting: Boolean = false,
    /** What was typed as the address is not one; see [syncAddressOf]. */
    val addressInvalid: Boolean = false,
    /** Why the last attempt to pair did not, until the next attempt. */
    val connectFailure: SyncFailure? = null,
)

/** Settings' sync section, in common code. See ScreenModels.kt. */
class PhoneSyncModel(
    private val scope: CoroutineScope,
    private val source: PhoneSyncSource,
) {
    private val form = MutableStateFlow(PhoneSyncState())

    val state: StateFlow<PhoneSyncState> = combine(source.status, form) { status, form ->
        form.copy(status = status)
    }.stateIn(scope, SharingStarted.Eagerly, PhoneSyncState(status = source.status.value))

    /**
     * Pair with [address] using [code], and sync straight away if that works:
     * somebody who has just typed a code in wants to see their wardrobe arrive,
     * not to find a second button.
     */
    fun onConnect(address: String, code: String) {
        if (form.value.connecting) return
        val normalized = syncAddressOf(address)
        if (normalized == null) {
            form.update { it.copy(addressInvalid = true, connectFailure = null) }
            return
        }
        form.update { it.copy(connecting = true, addressInvalid = false, connectFailure = null) }
        scope.launch {
            val failure = source.connect(normalized, code)
            form.update { it.copy(connecting = false, connectFailure = failure) }
            if (failure == null) source.sync()
        }
    }

    /** Sync now. Nothing if a sync is already running -- it will have the same effect. */
    fun onSyncNow() {
        if (state.value.status.syncing) return
        scope.launch { source.sync() }
    }

    fun onDisconnect() {
        source.disconnect()
        form.value = PhoneSyncState()
    }

    fun onBackgroundChanged(enabled: Boolean) = source.setBackground(enabled)

    fun onWifiOnlyChanged(enabled: Boolean) = source.setWifiOnly(enabled)

    /** Typing again takes back what was said about the last attempt. */
    fun onFormEdited() = form.update { it.copy(addressInvalid = false, connectFailure = null) }
}
