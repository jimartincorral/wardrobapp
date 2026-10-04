package com.wardrobapp.server

import com.wardrobapp.api.ApiFailure
import com.wardrobapp.domain.GarmentImportException
import com.wardrobapp.domain.UnsafeUrlException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

/**
 * Turn every failure into an ApiFailure the client can turn back into the
 * exception it was. The same for both of the server's faces -- the browser's,
 * through ingress, and the phone's sync port -- since both are answered by
 * :api's client.
 */
internal fun Application.answerFailures() {
    install(StatusPages) {
        exception<UnsafeUrlException> { call, e ->
            call.fail(HttpStatusCode.UnprocessableEntity, ApiFailure.UnsafeUrl(e.reason))
        }
        exception<GarmentImportException> { call, e ->
            call.fail(HttpStatusCode.UnprocessableEntity, ApiFailure.ImportFailed(e.reason))
        }
        exception<PhotoRejected.TooLarge> { call, e ->
            call.fail(HttpStatusCode.PayloadTooLarge, ApiFailure.Message(e.message.orEmpty()))
        }
        exception<PhotoRejected.NotAPhoto> { call, e ->
            call.fail(HttpStatusCode.UnsupportedMediaType, ApiFailure.Message(e.message.orEmpty()))
        }
        // A profile id in the path that names no profile: a bookmark from a
        // profile that was since removed, or a hand-typed address.
        exception<ProfileNotFound> { call, _ ->
            call.fail(HttpStatusCode.NotFound, ApiFailure.NotFound)
        }
        // A photo to cut out that is not there: deleted in another tab, or
        // never this profile's.
        exception<PhotoNotFound> { call, _ ->
            call.fail(HttpStatusCode.NotFound, ApiFailure.NotFound)
        }
        // A server with no model, asked anyway -- by a page loaded before an
        // update took the model away, say. Not Implemented is what it is.
        exception<BackgroundRemovalUnavailable> { call, e ->
            call.fail(HttpStatusCode.NotImplemented, ApiFailure.Message(e.message.orEmpty()))
        }
        // A body that is not the JSON the route takes. Ktor wraps the
        // serializer's complaint; the complaint is the useful part.
        exception<BadRequestException> { call, e ->
            call.fail(HttpStatusCode.BadRequest, ApiFailure.Message(e.readable()))
        }
        // Everything else is what the phone would have shown as the screen's
        // error: the exception's own message. Logged in full, because the
        // browser gets one sentence and somebody will want the rest.
        exception<Throwable> { call, e ->
            call.application.log.error("${call.request.local.method.value} ${call.request.local.uri} failed", e)
            call.fail(HttpStatusCode.InternalServerError, ApiFailure.Message(e.readable()))
        }
    }
}

/** Answer with [failure], typed as an ApiFailure so it carries the type that says which. */
internal suspend fun ApplicationCall.fail(status: HttpStatusCode, failure: ApiFailure) {
    respond<ApiFailure>(status, failure)
}

private fun Throwable.readable(): String {
    // BadRequestException's own message is Ktor's ("Failed to convert request
    // body to ..."); the cause says what was wrong with the body.
    val meaningful = if (this is BadRequestException && cause != null) cause!! else this
    return meaningful.message ?: meaningful::class.simpleName ?: "Something went wrong."
}

/** A request for a profile there is none of; answered as any other missing thing. */
internal class ProfileNotFound : RuntimeException("No such profile.")

/** A stored photo there is none of; answered as any other missing thing. */
internal class PhotoNotFound : RuntimeException("No such photo.")

/** Asked to cut a photo out on a server without the model to do it with. */
internal class BackgroundRemovalUnavailable : RuntimeException("Removing a background is not available on this server.")
