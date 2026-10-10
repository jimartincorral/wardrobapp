package com.wardrobapp.api

import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapWithPhotos
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.InspirationRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.toStoredImageRef
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.UnsafeUrlException
import com.wardrobapp.presentation.BackgroundEdit
import com.wardrobapp.presentation.BulkAddSource
import com.wardrobapp.presentation.BulkAddState
import com.wardrobapp.presentation.GarmentDetailSource
import com.wardrobapp.presentation.GarmentFormSource
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.GarmentImporter
import com.wardrobapp.presentation.HomeCounts
import com.wardrobapp.presentation.HomeSource
import com.wardrobapp.presentation.InspirationSource
import com.wardrobapp.presentation.OutfitDetailContent
import com.wardrobapp.presentation.OutfitDetailSource
import com.wardrobapp.presentation.OutfitDraft
import com.wardrobapp.presentation.OutfitEditSource
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import com.wardrobapp.presentation.OutfitsSource
import com.wardrobapp.presentation.SavedOutfits
import com.wardrobapp.presentation.StatisticsCounts
import com.wardrobapp.presentation.StatisticsSource
import com.wardrobapp.presentation.StorageFigures
import com.wardrobapp.presentation.SuggestionRequest
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.WardrobeSource
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException

/*
 * Each screen's source, answered by the Home Assistant server.
 *
 * The browser's counterpart to the Database sources in :presentation: the same
 * interfaces, the same screen models on top, and on the other end of each call a
 * server that answers it with exactly those Database sources. So a question asked
 * here gets the answer the phone would have given itself, computed by the same
 * code, and nothing here decides anything -- every method is one request.
 *
 * Failures come back as the exceptions the screens already handle. The server
 * sends an ApiFailure; [speakWardrobe] turns it back into a typed exception
 * before any of these sees the response, so a source here never has to look at
 * a status code.
 */

/**
 * Set a client up to talk to the wardrobe server: JSON in [WireJson], requests
 * relative to [baseUrl], and failures thrown as the exceptions they stand for.
 *
 * [baseUrl] is where the browser loaded the app from, ending in a slash, and
 * null to keep whatever the engine already has -- which is what :server's tests
 * want, their client being pointed at the server under test already.
 */
fun HttpClientConfig<*>.speakWardrobe(baseUrl: String? = null) {
    // Every answer is checked below, by its body, instead of Ktor's own check,
    // which knows only the status and would throw away why.
    expectSuccess = false

    install(ContentNegotiation) { json(WireJson) }

    if (baseUrl != null) {
        defaultRequest { url(baseUrl) }
    }

    HttpResponseValidator {
        validateResponse { response ->
            if (!response.status.isSuccess()) throw response.failure()
        }
    }
}

