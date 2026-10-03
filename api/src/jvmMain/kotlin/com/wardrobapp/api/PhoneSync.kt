package com.wardrobapp.api

import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.presentation.PhoneSyncSource
import com.wardrobapp.presentation.PhoneSyncStatus
import com.wardrobapp.presentation.SyncFailure
import com.wardrobapp.presentation.storedSyncFailure
import com.wardrobapp.presentation.syncFailureFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.ContentConvertException
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.FileSystemException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException

/*
 * The phone's syncing, everything about it but the phone.
 *
 * Here rather than in :app because almost none of it needs Android -- what to
 * record when, which syncs may run at once, what a failure means -- and :app is
 * the one module nothing outside CI compiles. What does need Android comes in
 * through the three small interfaces below: where the settings are kept, how
 * a sync is scheduled to run unattended, and (from :api's common code) where
 * the photos are. :server's tests drive this against a real server.
 */

/**
 * What the phone keeps about syncing, across restarts.
 *
 * Holds the pairing code, which is a credential to the wardrobe on the server:
 * whatever keeps it must not put it in a backup, an export, or anywhere else
 * it could travel from. On the phone that is a preference file of its own,
 * which AppSettings' allowlist leaves out for exactly that reason.
 */
interface SyncPreferences {
    var address: String?
    var code: String?
    var lastSyncedAt: String?
    var lastFailure: String?
    var background: Boolean
    var wifiOnly: Boolean
}

/** Running a sync while nobody has the app open. WorkManager, on the phone. */
interface BackgroundSync {
    /** Run every so often from now on, with these rules, replacing any earlier ones. */
    fun schedule(wifiOnly: Boolean)

    fun cancel()
}

/**
 * A client for the sync port at [address], carrying [code].
 *
 * CIO rather than the platform's HTTP stack, and on Android that is a decision
 * about cleartext rather than about engines. Android refuses plain-http
 * requests unless the app opts in, and the opt-in is all or nothing per host --
 * a network security config lists the hosts it allows by name, and this host
 * is whatever somebody types, most often a bare address on a home network.
 * Opting in for every host would undo what :net relies on for URL import and
 * the update check (see ImportHttp). The policy is enforced by the platform's
 * HTTP stacks, HttpURLConnection and OkHttp, which ask it before they connect;
 * CIO speaks HTTP over its own sockets and does not ask. So this one client
 * reaches Home Assistant over http, as Home Assistant itself is usually reached
 * at home, and nothing else in the app can.
 *
 * Short timeouts, because the usual failure is an address that is not there --
 * the phone away from home, Home Assistant restarting -- and nobody should wait
 * a minute to be told so. The request itself may take longer: the first sync of
 * a large wardrobe sends all of it.
 */
fun syncHttpClient(address: String, code: String): HttpClient = HttpClient(CIO) {
    speakSync(address, code)
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }
}

private const val CONNECT_TIMEOUT_MS = 10_000L
private const val SOCKET_TIMEOUT_MS = 60_000L

/**
 * What [error] means to somebody looking at Settings.
 *
 * Unreachable covers more than the network: anything answering that is not
 * the sync port -- Home Assistant's own port, a router's page, a 404 -- answers
 * with something that is not this API, and the thing to do about it is the
 * same, check the address. A local file failing is not the network, though,
 * however much it is an IOException, so it keeps its own words.
 */
fun syncFailureOf(error: Throwable): SyncFailure = when (error) {
    is ServerException -> when (error.status) {
        401 -> SyncFailure.NotPaired
        404 -> SyncFailure.Unreachable
        else -> SyncFailure.Other(error.message ?: "${error.status}")
    }
    is NotFoundException,
    is UnresolvedAddressException,
    is SerializationException,
    is ContentConvertException,
    -> SyncFailure.Unreachable
    is FileNotFoundException, is FileSystemException -> SyncFailure.Other(error.message ?: error.javaClass.simpleName)
    is IOException -> SyncFailure.Unreachable
    else -> SyncFailure.Other(error.message ?: error.javaClass.simpleName)
}

/**
 * The phone's syncing: pairing, a sync from whichever of the three places
 * starts one, and keeping what Settings shows in step with both.
 *
 * One per process, because [status] and the rule that one sync runs at a time
 * are only true if everything that syncs goes through the same instance --
 * Settings, opening the app and the background worker all reach this one
 * through AppContainer.
 */
