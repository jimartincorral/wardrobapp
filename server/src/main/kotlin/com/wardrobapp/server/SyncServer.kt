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
    answerFailures()

    routing {
        get("/${SyncRoutes.STATUS}") { call.respond(version) }

        post("/${SyncRoutes.EXCHANGE}") {
            val wardrobe = call.attributes[Syncing]
            val theirs = call.receive<WardrobeSnapshot>()
            answer(wardrobe, withContext(Dispatchers.IO) { wardrobe.sync.mergeWith(theirs) })
        }

        // A phone that restored a backup: its wardrobe replaces this one, as of
        // now, so the restore holds here and on every other phone. See
        // SyncStore.replaceWith.
        post("/${SyncRoutes.REPLACE}") {
            val wardrobe = call.attributes[Syncing]
            val theirs = call.receive<WardrobeSnapshot>()
            val now = isoTimestamp(System.currentTimeMillis())
            answer(wardrobe, withContext(Dispatchers.IO) { wardrobe.sync.replaceWith(theirs, now) })
        }

        put("/${SyncRoutes.PHOTO}") {
            val wardrobe = call.attributes[Syncing]
            val bytes = call.receiveChannel().readRemaining(PhotoFiles.MAX_PHOTO_BYTES + 1L).readByteArray()
            wardrobe.photos.storeAs(call.parameters["name"].orEmpty(), bytes)
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
    onCall { call ->
        val offered = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
        val wardrobe = offered?.let(profiles::pairedWith)
        if (wardrobe != null) {
            call.attributes.put(Syncing, wardrobe)
        } else {
            call.application.log.warn("Refused a sync request from ${call.request.local.remoteAddress}: no pairing code, or the wrong one")
            call.fail(
                HttpStatusCode.Unauthorized,
                ApiFailure.Message("This phone is not paired with this Home Assistant, or its pairing code was changed."),
            )
        }
    }
}

/** The wardrobe the request's pairing code opened, put there by [paired]. */
private val Syncing = AttributeKey<ServerWardrobe>("syncing")

/** Let go of the photos a merge left unused, and answer with the result and the photos still to come. */
private suspend fun RoutingContext.answer(wardrobe: ServerWardrobe, result: MergedWardrobe) {
    for (name in result.photosNoLongerUsed) wardrobe.photos.delete(name)

    val missing = result.merged.garments
        .flatMap { it.photoNames() }
        .distinct()
        .filter { wardrobe.photos.file(it) == null }
    call.respond(SyncAnswer(merged = result.merged, missingPhotos = missing))
}
