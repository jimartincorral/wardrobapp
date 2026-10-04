package com.wardrobapp.server

import com.wardrobapp.api.Profile
import com.wardrobapp.api.Routes
import com.wardrobapp.api.WireJson
import com.wardrobapp.presentation.GarmentImporter
import java.io.File
import java.security.SecureRandom
import kotlinx.serialization.Serializable

/**
 * The wardrobes the server holds, one per profile, and which is whose.
 *
 * Written down in `profiles.json` in the data directory -- Home Assistant
 * backs it up with everything else -- as each profile's id, name, directory,
 * and the Home Assistant users who open it by default. Each profile's
 * directory holds what the whole data directory used to: a database, a photo
 * directory and a pairing code, in the same layout, opened by a ServerWardrobe
 * like any other.
 *
 * The wardrobe that was there before profiles existed becomes the first one,
 * where it already is. Not moved: a move is the one step of an upgrade that
 * could lose somebody's photos, and nothing needs the files anywhere else. So
 * its directory is the data directory itself, its pairing code is the one its
 * phones already have, and they carry on syncing without noticing anything
 * happened. New profiles go under `profiles/<id>/`.
 *
 * Wardrobes are opened when first asked for and kept open, as the single one
 * was; a household has a handful, and each is a database connection and some
 * objects.
 */
