package com.wardrobapp.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/*
 * Profiles: several wardrobes in one Home Assistant app.
 *
 * A household shares one Home Assistant, and before this shared one wardrobe:
 * every browser and every paired phone saw everybody's clothes, and a second
 * person pairing a phone merged their wardrobe into the first. A profile is a
 * wardrobe of its own -- its own database, photos and pairing code, in its own
 * directory -- with a name somebody chose.
 *
 * Profiles are named rather than being Home Assistant's users, so a child
 * without a login, or two people sharing one, can still have a wardrobe each.
 * But Home Assistant does say who is signed in, so each of its users can make
 * one profile theirs, and that is the one their browser opens; anybody can
 * switch to any other. This is a household's convenience, not a wall between
 * its members: whoever administers Home Assistant can read every file anyway.
 *
 * A phone holds one wardrobe and syncs with one profile: the one whose code it
 * was given. Nothing on the phone knows profiles exist.
 */

/** A profile, as the browser lists it. */
@Serializable
data class Profile(
    val id: String,
    /**
     * What somebody called it. Empty for the wardrobe that was there before
     * profiles existed, until somebody names it -- the browser says what it
     * is in the reader's language rather than the server guessing a name in
     * one.
     */
    val name: String,
)

/** Every profile, and which one the person asking opens by default. */
@Serializable
data class Profiles(
    val profiles: List<Profile>,
    /** The id of the asking Home Assistant user's own profile, or null if they have not chosen one. */
    val yours: String?,
    /**
     * Whether the request came from a Home Assistant user the server can name.
     * Through ingress it always does; a development server answering a
     * browser directly does not, and then there is nobody to make a profile
     * "yours" for.
     */
    val signedIn: Boolean,
)

/** What making a profile asks for. */
@Serializable
data class NewProfile(
    val name: String,
    /** Make it the asking Home Assistant user's own, as the first one they make usually is. */
    val yours: Boolean,
)

/** A profile's new name. */
@Serializable
data class ProfileName(val name: String)

/**
 * The profile requests, which the browser makes at the page's root -- with
 * the client it had before it knew which profile to show.
 */
class HttpProfiles(private val http: HttpClient) {
    suspend fun list(): Profiles = http.get(Routes.PROFILES).body()

    suspend fun create(name: String, yours: Boolean): Profile = http.post(Routes.PROFILES) {
        contentType(ContentType.Application.Json)
        setBody(NewProfile(name, yours))
    }.body()

    suspend fun rename(id: String, name: String) {
        http.put(Routes.profile(id)) {
            contentType(ContentType.Application.Json)
            setBody(ProfileName(name))
        }
    }

    suspend fun makeYours(id: String) {
        http.post(Routes.profileYours(id))
    }

    /** Delete profile [id] and everything in it. Refused, with a ServerException, for the only one there is. */
    suspend fun delete(id: String) {
        http.delete(Routes.profile(id))
    }
}
