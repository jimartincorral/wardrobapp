package com.wardrobapp.api

import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.presentation.BackgroundEdit
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.OutfitsScreenState.Suggestion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON both sides speak.
 *
 * Defaults are written out. Leaving them off saves a few bytes and makes every
 * default a promise: a value the sender left out is whatever the *receiver's*
 * build thinks the default is. The browser and the server ship together today,
 * but the phone will sync with the server one day, on whatever version it has.
 *
 * Unknown keys are an error, for the same reason in the other direction: a
 * field one side sends and the other drops is data quietly lost, and the place
 * to find that out is a test, not somebody's wardrobe.
 */
val WireJson: Json = Json { encodeDefaults = true }

/*
 * The bodies a route needs that are not already one of the screens' own types.
 * Most requests and answers are -- a garment, a query, the form's state -- and
 * travel as themselves; these wrap what is left over, a bare value or two
 * values that a source method takes side by side.
 */

/** A yes or no: whether a garment is in use, whether an outfit is pinned. */
@Serializable
data class Flag(val value: Boolean)

/** An outfit's rating, one to five. */
@Serializable
data class Rating(val rating: Int)

/** A suggestion and the rating it was given, before it is an outfit of its own. */
@Serializable
data class SuggestionRating(val suggestion: Suggestion, val rating: Int)

/** A garment's photos after a background was removed or put back. */
@Serializable
data class SavedPhotos(val edit: BackgroundEdit, val alsoImages: Boolean)

/**
 * The garment form, saved: what it says now, and the photos the garment held
 * when the form opened, so the server can delete the ones it no longer does.
 */
@Serializable
data class SavedGarment(val form: GarmentFormState, val previouslyStored: List<String>)

/** A product page to fill the garment form in from. */
@Serializable
data class ImportRequest(val url: String)

/**
 * Which build of the server this is, for Settings' About section: the Home
 * Assistant app's version and the CI run that built it, or "development" and 0
 * for a server built anywhere else.
 */
@Serializable
data class ServerVersion(val name: String, val build: Long) {
    companion object {
        /** A server not built by the release workflow, and what the page shows until the server answers. */
        val DEVELOPMENT = ServerVersion(name = "development", build = 0)
    }
}

/**
 * What this server can do that another might not, so the browser offers only
 * that. Every field defaults to what a server without it would say, so an
 * older browser reading a newer server's answer, and the other way round,
 * reads what it knows and nothing else.
 */
@Serializable
data class ServerFeatures(
    /** Whether it has the model to cut a garment out of its background; see Routes.PHOTO_CUT_OUT. */
    val removesBackgrounds: Boolean = false,
)

/** Where an uploaded photo was stored, in the form a garment row refers to it by. */
@Serializable
data class StoredPhoto(val ref: String)

/**
 * Why a request failed, as the body of any answer that is not a success.
 *
 * Typed rather than a status code and a sentence, because the screens tell
 * failures apart by type: the garment form says one thing for an address on
 * the reader's own network and another for a page with no garment on it, and
 * both reasons arrive here intact to be turned back into the exceptions the
 * form already catches. Everything else is a message, which is what the
 * phone's screens show for an exception they have no words of their own for.
 */
@Serializable
sealed interface ApiFailure {

    @Serializable
    @SerialName("message")
    data class Message(val message: String) : ApiFailure

    /** No garment or outfit by that id. A source answers that with null. */
    @Serializable
    @SerialName("not-found")
    data object NotFound : ApiFailure

    @Serializable
    @SerialName("unsafe-url")
    data class UnsafeUrl(val reason: UnsafeUrlReason) : ApiFailure

    @Serializable
    @SerialName("import-failed")
    data class ImportFailed(val reason: ImportFailureReason) : ApiFailure
}

/**
 * A request the server answered with a failure that is only a message.
 *
 * Its message is the server's, so a screen showing `readableMessage()` shows
 * what the phone would have shown for the same failure on the phone.
 */
class ServerException(message: String, val status: Int) : Exception(message)

/** Nothing by that id; turned back into null by the sources that can say so. */
class NotFoundException : Exception("Not found")
