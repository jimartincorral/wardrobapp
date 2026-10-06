package com.wardrobapp.server

import com.wardrobapp.api.ApiFailure
import com.wardrobapp.api.Flag
import com.wardrobapp.api.ImportRequest
import com.wardrobapp.api.Rating
import com.wardrobapp.api.Routes
import com.wardrobapp.api.SavedGarment
import com.wardrobapp.api.SavedPhotos
import com.wardrobapp.api.ServerFeatures
import com.wardrobapp.api.StoredPhoto
import com.wardrobapp.api.SuggestionRating
import com.wardrobapp.api.SyncPairing
import com.wardrobapp.api.WireJson
import com.wardrobapp.data.resolveImageRef
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.OutfitDraft
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import com.wardrobapp.presentation.SuggestionRequest
import com.wardrobapp.presentation.WardrobeQuery
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.util.AttributeKey
import io.ktor.server.routing.route
import com.wardrobapp.api.Profiles
import com.wardrobapp.api.ProfileName
import com.wardrobapp.api.NewProfile

/**
 * The routes in :api, answered from [wardrobe].
 *
 * Every handler is one call to one of the wardrobe's sources -- the Database
 * sources the phone's screens use -- with the request's body as its argument and
 * its answer as the response. Nothing about a wardrobe is decided here, so
 * nothing here can decide it differently from the phone; what is here is HTTP:
 * reading bodies, saying 404, and turning a failure into an ApiFailure the
 * browser can turn back into the exception it was.
 *
 * Nothing here checks who is asking; Home Assistant does, before a request
 * reaches the app: ingress lets through only people signed in to Home
 * Assistant. What is here is the other half, [ServerSettings.allowedClients]:
 * a request that did not come through ingress is refused before anything
 * else sees it, so the port being reachable from elsewhere on Home Assistant's
 * network is not a way around that check.
 */
fun Application.wardrobeApi(
    profiles: ProfileRegistry,
    settings: ServerSettings = ServerSettings(),
    hostPorts: HostPorts = HostPorts.from(settings),
) {
    settings.allowedClients?.let { allowed -> install(onlyFrom(allowed)) }

    install(ContentNegotiation) { json(WireJson) }

    answerFailures()

    attributes.put(ProfilesKey, profiles)

    routing {
        profileRoutes(profiles)

        // Everything else is one profile's, under its own path: the browser
        // makes `p/<id>/` its base once it knows which profile it is showing,
        // so these are the routes it always asked for, with the profile in
        // front. Each request reaches its profile's wardrobe through
        // [wardrobe] below; an id there is no profile for is a 404.
        route("/p/{profile}") {
            get(Routes.HOME_COUNTS) { call.respond(wardrobe.home.counts()) }

            garments()
            outfits()
            statistics()
            photos()

            get(Routes.STORAGE) { call.respond(wardrobe.storage()) }

            get(Routes.VERSION) { call.respond(settings.version) }

            get(Routes.FEATURES) { call.respond(ServerFeatures(removesBackgrounds = wardrobe.removesBackgrounds)) }

            // Served as the workflow wrote it: the browser reads it with the same
            // lenient parser the phone reads its own document with, so a server
            // that checked it first would only be a second opinion. Read per
            // request, since it is small and asked for once per page load.
            get(Routes.WHATS_NEW) {
                val notes = withContext(Dispatchers.IO) {
                    settings.releaseNotes?.takeIf { it.isFile }?.readText()
                }
                call.respondText(notes ?: "[]", ContentType.Application.Json)
            }

            // What a phone needs to pair with this profile, for Settings in the
            // browser to show -- behind ingress, so only somebody signed in to
            // Home Assistant sees it. Each profile has its own code, and a phone
            // syncs with whichever profile's code it was given.
            //
            // With the host port the sync port is published on, when Home
            // Assistant says: that is what lets the browser show one QR code
            // instead of a paragraph about Network settings.
            get(Routes.SYNC_PAIRING) {
                val port = settings.syncPort
                if (port == null) {
                    call.fail(HttpStatusCode.NotFound, ApiFailure.NotFound)
                } else {
                    call.respond(syncPairing(wardrobe.syncSecret.current(), port, hostPorts.of(port)))
                }
            }

            post(Routes.SYNC_PAIRING_RESET) {
                val port = settings.syncPort
                if (port == null) {
                    call.fail(HttpStatusCode.NotFound, ApiFailure.NotFound)
                } else {
                    call.respond(syncPairing(wardrobe.syncSecret.reset(), port, hostPorts.of(port)))
                }
            }

            post(Routes.IMPORT) {
                val url = wardrobe.importer.check(call.receive<ImportRequest>().url)
                call.respond(wardrobe.importer.import(url))
            }
        }

        val webDirectory = settings.webDirectory
        if (webDirectory != null) {
            // Below every route above, so the API and the photos are never
            // shadowed by a file of the same name.
            staticFiles("/", webDirectory) {
                default("index.html")
                // Browsers compile WebAssembly as it streams in only when it
                // arrives as application/wasm, and fall back to a slower path,
                // with a warning, for anything else.
                contentType { file -> if (file.extension == "wasm") ContentType("application", "wasm") else null }
                // The page itself names the current build's files, so it must
                // be asked for again; the files are named by their content and
                // never change under the same name.
                cacheControl { file ->
                    if (file.name == "index.html") listOf(CacheControl.NoCache(null)) else emptyList()
                }
            }
        }
    }
}

