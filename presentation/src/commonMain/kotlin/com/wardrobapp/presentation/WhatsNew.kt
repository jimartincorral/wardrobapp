package com.wardrobapp.presentation

import com.wardrobapp.data.AppRelease
import com.wardrobapp.data.ReleaseNote
import com.wardrobapp.data.ReleaseNoteKind
import com.wardrobapp.data.ReleasePlatform
import com.wardrobapp.data.WebRelease

/*
 * What's new: telling somebody, once they have a build, what it changed.
 *
 * The update dialog tells them before, to help decide whether to install; this
 * tells them after, where the app can do what the dialog cannot -- take them to
 * the thing that changed, which the build that showed the dialog did not have.
 * The notes are the same ones: the history the update check reads, written as
 * Release-Note trailers (scripts/release-notes.py).
 */

/**
 * Where a note can send somebody: the screens the app can open from anywhere.
 *
 * [key] is what a note names, after the `->` in its brackets; the script holds
 * a list of the same keys, and ReleaseNoteDestinationsTest holds the two equal,
 * so no note can be published pointing at a screen the app does not know.
 */
enum class AppDestination(val key: String) {
    HOME("home"),
    WARDROBE("wardrobe"),
    OUTFITS("outfits"),
    STATISTICS("statistics"),
    SETTINGS("settings"),
    NEW_GARMENT("garment.new"),
    BULK_ADD("garment.bulk"),
    NEW_OUTFIT("outfit.new"),
    ;

    companion object {
        /** The destination [key] names, or null for none -- or one a later build added. */
        fun of(key: String?): AppDestination? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The note in [language], as a two-letter code: its Spanish if it has one and
 * that is what is asked for, its English otherwise. English is what every note
 * has, so a note nobody translated is still read rather than left out.
 */
fun ReleaseNote.textIn(language: String): String =
    if (language == "es") textEs ?: text else text

/** What to do about What's new on this launch. */
sealed interface WhatsNewDecision {
    /** Show these, and remember the build once they have been seen. */
    data class Show(val notes: List<ReleaseNote>) : WhatsNewDecision

    /** Nothing to show for this build; remember it, so the question is not asked again. */
    data object NothingNew : WhatsNewDecision

    /** Nothing can be said yet -- the notes could not be fetched. Ask again next launch. */
    data object NotYet : WhatsNewDecision
}

/**
 * Whether to show What's new on a phone running [installed], which last
 * showed it for (or decided against it for) [lastSeen], given the published
 * [release].
 *
 *  - Already seen this build, or a newer one: nothing.
 *  - Never seen anything, on a fresh install: nothing. Somebody who has just
 *    installed the app has no "before" for a list of changes to be relative
 *    to, and the welcome screen is what they are about to see.
 *  - Never seen anything, on an update: this build's notes. The phone was
 *    updated from a build that predates this screen and recorded nothing, so
 *    which build it came from is not known; this build's own notes are the
 *    ones that are certainly new to it.
 *  - Otherwise the phone's notes newer than [lastSeen], up to [installed]. A
 *    note for a later build than the one running describes something it does
 *    not have yet.
 *
 * [release] null is "the notes could not be read", which is not the same as
 * "there were none": the first waits for the next launch, the second is
 * recorded.
 */
fun whatsNewDecision(
    installed: Long,
    lastSeen: Long?,
    freshInstall: Boolean,
    release: AppRelease?,
): WhatsNewDecision {
    if (lastSeen != null && lastSeen >= installed) return WhatsNewDecision.NothingNew
    if (lastSeen == null && freshInstall) return WhatsNewDecision.NothingNew
    if (release == null) return WhatsNewDecision.NotYet

    val since = lastSeen ?: (installed - 1)
    val notes = release.history
        .filter { it.forPhone && it.build > since && it.build <= installed }
        .distinctBy { it.text }

    return if (notes.isEmpty()) WhatsNewDecision.NothingNew else WhatsNewDecision.Show(notes)
}

/** One heading's worth of What's new. */
data class WhatsNewGroup(val kind: ReleaseNoteKind, val notes: List<ReleaseNote>)

/**
 * [notes] under their headings: new things first, since they are what
 * somebody wants to go and try, then improvements, then fixes. Each keeps the
 * order it arrived in, newest first. Headings with nothing under them are left
 * out.
 */
fun whatsNewGroups(notes: List<ReleaseNote>): List<WhatsNewGroup> =
    ReleaseNoteKind.entries.mapNotNull { kind ->
        notes.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { WhatsNewGroup(kind, it) }
    }

/**
 * Whether the browser should show What's new, running the Home Assistant app
 * at version [current], having last shown it for [lastSeen], given what the
 * server says each version changed.
 *
 * As [whatsNewDecision], with one difference forced by the browser: it cannot
 * tell a browser opening the app for the first time from one that has used
 * it for months under a version from before this existed -- both have nothing
 * stored. Both are told nothing and start counting from here, since a
 * stranger's first look at the app is no time for a list of changes to it.
 *
 * Versions are compared as numbers, part by part, so 0.10.0 is newer than
 * 0.9.0. One that is not numbers at all -- a development build calls itself
 * "development" -- has nothing to compare and shows nothing.
 */
fun webWhatsNewDecision(
    current: String,
    lastSeen: String?,
    releases: List<WebRelease>?,
): WhatsNewDecision {
    if (lastSeen == null) return WhatsNewDecision.NothingNew
    val newer = compareVersions(current, lastSeen) ?: return WhatsNewDecision.NothingNew
    if (newer <= 0) return WhatsNewDecision.NothingNew
    if (releases == null) return WhatsNewDecision.NotYet

    val notes = releases
        .filter { release ->
            val sinceSeen = compareVersions(release.version, lastSeen) ?: return@filter false
            val upToCurrent = compareVersions(release.version, current) ?: return@filter false
            sinceSeen > 0 && upToCurrent <= 0
        }
        .flatMap { it.notes }
        .filter { ReleasePlatform.WEB in it.platforms }
        .distinctBy { it.text }

    return if (notes.isEmpty()) WhatsNewDecision.NothingNew else WhatsNewDecision.Show(notes)
}

/**
 * [a] against [b] as dotted version numbers: negative, zero or positive, or
 * null when either is not one. A missing part is a zero, so 0.2 is 0.2.0.
 */
fun compareVersions(a: String, b: String): Int? {
    val left = a.split('.').map { it.toIntOrNull() ?: return null }
    val right = b.split('.').map { it.toIntOrNull() ?: return null }
    for (index in 0 until maxOf(left.size, right.size)) {
        val difference = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    return 0
}