class PhoneSync(
    private val preferences: SyncPreferences,
    private val store: SyncStore,
    private val photos: PhotoFolder,
    private val background: BackgroundSync,
    private val clientFor: (address: String, code: String) -> HttpClient = ::syncHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PhoneSyncSource {

    /**
     * One sync at a time. Two at once would each merge the same wardrobe and
     * each write the result, which is safe -- the merge is idempotent and each
     * write a transaction -- but would fetch every new photo twice, and leaves
     * Settings unable to say which one it is showing.
     */
    private val running = Mutex()

    /** When a sync last finished in this process, so opening the app twice in a minute syncs once. */
    @Volatile
    private var lastFinishedAt: Long? = null

    private val _status = MutableStateFlow(read(syncing = false))
    override val status: StateFlow<PhoneSyncStatus> = _status.asStateFlow()

    private val _changes = MutableStateFlow(0)

    /**
     * How many syncs have changed the wardrobe here since the app started.
     *
     * For the screens: they read the wardrobe when they come into view, and a
     * sync that lands while one is already showing would otherwise go unseen
     * until somebody left and came back. Watching this tells them to read again.
     */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    override suspend fun connect(address: String, code: String): SyncFailure? {
        val failure = attempt { clientFor(address, code).use { WardrobeSyncClient(it, store, photos, io).check() } }
        if (failure != null) return failure

        preferences.address = address
        preferences.code = code.trim()
        // Whatever was recorded was about the last pairing, which may have
        // been a different Home Assistant.
        preferences.lastSyncedAt = null
        preferences.lastFailure = null
        if (preferences.background) background.schedule(preferences.wifiOnly)
        publish()
        return null
    }

    /** Sync now, because somebody asked: waits for one already running, then runs. */
    override suspend fun sync(): SyncFailure? {
        running.lock()
        return runLocked()
    }

    /**
     * Sync because the app was opened, unless that is pointless: not paired,
     * synced moments ago -- opening the camera and coming back is opening the
     * app again, as far as Android is concerned -- already syncing, or on a
     * network the phone was told not to sync over by itself.
     */
    suspend fun syncOnOpen(metered: Boolean): SyncFailure? {
        if (!_status.value.paired) return null
        if (metered && preferences.wifiOnly) return null
        val last = lastFinishedAt
        if (last != null && now() - last < OPEN_AGAIN_MS) return null
        return syncIfIdle()
    }

    /**
     * Sync from the background worker. Nothing if a sync is already running,
     * which will have done what this would. The worker checks the network
     * rules itself, as WorkManager constraints.
     */
    suspend fun syncInBackground(): SyncFailure? {
        if (!_status.value.paired || !preferences.background) return null
        return syncIfIdle()
    }

    private suspend fun syncIfIdle(): SyncFailure? {
        if (!running.tryLock()) return null
        return runLocked()
    }

    /** Runs with [running] held, and releases it. */
    private suspend fun runLocked(): SyncFailure? {
        try {
            val address = preferences.address
            val code = preferences.code
            if (address == null || code == null) return null

            publish(syncing = true)
            val failure = attempt {
                val report = clientFor(address, code).use { WardrobeSyncClient(it, store, photos, io).sync() }
                if (report.changed) _changes.update { it + 1 }
            }

            // Unpaired, or paired elsewhere, while this ran: there is nothing to
            // say about a pairing that is gone. The wardrobe it brought stays.
            if (preferences.address == address) {
                if (failure == null) preferences.lastSyncedAt = isoTimestamp(now())
                preferences.lastFailure = failure?.let(::storedSyncFailure)
            }
            lastFinishedAt = now()
            return failure
        } finally {
            running.unlock()
            publish()
        }
    }

    override fun disconnect() {
        preferences.address = null
        preferences.code = null
        preferences.lastSyncedAt = null
        preferences.lastFailure = null
        background.cancel()
        publish()
    }

    override fun setBackground(enabled: Boolean) {
        preferences.background = enabled
        if (_status.value.paired) {
            if (enabled) background.schedule(preferences.wifiOnly) else background.cancel()
        }
        publish()
    }

    override fun setWifiOnly(enabled: Boolean) {
        preferences.wifiOnly = enabled
        // A rule that was only written down would leave the schedule running
        // to the old one, as BackupSchedule says of its own.
        if (_status.value.paired && preferences.background) background.schedule(enabled)
        publish()
    }

    private fun publish(syncing: Boolean = running.isLocked) {
        _status.value = read(syncing)
    }

    private fun read(syncing: Boolean) = PhoneSyncStatus(
        address = preferences.address?.takeIf { preferences.code != null },
        lastSyncedAt = preferences.lastSyncedAt,
        lastFailure = syncFailureFor(preferences.lastFailure),
        syncing = syncing,
        background = preferences.background,
        wifiOnly = preferences.wifiOnly,
    )

    /** Null if [block] went through, or what its failure means. Cancellation is not a failure. */
    private suspend fun attempt(block: suspend () -> Unit): SyncFailure? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        syncFailureOf(e)
    }

    private companion object {
        const val OPEN_AGAIN_MS = 2 * 60 * 1000L
    }
}
