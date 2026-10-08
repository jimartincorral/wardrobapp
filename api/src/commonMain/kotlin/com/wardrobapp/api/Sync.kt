package com.wardrobapp.api

import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSnapshot
import com.wardrobapp.data.photoNames
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/*
 * The phone syncing with the Home Assistant server.
 *
 * Not through ingress, which only lets in a browser signed in to Home
 * Assistant: through a port of its own that the server opens for sync alone,
 * which is closed until it is given a host port in the app's Network settings,
 * and which answers only a request carrying the pairing code the browser shows
 * in Settings. See the server's SyncServer for that side, and WardrobeSync.kt in
 * :data for what a sync does to the two wardrobes.
 */

/** The sync port's routes, relative like Routes' and for the same reason. */
object SyncRoutes {
    /** GET: the server's [ServerVersion], which is how a phone checks it is paired. */
    const val STATUS = "sync/v1/status"

    /** POST a [WardrobeSnapshot]; answers [SyncAnswer]. */
    const val EXCHANGE = "sync/v1/exchange"

    /**
     * POST a [WardrobeSnapshot] that replaces the server's wardrobe rather
     * than merging into it, after the phone restored a backup; answers
     * [SyncAnswer]. A server from before this answers 404, which the phone
     * reads as "update the Home Assistant app" (see PhoneSync).
     *
     * With [RESTORED_AT], the time of the restore as the phone stamped its
     * rows with, which is when what the backup lacks counts as deleted. A
     * phone from before the parameter sends none, and the server uses the
     * time the request arrived, as it always did; see SyncStore.replaceWith
     * for what that costs.
     */
    const val REPLACE = "sync/v1/replace"

    /** [REPLACE]'s query parameter: an ISO timestamp, as isoTimestamp writes one. */
    const val RESTORED_AT = "restoredAt"

    /** GET a photo by name, or PUT one under the name the phone stored it as. */
    const val PHOTO = "sync/v1/photos/{name}"

    fun photo(name: String) = PHOTO.replace("{name}", name.encodeURLPathPart())
}

/** The server's answer to a sync: the merged wardrobe, and the photos it needs the phone to send. */
@Serializable
data class SyncAnswer(val merged: WardrobeSnapshot, val missingPhotos: List<String>)

/**
 * What a phone needs to pair: the code, the port the server listens for sync
 * on inside its container, and the port Home Assistant publishes that one on.
 *
 * [hostPort] is what makes a QR code possible: without it the browser can say
 * only "the port you chose", and the person has to remember which. Home
 * Assistant tells the server (see HostPorts in :server), when it is asked from
 * inside Home Assistant; [hostPortKnown] is false where nothing could be asked
 * -- a server run on its own, or a Supervisor that did not answer -- and then
 * a null [hostPort] means "cannot tell" rather than "closed", and the browser
 * falls back to the instructions it has always shown.
 *
 * Both default, so a browser reading an older server's answer reads "cannot
 * tell", which is true.
 */
@Serializable
data class SyncPairing(
    val code: String,
    val port: Int,
    val hostPortKnown: Boolean = false,
    val hostPort: Int? = null,
)

/**
 * Set a client up to sync: as [speakWardrobe] does, plus the pairing code on
 * every request.
 */
fun HttpClientConfig<*>.speakSync(baseUrl: String, code: String) {
    speakWardrobe(baseUrl)
    defaultRequest { header(HttpHeaders.Authorization, "Bearer ${code.trim()}") }
}

/** Where a side keeps its photos, by the names garment rows hold. */
interface PhotoFolder {
    suspend fun has(name: String): Boolean

    /** The photo's bytes, or null if there is none by that name. */
    suspend fun read(name: String): ByteArray?

    suspend fun write(name: String, bytes: ByteArray)

    suspend fun delete(name: String)
}

/**
 * What a sync did, for whatever started it: the photos it moved, and whether
 * this side's wardrobe changed -- photos arriving included, since a garment that
 * was drawn without its photo is drawn differently once it has one.
 */
data class SyncReport(val uploaded: Int, val downloaded: Int, val changed: Boolean)

/**
 * One phone's syncing: its wardrobe, its photos, and a client pointed at the
 * server's sync port with the pairing code.
 *
 * The database work runs on [io], since SyncStore's calls block.
 */
class WardrobeSyncClient(
    private val http: HttpClient,
    private val store: SyncStore,
    private val photos: PhotoFolder,
    private val io: CoroutineDispatcher,
) {
    /** The server's version, or the reason it will not answer: not reachable, or not paired. */
    suspend fun check(): ServerVersion = http.get(SyncRoutes.STATUS).body()

    /**
     * Sync once:
     *
     *  1. Send this side's wardrobe; the server merges it into its own, keeps
     *     the result, and answers with it and the photos it lacks.
     *  2. Send those photos.
     *  3. Merge the answer into this side's wardrobe -- merged again rather
     *     than applied, so anything changed here while the request was out
     *     survives; see SyncStore.mergeWith.
     *  4. Let go of the photos nothing here uses any more, and fetch the ones
     *     the merged wardrobe uses that this side does not have.
     *
     * Photos are sent before the merge is applied here, and fetched after, so
     * a sync that dies partway leaves both sides with every photo their own
     * garments refer to -- at worst some that nothing does yet.
     */
    suspend fun sync(replace: Boolean = false, restoredAt: String? = null): SyncReport {
        val ours = withContext(io) { store.snapshot() }

        // Replacing is the same exchange to a different route: the server
        // deletes what this side does not have instead of keeping it, and
        // answers with the result. Merging that answer here is then a no-op
        // for every row this side sent, so nothing else changes below. The
        // time of the restore goes with it, so the deletions date from the
        // restore rather than from whenever this request got through.
        val answer = http.post(if (replace) SyncRoutes.REPLACE else SyncRoutes.EXCHANGE) {
            contentType(ContentType.Application.Json)
            if (replace && restoredAt != null) parameter(SyncRoutes.RESTORED_AT, restoredAt)
            setBody(ours)
        }.body<SyncAnswer>()

        var uploaded = 0
        for (name in answer.missingPhotos) {
            val bytes = photos.read(name) ?: continue
            http.put(SyncRoutes.photo(name)) {
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
            uploaded++
        }

        val result = withContext(io) { store.mergeWith(answer.merged) }
        for (name in result.photosNoLongerUsed) photos.delete(name)

        var downloaded = 0
        for (name in result.merged.garments.flatMap { it.photoNames() }.distinct()) {
            if (photos.has(name)) continue
            val bytes = try {
                http.get(SyncRoutes.photo(name)).readRawBytes()
            } catch (_: NotFoundException) {
                // The server has the garment but not this photo: another phone
                // has not sent it yet. The garment shows without it until then.
                continue
            }
            photos.write(name, bytes)
            downloaded++
        }

        return SyncReport(
            uploaded = uploaded,
            downloaded = downloaded,
            changed = result.changedAnything || downloaded > 0,
        )
    }
}