private suspend fun HttpResponse.failure(): Exception {
    val text = bodyAsText()
    val failure = try {
        WireJson.decodeFromString(ApiFailure.serializer(), text)
    } catch (_: SerializationException) {
        // Not from the server at all: Home Assistant's own error page when the
        // app is restarting, a proxy's, a gateway timeout. Say what arrived.
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    return when (failure) {
        is ApiFailure.Message -> ServerException(failure.message, status.value)
        ApiFailure.NotFound -> NotFoundException()
        is ApiFailure.UnsafeUrl -> UnsafeUrlException(failure.reason)
        is ApiFailure.ImportFailed -> GarmentImportException(failure.reason)
        null -> ServerException("The server answered ${status.value} ${status.description}", status.value)
    }
}

/** The answer, or null when the server says there is nothing by that id. */
private suspend inline fun <T> orNullIfMissing(request: () -> T): T? = try {
    request()
} catch (_: NotFoundException) {
    null
}

private suspend inline fun <reified T> HttpClient.postJson(route: String, body: T): HttpResponse =
    post(route) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend inline fun <reified T> HttpClient.putJson(route: String, body: T): HttpResponse =
    put(route) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

class HttpHomeSource(private val http: HttpClient) : HomeSource {
    override suspend fun counts(): HomeCounts = http.get(Routes.HOME_COUNTS).body()
}

class HttpOutfitDetailSource(private val http: HttpClient) : OutfitDetailSource {
    override suspend fun outfit(id: String): OutfitDetailContent? =
        orNullIfMissing { http.get(Routes.outfitDetail(id)).body<OutfitDetailContent>() }

    override suspend fun rate(outfitId: String, rating: Int) {
        http.putJson(Routes.outfitRating(outfitId), Rating(rating))
    }

    override suspend fun delete(outfitId: String) {
        http.delete(Routes.outfit(outfitId))
    }

    override suspend fun undoDelete(outfitId: String): Boolean =
        http.post(Routes.outfitUndelete(outfitId)).body<Flag>().value
}

class HttpOutfitEditSource(private val http: HttpClient) : OutfitEditSource {
    override suspend fun wardrobe(): List<GarmentRecord> = http.get(Routes.GARMENTS).body()

    override suspend fun outfit(id: String): OutfitRecord? =
        orNullIfMissing { http.get(Routes.outfit(id)).body<OutfitRecord>() }

    override suspend fun create(draft: OutfitDraft) {
        http.postJson(Routes.OUTFITS, draft)
    }

    override suspend fun update(id: String, draft: OutfitDraft) {
        http.putJson(Routes.outfit(id), draft)
    }
}

class HttpWardrobeSource(private val http: HttpClient) : WardrobeSource {
    override suspend fun garments(query: WardrobeQuery): List<GarmentRecord> =
        http.postJson(Routes.GARMENT_SEARCH, query).body()
}

class HttpStatisticsSource(private val http: HttpClient) : StatisticsSource {
    override suspend fun counts(): StatisticsCounts = http.get(Routes.STATISTICS).body()

    override suspend fun duplicates(): List<DuplicateGarmentGroup> = http.get(Routes.STATISTICS_DUPLICATES).body()

    override suspend fun gaps(): List<GapWithPhotos> = http.get(Routes.STATISTICS_GAPS).body()
}

class HttpOutfitsSource(private val http: HttpClient) : OutfitsSource {
    override suspend fun garment(id: String): GarmentRecord? =
        orNullIfMissing { http.get(Routes.garment(id)).body<GarmentRecord>() }

    override suspend fun suggest(request: SuggestionRequest): List<Suggestion> =
        http.postJson(Routes.SUGGESTIONS, request).body()

    override suspend fun saved(includeArchived: Boolean): SavedOutfits =
        http.get(Routes.OUTFITS) { parameter("archived", includeArchived) }.body()

    override suspend fun keep(suggestion: Suggestion) {
        http.postJson(Routes.SUGGESTION_KEPT, suggestion)
    }

    override suspend fun rate(suggestion: Suggestion, rating: Int) {
        http.postJson(Routes.SUGGESTION_RATING, SuggestionRating(suggestion, rating))
    }

    override suspend fun unarchive(outfitId: String) {
        http.post(Routes.outfitUnarchive(outfitId))
    }

    override suspend fun setPinned(outfitId: String, pinned: Boolean) {
        http.putJson(Routes.outfitPinned(outfitId), Flag(pinned))
    }

    override suspend fun delete(outfitId: String) {
        http.delete(Routes.outfit(outfitId))
    }

    override suspend fun undoDelete(outfitId: String): Boolean =
        http.post(Routes.outfitUndelete(outfitId)).body<Flag>().value
}

class HttpGarmentDetailSource(private val http: HttpClient) : GarmentDetailSource {
    override suspend fun garment(id: String): GarmentRecord? =
        orNullIfMissing { http.get(Routes.garment(id)).body<GarmentRecord>() }

    override suspend fun setInUse(id: String, inUse: Boolean) {
        http.putJson(Routes.garmentInUse(id), Flag(inUse))
    }

    override suspend fun delete(id: String) {
        http.delete(Routes.garment(id))
    }

    // The memory of recent deletes is the server's, with the files: the
    // browser only asks. See DatabaseGarmentDetailSource.
    override suspend fun undoDelete(id: String): Boolean =
        http.post(Routes.garmentUndelete(id)).body<Flag>().value

    override suspend fun discardDeleted(id: String) {
        http.post(Routes.garmentDiscard(id))
    }

    /**
     * The server cuts it out, with the model BackgroundRemover runs there: the
     * phone's ML Kit is Android's, and the browser has nothing of its own.
     */
    override suspend fun cutOut(photo: String): String = HttpPhotos(http).cutOut(photo)

    override suspend fun savePhotos(id: String, edit: BackgroundEdit, alsoImages: Boolean) {
        http.putJson(Routes.garmentPhotos(id), SavedPhotos(edit, alsoImages))
    }
}

class HttpInspirationSource(private val http: HttpClient) : InspirationSource {
    override suspend fun looks(): List<InspirationRecord> = http.get(Routes.INSPIRATIONS).body()

    override suspend fun add(photo: String): InspirationRecord =
        http.postJson(Routes.INSPIRATIONS, StoredPhoto(photo)).body()

    override suspend fun delete(id: String) {
        http.delete(Routes.inspiration(id))
    }
}

class HttpBulkAddSource(private val http: HttpClient) : BulkAddSource {
    override suspend fun save(draft: BulkAddState.Draft) {
        http.postJson(Routes.GARMENT_BULK, draft)
    }
}

class HttpGarmentFormSource(private val http: HttpClient) : GarmentFormSource {
    override suspend fun garment(id: String): GarmentRecord? =
        orNullIfMissing { http.get(Routes.garment(id)).body<GarmentRecord>() }

    override suspend fun brands(): List<String> = http.get(Routes.BRANDS).body()

    override suspend fun duplicatesOf(candidate: DuplicateCandidate): List<DuplicateGarment> =
        http.postJson(Routes.GARMENT_DUPLICATES, candidate).body()

    override suspend fun save(garmentId: String?, form: GarmentFormState, previouslyStored: List<String>) {
        val body = SavedGarment(form, previouslyStored)
        if (garmentId == null) http.postJson(Routes.GARMENTS, body) else http.putJson(Routes.garment(garmentId), body)
    }
}

/**
 * URL import, done by the server: it is the side with :net's address checks and
 * a network that is not the reader's browser.
 */
class HttpGarmentImporter(private val http: HttpClient) : GarmentImporter {

    /**
     * Only trimmed. The real check is :domain's, and it is JVM code -- it needs
     * `java.net.IDN` -- so it runs on the server, as part of [import], and a
     * refused address comes back from there as the same UnsafeUrlException it
     * would have been here. What the browser loses is being told before the
     * request rather than after it, which for an address typed by hand is the
     * same moment.
     */
    override fun check(url: String): String = url.trim()

    override suspend fun import(url: String): ImportedGarmentPreview =
        http.postJson(Routes.IMPORT, ImportRequest(url)).body()
}

/** What the settings screen asks the server: the storage figures, and which build it is. */
class HttpStorageSource(private val http: HttpClient) {
    suspend fun storage(): StorageFigures = http.get(Routes.STORAGE).body()

    suspend fun version(): ServerVersion = http.get(Routes.VERSION).body()

    /** What a phone needs to pair, or null when the server has sync switched off. */
    suspend fun pairing(): SyncPairing? = orNullIfMissing { http.get(Routes.SYNC_PAIRING).body<SyncPairing>() }

    /** A new pairing code, unpairing every phone. */
    suspend fun resetPairing(): SyncPairing = http.post(Routes.SYNC_PAIRING_RESET).body()
}

/**
 * Photos on the server: upload one, delete one.
 *
 * Not a PhotoWork, which also decodes photos for their colours and is generic
 * over what a platform's picker hands back; the browser's PhotoWork, when it is
 * written, does those itself and uses this for the parts that need the server.
 */
class HttpPhotos(private val http: HttpClient) {

    /** Store [bytes] as a photo of [type]; the reference a garment row will hold. */
    suspend fun upload(bytes: ByteArray, type: ContentType): String =
        http.post(Routes.PHOTOS) {
            contentType(type)
            setBody(bytes)
        }.body<StoredPhoto>().ref

    /** Delete a stored photo, by any form of its reference. */
    suspend fun delete(photo: String) {
        http.delete(Routes.photo(toStoredImageRef(photo)))
    }

    /**
     * Cut a stored photo, by any form of its reference, out of its background;
     * the cut-out's reference. Takes seconds: the server runs a model.
     */
    suspend fun cutOut(photo: String): String =
        http.post(Routes.photoCutOut(toStoredImageRef(photo))).body<StoredPhoto>().ref
}
