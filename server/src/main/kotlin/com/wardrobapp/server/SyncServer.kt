package com.wardrobapp.server

import com.wardrobapp.api.ApiFailure
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.api.SyncAnswer
import com.wardrobapp.api.SyncRoutes
import com.wardrobapp.api.WireJson
import com.wardrobapp.data.WardrobeSnapshot
import com.wardrobapp.data.photoNames
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import io.ktor.util.AttributeKey
import io.ktor.server.routing.RoutingContext
import com.wardrobapp.data.isoTimestamp
import com.wardrobapp.data.MergedWardrobe

/**
 * The sync port: what a paired phone talks to.
 *
 * Every request must carry the pairing code (see SyncSecret) as a bearer
 * token, and is refused before anything else looks at it otherwise. Past that,
 * three things: say which server this is, merge a wardrobe, and move photos by
 * name. Nothing a screen uses is here, so opening this port opens nothing but
 * sync.
 *
 * Which wardrobe it syncs with is the pairing code's to say: every profile
 * has its own (see ProfileRegistry), and a phone holding one profile's code
 * reaches that profile and no other. So the phones in a household each keep
 * their own wardrobe in step with their owner's profile, and never meet.
 *
 * A merge is one transaction on the server's database -- read, merge, write --
 * so a phone syncing while somebody edits in the browser, or two phones at
 * once, cannot interleave with it; see SyncStore.mergeWith.
 */
fun Application.wardrobeSync(profiles: ProfileRegistry, version: ServerVersion) {
    install(paired(profiles))
    install(ContentNegotiation) { json(WireJson) }
    boundedBodies()
    answerFailures()

    routing {
        get("/${SyncRoutes.STATUS}") { call.respond(version) }

        post("/${SyncRoutes.EXCHANGE}") {
            val wardrobe = call.attributes[Syncing]
            val theirs = call.receive<WardrobeSnapshot>().withStoredPhotosOnly()
            answer(wardrobe, withContext(Dispatchers.IO) { wardrobe.sync.mergeWith(theirs) })
        }

        // A phone that restored a backup: its wardrobe replaces this one, as of
        // the restore, so the restore holds here and on every other phone. See
        // SyncStore.replaceWith.
        //
        // As of the restore, not as of now: the phone may have restored days
        // ago, away from home, and anything added here or on another phone
        // since is newer than the restore and should stay -- dated from now,
        // the deletions would beat it all. A phone from before it sent the
        // time gets now, as before. Never later than now, whatever the phone's
        // clock says: a deletion dated in the future would win against every
        // edit made until then.
        post("/${SyncRoutes.REPLACE}") {
            val wardrobe = call.attributes[Syncing]
            val theirs = call.receive<WardrobeSnapshot>().withStoredPhotosOnly()
            val now = isoTimestamp(System.currentTimeMillis())
            val restoredAt = call.request.queryParameters[SyncRoutes.RESTORED_AT]
                ?.takeIf { ISO_TIMESTAMP.matches(it) && it < now }
                ?: now
            answer(wardrobe, withContext(Dispatchers.IO) { wardrobe.sync.replaceWith(theirs, restoredAt) })
        }

        put("/${SyncRoutes.PHOTO}") {
            val wardrobe = call.attributes[Syncing]
            val bytes = call.receiveChannel().readRemaining(PhotoFiles.MAX_PHOTO_BYTES + 1L).readByteArray()
            wardrobe.photos.storeAs(call.parameters["name"].orEmpty(), bytes)
            // A photo that arrived is a garment the style model can now see;
            // what it reads off it reaches the phone on the next sync.
            wardrobe.style?.refresh()
            call.respond(HttpStatusCode.NoContent)
        }

        get("/${SyncRoutes.PHOTO}") {
            val wardrobe = call.attributes[Syncing]
            val name = call.parameters["name"].orEmpty()
            val file = wardrobe.photos.file(name)
            val type = PhotoType.ofName(name)
            if (file == null || type == null) {
                call.fail(HttpStatusCode.NotFound, ApiFailure.NotFound)
            } else {
                call.respond(LocalFileContent(file, ContentType.parse(type.contentType)))
            }
        }
    }
}

/**
 * Refuse any request without the pairing code, before routing or anything
 * else has looked at it.
 *
 * The answer is a 401 with an ApiFailure, so the phone's client turns it into
 * a ServerException with that status and the phone can say "pair again" rather
 * than "something went wrong".
 */