class ProfileRegistry(
    private val dataDirectory: File,
    private val importer: GarmentImporter? = null,
) : AutoCloseable {

    @Serializable
    private data class Stored(
        val id: String,
        val name: String,
        /** Relative to the data directory: `.` for the first, `profiles/<id>` for the rest. */
        val directory: String,
        /** Home Assistant user ids who open this one by default. */
        val users: List<String> = emptyList(),
    )

    @Serializable
    private data class Document(val profiles: List<Stored>)

    private val file = File(dataDirectory, "profiles.json")
    private val lock = Any()
    private var stored: List<Stored> = load()
    private val open = mutableMapOf<String, ServerWardrobe>()

    /** Every profile, in the order they were made: the first is the one that was there before. */
    fun list(): List<Profile> = synchronized(lock) { stored.map { Profile(it.id, it.name) } }

    /** The profile [user] opens by default, or null if they have not made one theirs. */
    fun yours(user: String?): String? = synchronized(lock) {
        if (user == null) null else stored.firstOrNull { user in it.users }?.id
    }

    /** The wardrobe of profile [id], opened if it is not yet; null for no such profile. */
    fun wardrobe(id: String): ServerWardrobe? = synchronized(lock) {
        open[id] ?: stored.firstOrNull { it.id == id }?.let { profile ->
            ServerWardrobe(
                dataDirectory = File(dataDirectory, profile.directory),
                importer = importer,
                photoPrefix = Routes.profileBase(profile.id) + Routes.PHOTO_FILES,
            ).also { open[id] = it }
        }
    }

    /**
     * The wardrobe whose pairing code is [code], for the sync port; null if
     * none is. Every profile's code is tried, each in constant time, so which
     * profile a code belongs to is not something a guess can time its way to.
     */
    fun pairedWith(code: String): ServerWardrobe? {
        val ids = synchronized(lock) { stored.map { it.id } }
        var found: ServerWardrobe? = null
        for (id in ids) {
            val wardrobe = wardrobe(id) ?: continue
            // No early exit, so a match on the first profile takes as long as
            // one on the last.
            if (wardrobe.syncSecret.accepts(code) && found == null) found = wardrobe
        }
        return found
    }

    /** Make a profile called [name], and make it [owner]'s own if one is given. */
    fun create(name: String, owner: String?): Profile = synchronized(lock) {
        val id = generateSequence { newId() }.first { candidate -> stored.none { it.id == candidate } }
        val profile = Stored(id = id, name = validName(name), directory = "profiles/$id")
        stored = (stored + profile).let { if (owner != null) claimed(it, id, owner) else it }
        save()
        Profile(profile.id, profile.name)
    }

    /** Rename profile [id]; false if there is no such profile. */
    fun rename(id: String, name: String): Boolean = synchronized(lock) {
        if (stored.none { it.id == id }) return false
        val valid = validName(name)
        stored = stored.map { if (it.id == id) it.copy(name = valid) else it }
        save()
        true
    }

    /** Make profile [id] the one [user] opens by default, and no longer any other; false if there is none. */
    fun makeYours(id: String, user: String): Boolean = synchronized(lock) {
        if (stored.none { it.id == id }) return false
        stored = claimed(stored, id, user)
        save()
        true
    }

    /** What [delete] did. */
    enum class Deletion { DELETED, NOT_FOUND, LAST }

    /**
     * Delete profile [id]: its clothes, outfits, photos and pairing code, for
     * good. Never the last one -- a server with no profiles would have nothing
     * to open, and the first thing it did on starting again would be to make
     * an empty one, which is what somebody wanting to start over could do
     * more plainly by making a new profile before deleting this one.
     *
     * In this order, so a crash between any two steps leaves something
     * nobody can reach rather than something half there:
     *  1. Out of the list, saved. Nothing can open it from here on; whoever
     *     had made it theirs is asked again which wardrobe is theirs.
     *  2. Its database closed for good, if it was open. A request already
     *     holding it fails, rather than reopening an empty one in its place.
     *  3. Its files deleted. A crash before this leaves files nothing lists,
     *     taking space and nothing else.
     *
     * A phone paired with it is not told. Its next sync is refused, as with a
     * code that was replaced, and it keeps its own copy of the wardrobe -- so
     * deleting a profile somebody still has on their phone is not the end of
     * it for them, and they can pair that phone with another profile.
     *
     * Only `profiles/<id>` is deleted whole. The first profile's directory is
     * the data directory itself, so for it only its own files go; and a
     * directory that is anything else -- profiles.json edited by hand, say --
     * is treated the same, rather than trusted with a recursive delete.
     */
    fun delete(id: String): Deletion = synchronized(lock) {
        val profile = stored.firstOrNull { it.id == id } ?: return Deletion.NOT_FOUND
        if (stored.size == 1) return Deletion.LAST

        stored = stored - profile
        save()
        open.remove(id)?.retire()

        val directory = File(dataDirectory, profile.directory)
        if (profile.directory == "profiles/${profile.id}") {
            directory.deleteRecursively()
        } else {
            ServerWardrobe.filesIn(directory).forEach { it.deleteRecursively() }
        }
        Deletion.DELETED
    }

    override fun close() = synchronized(lock) {
        open.values.forEach { it.close() }
        open.clear()
    }

    private fun claimed(profiles: List<Stored>, id: String, user: String) = profiles.map {
        when {
            it.id == id -> it.copy(users = (it.users - user) + user)
            user in it.users -> it.copy(users = it.users - user)
            else -> it
        }
    }

    private fun load(): List<Stored> {
        if (file.isFile) {
            val read = WireJson.decodeFromString(Document.serializer(), file.readText()).profiles
            if (read.isNotEmpty()) return read
        }
        // Before profiles: the wardrobe in the data directory is the first
        // one, unnamed until somebody names it. Written down at once, so the
        // id phones and browsers learn for it never changes.
        val first = listOf(Stored(id = FIRST, name = "", directory = "."))
        save(first)
        return first
    }

    /** Written beside and renamed over, as the pairing code is, so a crash mid-write leaves the old list. */
    private fun save(profiles: List<Stored> = stored) {
        dataDirectory.mkdirs()
        val partial = File(dataDirectory, "${file.name}.part")
        partial.writeText(WireJson.encodeToString(Document.serializer(), Document(profiles)))
        partial.renameTo(file) || error("Could not save the profiles to $file")
    }

    companion object {
        /** The first profile: the wardrobe there was before there were profiles. */
        const val FIRST = "main"

        const val MAX_NAME = 40

        /** A name trimmed and at most [MAX_NAME] long; a blank one is refused. */
        fun validName(name: String): String {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "A profile needs a name." }
            return trimmed.take(MAX_NAME)
        }

        /** Ten characters of Crockford's base32, lower case: safe in a path, and never a word. */
        private fun newId(): String {
            val random = SecureRandom()
            return (1..10).map { "0123456789abcdefghjkmnpqrstvwxyz"[random.nextInt(32)] }.joinToString("")
        }
    }
}