private fun Route.garments() {
    // The wardrobe the outfit editor picks from: everything in use.
    get(Routes.GARMENTS) { call.respond(wardrobe.outfitEdit.wardrobe()) }

    post(Routes.GARMENTS) {
        val saved = call.receive<SavedGarment>()
        wardrobe.garmentForm.save(null, saved.form, saved.previouslyStored)
        call.respond(HttpStatusCode.NoContent)
    }

    post(Routes.GARMENT_SEARCH) { call.respond(wardrobe.wardrobe.garments(call.receive<WardrobeQuery>())) }

    post(Routes.GARMENT_BULK) {
        wardrobe.bulkAdd.save(call.receive<BulkAddState.Draft>())
        call.respond(HttpStatusCode.NoContent)
    }

    post(Routes.GARMENT_DUPLICATES) {
        call.respond(wardrobe.garmentForm.duplicatesOf(call.receive<DuplicateCandidate>()))
    }

    get(Routes.BRANDS) { call.respond(wardrobe.garmentForm.brands()) }

    get(Routes.GARMENT) {
        call.respondOrNotFound(wardrobe.garmentDetail.garment(id()))
    }

    put(Routes.GARMENT) {
        val saved = call.receive<SavedGarment>()
        wardrobe.garmentForm.save(id(), saved.form, saved.previouslyStored)
        call.respond(HttpStatusCode.NoContent)
    }

    delete(Routes.GARMENT) {
        wardrobe.garmentDetail.delete(id())
        call.respond(HttpStatusCode.NoContent)
    }

    put(Routes.GARMENT_IN_USE) {
        wardrobe.garmentDetail.setInUse(id(), call.receive<Flag>().value)
        call.respond(HttpStatusCode.NoContent)
    }

    put(Routes.GARMENT_PHOTOS) {
        val saved = call.receive<SavedPhotos>()
        wardrobe.garmentDetail.savePhotos(id(), saved.edit, saved.alsoImages)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.outfits() {
    get(Routes.OUTFITS) {
        val includeArchived = call.request.queryParameters["archived"] == "true"
        call.respond(wardrobe.outfitList.saved(includeArchived))
    }

    post(Routes.OUTFITS) {
        wardrobe.outfitEdit.create(call.receive<OutfitDraft>())
        call.respond(HttpStatusCode.NoContent)
    }

    get(Routes.OUTFIT) { call.respondOrNotFound(wardrobe.outfitEdit.outfit(id())) }

    put(Routes.OUTFIT) {
        wardrobe.outfitEdit.update(id(), call.receive<OutfitDraft>())
        call.respond(HttpStatusCode.NoContent)
    }

    delete(Routes.OUTFIT) {
        wardrobe.outfitDetail.delete(id())
        call.respond(HttpStatusCode.NoContent)
    }

    get(Routes.OUTFIT_DETAIL) { call.respondOrNotFound(wardrobe.outfitDetail.outfit(id())) }

    put(Routes.OUTFIT_RATING) {
        wardrobe.outfitDetail.rate(id(), validRating(call.receive<Rating>().rating))
        call.respond(HttpStatusCode.NoContent)
    }

    put(Routes.OUTFIT_PINNED) {
        wardrobe.outfitList.setPinned(id(), call.receive<Flag>().value)
        call.respond(HttpStatusCode.NoContent)
    }

    post(Routes.OUTFIT_UNARCHIVE) {
        wardrobe.outfitList.unarchive(id())
        call.respond(HttpStatusCode.NoContent)
    }

    post(Routes.SUGGESTIONS) { call.respond(wardrobe.outfitList.suggest(call.receive<SuggestionRequest>())) }

    post(Routes.SUGGESTION_KEPT) {
        wardrobe.outfitList.keep(call.receive<Suggestion>())
        call.respond(HttpStatusCode.NoContent)
    }

    post(Routes.SUGGESTION_RATING) {
        val rated = call.receive<SuggestionRating>()
        wardrobe.outfitList.rate(rated.suggestion, validRating(rated.rating))
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.statistics() {
    get(Routes.STATISTICS) { call.respond(wardrobe.statistics.counts()) }
    get(Routes.STATISTICS_DUPLICATES) { call.respond(wardrobe.statistics.duplicates()) }
    get(Routes.STATISTICS_GAPS) { call.respond(wardrobe.statistics.gaps()) }
}

private fun Route.photos() {
    post(Routes.PHOTOS) {
        // Read with a ceiling, so a body that never ends is refused at the
        // limit instead of after it has been held in memory whole. One byte
        // past the limit is enough to know.
        val bytes = call.receiveChannel().readRemaining(PhotoFiles.MAX_PHOTO_BYTES + 1L).readByteArray()
        val name = wardrobe.photos.store(bytes)
        call.respond(HttpStatusCode.Created, StoredPhoto(wardrobe.photoRef(name)))
    }

    delete(Routes.PHOTO) {
        wardrobe.photos.delete(call.parameters["name"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }

    // Through the garment screen's own source, as the phone's cut-out goes
    // through its: the same naming, the same reference back, and the seconds
    // of work on its IO dispatcher rather than the threads answering requests.
    post(Routes.PHOTO_CUT_OUT) {
        call.respond(HttpStatusCode.Created, StoredPhoto(wardrobe.garmentDetail.cutOut(call.parameters["name"].orEmpty())))
    }

    get(Routes.PHOTO_FILE) {
        val name = call.parameters["name"].orEmpty()
        val file = wardrobe.photos.file(name)
        val type = PhotoType.ofName(name)
        if (file == null || type == null) {
            call.fail(HttpStatusCode.NotFound, ApiFailure.NotFound)
            return@get
        }
        // Served as what its first bytes said it was when it was stored, and
        // the browser told not to guess otherwise.
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Cache-Control", "private, max-age=86400")
        call.respond(LocalFileContent(file, ContentType.parse(type.contentType)))
    }
}

/**
 * Refuse any request from an address not in [allowed], before routing,
 * content negotiation or anything else has looked at it.
 *
 * The socket's own peer, `local.remoteAddress`, rather than `origin`: origin
 * is what a request says about itself once forwarding headers are believed,
 * and this is exactly the place not to believe them. Ingress connects from its
 * own address, so the peer is the proxy, which is the thing being checked.
 */
private fun onlyFrom(allowed: Set<String>) = createApplicationPlugin("OnlyFrom") {
    onCall { call ->
        val peer = call.request.local.remoteAddress
        if (peer !in allowed) {
            call.application.log.warn("Refused a request from $peer, which is not Home Assistant's ingress")
            call.respondText(
                "This app answers only through Home Assistant.",
                status = HttpStatusCode.Forbidden,
            )
        }
    }
}

/*
 * Routes are written relative, for the browser's sake -- see Routes -- and
 * registered from the root of this server, which is where Home Assistant's
 * ingress delivers them once it has taken its own prefix off.
 */
private fun Route.get(route: String, body: suspend RoutingContext.() -> Unit) = get("/$route", body)
private fun Route.post(route: String, body: suspend RoutingContext.() -> Unit) = post("/$route", body)
private fun Route.put(route: String, body: suspend RoutingContext.() -> Unit) = put("/$route", body)
private fun Route.delete(route: String, body: suspend RoutingContext.() -> Unit) = delete("/$route", body)

private fun RoutingContext.id(): String = call.parameters["id"].orEmpty()

private val ProfilesKey = AttributeKey<ProfileRegistry>("profiles")

/**
 * The wardrobe of the profile this request is for: the one named in its path.
 * Read where the handlers used to read the server's one wardrobe, so moving to
 * profiles changed where each route is registered and nothing in what it does.
 */
private val RoutingContext.wardrobe: ServerWardrobe
    get() = call.application.attributes[ProfilesKey].wardrobe(call.parameters["profile"].orEmpty())
        ?: throw ProfileNotFound()

/**
 * Who is asking, as Home Assistant's ingress says: the id of the signed-in
 * user, in a header the Supervisor sets on every request it forwards, having
 * removed any the browser sent itself. Believed only because nothing but
 * ingress reaches this server (see [onlyFrom]); null where nobody says, as on
 * a development server answering a browser directly.
 */
private fun RoutingContext.homeAssistantUser(): String? =
    call.request.headers["X-Remote-User-Id"]?.trim()?.takeIf { it.isNotEmpty() }

/** The profiles themselves: answered at the root, since the browser asks before it has one. */
private fun Route.profileRoutes(profiles: ProfileRegistry) {
    get(Routes.PROFILES) {
        val user = homeAssistantUser()
        call.respond(Profiles(profiles = profiles.list(), yours = profiles.yours(user), signedIn = user != null))
    }

    post(Routes.PROFILES) {
        val wanted = call.receive<NewProfile>()
        if (wanted.name.isBlank()) throw BadRequestException("A profile needs a name.")
        call.respond(profiles.create(wanted.name, owner = if (wanted.yours) homeAssistantUser() else null))
    }

    put(Routes.PROFILE) {
        val name = call.receive<ProfileName>().name
        if (name.isBlank()) throw BadRequestException("A profile needs a name.")
        if (profiles.rename(id(), name)) call.respond(HttpStatusCode.NoContent) else throw ProfileNotFound()
    }

    // Anybody can delete any profile, as anybody can rename or open one: the
    // profiles are a household's way of keeping wardrobes apart, not a lock
    // between its members (see Profiles in :api). The browser asks first.
    delete(Routes.PROFILE) {
        when (profiles.delete(id())) {
            ProfileRegistry.Deletion.DELETED -> call.respond(HttpStatusCode.NoContent)
            ProfileRegistry.Deletion.NOT_FOUND -> throw ProfileNotFound()
            ProfileRegistry.Deletion.LAST -> call.fail(
                HttpStatusCode.Conflict,
                ApiFailure.Message("The only wardrobe there is cannot be deleted. Make another first."),
            )
        }
    }

    post(Routes.PROFILE_YOURS) {
        val user = homeAssistantUser()
            ?: throw BadRequestException("Only somebody signed in to Home Assistant can have a profile of their own.")
        if (profiles.makeYours(id(), user)) call.respond(HttpStatusCode.NoContent) else throw ProfileNotFound()
    }
}

/** One to five, as every screen that rates offers; anything else is a request that did not come from one. */
private fun validRating(rating: Int): Int {
    if (rating !in 1..5) throw BadRequestException("A rating is from 1 to 5, not $rating.")
    return rating
}

private suspend inline fun <reified T : Any> ApplicationCall.respondOrNotFound(value: T?) {
    if (value == null) fail(HttpStatusCode.NotFound, ApiFailure.NotFound) else respond(value)
}

/** The pairing Settings shows, with what Home Assistant said about [port]'s mapping; see [SyncPairing]. */
private fun syncPairing(code: String, port: Int, hostPort: HostPort) = SyncPairing(
    code = code,
    port = port,
    hostPortKnown = hostPort != HostPort.Unknown,
    hostPort = (hostPort as? HostPort.Open)?.port,
)