private fun paired(profiles: ProfileRegistry) = createApplicationPlugin("Paired") {
    val refusals = Refusals()
    onCall { call ->
        val offered = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
        val wardrobe = offered?.let(profiles::pairedWith)
        if (wardrobe != null) {
            call.attributes.put(Syncing, wardrobe)
        } else {
            refusals.noting(call.request.local.remoteAddress)?.let { call.application.log.warn(it) }
            call.fail(
                HttpStatusCode.Unauthorized,
                ApiFailure.Message("This phone is not paired with this Home Assistant, or its pairing code was changed."),
            )
        }
    }
}

/**
 * What to say in the log about a refused sync request, and when.
 *
 * One line per refusal was the right amount for a phone whose code was
 * reset -- a few lines, then somebody pairs again -- and the wrong amount for
 * a port scanner on the home network, which is a line per probe for as long
 * as it likes, in the log Home Assistant shows for the app. So a refusal is
 * written at once if nothing was written in the last minute, and otherwise
 * counted and folded into the next line that is: the log still says that
 * requests are being refused, from where, and how many, without being
 * filled by them.
 */
internal class Refusals(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var lastWrittenAt = 0L
    private var sinceThen = 0

    /** The line to log for a refusal of a request from [address], or null to keep quiet this time. */
    fun noting(address: String): String? = synchronized(lock) {
        val at = now()
        if (at - lastWrittenAt < QUIET_MILLIS) {
            sinceThen++
            return null
        }
        val folded = sinceThen
        lastWrittenAt = at
        sinceThen = 0
        buildString {
            append("Refused a sync request from ").append(address).append(": no pairing code, or the wrong one")
            if (folded > 0) append(" (and ").append(folded).append(" more since the last line)")
        }
    }

    private companion object {
        const val QUIET_MILLIS = 60_000L
    }
}

/**
 * [this] with every photo reference that is not the name of a stored photo
 * taken out.
 *
 * A phone's garment refers to its photos by file name, and those are what
 * the server stores and serves. The record's fields can hold other things
 * -- a web address, a data URL, an Android document -- because the phone's
 * own database keeps those as they are (see toStoredImageRef), and a phone
 * is trusted enough to be given the whole wardrobe. It is not trusted with
 * every browser in the household: a garment synced with a web address for
 * a photo would have every browser that opened the wardrobe fetch that
 * address, and the server would ask every phone for a photo of that name
 * for ever. So what is not a photo here is no photo here: a reference with
 * nothing behind it, the way a garment without a photo already looks.
 */
private fun WardrobeSnapshot.withStoredPhotosOnly(): WardrobeSnapshot = copy(
    garments = garments.map { garment ->
        garment.copy(
            imageUri = garment.imageUri.storedPhotoOrNone(),
            imageUriNoBg = garment.imageUriNoBg?.storedPhotoOrNone(),
            imageUris = garment.imageUris.map { it.storedPhotoOrNone() },
            imageUrisNoBg = garment.imageUrisNoBg.map { it.storedPhotoOrNone() },
        )
    },
)

/**
 * [this] if it names a stored photo, else nothing. An empty string stays
 * one, and a list keeps its length: the lists are positional -- the cut-out
 * list runs beside the photo list, an empty entry meaning "no cut-out of
 * this one" -- and a record that came back a different shape from the one
 * sent would read as a change on every sync.
 */
private fun String.storedPhotoOrNone(): String = if (isEmpty() || PhotoFiles.isPhotoName(this)) this else ""

/** The wardrobe the request's pairing code opened, put there by [paired]. */
private val Syncing = AttributeKey<ServerWardrobe>("syncing")

/**
 * What isoTimestamp writes, and nothing else: timestamps in this shape order
 * as text, which is how the merge compares them, and one in any other shape
 * would compare as nonsense against every row it met.
 */
private val ISO_TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")

/** Let go of the photos a merge left unused, and answer with the result and the photos still to come. */
private suspend fun RoutingContext.answer(wardrobe: ServerWardrobe, result: MergedWardrobe) {
    for (name in result.photosNoLongerUsed) wardrobe.photos.delete(name)
    // Garments that arrived with photos already here are embedded in the
    // background; the attributes read off them ride the sync after this one.
    wardrobe.style?.refresh()

    val missing = result.merged.garments
        .flatMap { it.photoNames() }
        .distinct()
        .filter { wardrobe.photos.file(it) == null }
    call.respond(SyncAnswer(merged = result.merged, missingPhotos = missing))
}
