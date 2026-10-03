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

    /** GET a photo by name, or PUT one under the name the phone stored it as. */
    const val PHOTO = "sync/v1/photos/{name}"

    fun photo(name: String) = PHOTO.replace("{name}", name.encodeURLPathPart())
}

/** The server's answer to a sync: the merged wardrobe, and the photos it needs the phone to send. */
@Serializable
data class SyncAnswer(val merged: WardrobeSnapshot, val missingPhotos: List<String>)

/**
 * What a phone needs to pair: the code, and the port the server listens for
 * sync on inside its container. The host port it is reached on is whatever it
 * is mapped to in Home Assistant, which the server cannot know.
 */
@Serializable
data class SyncPairing(val code: String, val port: Int)

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
    suspend fun sync(): SyncReport {
        val ours = withContext(io) { store.snapshot() }

        val answer = http.post(SyncRoutes.EXCHANGE) {
            contentType(ContentType.Application.Json)
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
