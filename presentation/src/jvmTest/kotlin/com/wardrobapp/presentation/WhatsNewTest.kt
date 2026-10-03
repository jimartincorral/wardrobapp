package com.wardrobapp.presentation

import com.wardrobapp.data.AppRelease
import com.wardrobapp.data.ReleaseNote
import com.wardrobapp.data.ReleaseNoteKind
import com.wardrobapp.data.ReleasePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsNewTest {

    private fun release(vararg notes: ReleaseNote) = AppRelease(
        versionCode = notes.maxOf { it.build },
        versionName = "1.1.0",
        apkUrl = "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
        changes = emptyList(),
        history = notes.toList(),
    )

    private val sync = ReleaseNote(1130, "Sync with Home Assistant.", ReleaseNoteKind.NEW, textEs = "Sincroniza.", destination = "settings")
    private val browser = ReleaseNote(1130, "The browser keeps its place.", ReleaseNoteKind.FIXED, setOf(ReleasePlatform.WEB))
    private val fix = ReleaseNote(1129, "Ratings stick.", ReleaseNoteKind.FIXED)
    private val older = ReleaseNote(1128, "Older.", ReleaseNoteKind.IMPROVED)
    private val later = ReleaseNote(1131, "Not installed yet.", ReleaseNoteKind.NEW)
    private val published = release(later, sync, browser, fix, older)

    @Test
    fun `after an update, the phone's notes since the build it last showed`() {
        assertEquals(
            WhatsNewDecision.Show(listOf(sync, fix)),
            whatsNewDecision(installed = 1130, lastSeen = 1128, freshInstall = false, release = published),
        )
    }

    @Test
    fun `nothing twice, and nothing for a build already seen`() {
        assertEquals(WhatsNewDecision.NothingNew, whatsNewDecision(1130, lastSeen = 1130, freshInstall = false, release = published))
        assertEquals(WhatsNewDecision.NothingNew, whatsNewDecision(1130, lastSeen = 1131, freshInstall = false, release = published))
    }

    @Test
    fun `a fresh install has no before, and is told nothing`() {
        assertEquals(WhatsNewDecision.NothingNew, whatsNewDecision(1130, lastSeen = null, freshInstall = true, release = published))
    }

    @Test
    fun `an update from a build that recorded nothing is told about this build`() {
        assertEquals(
            WhatsNewDecision.Show(listOf(sync)),
            whatsNewDecision(1130, lastSeen = null, freshInstall = false, release = published),
        )
    }

    @Test
    fun `notes that could not be read are asked for again, not recorded as none`() {
        assertEquals(WhatsNewDecision.NotYet, whatsNewDecision(1130, lastSeen = 1128, freshInstall = false, release = null))
    }

    @Test
    fun `a build with nothing for the phone is recorded as nothing`() {
        assertEquals(
            WhatsNewDecision.NothingNew,
            whatsNewDecision(1130, lastSeen = 1129, freshInstall = false, release = release(browser)),
        )
    }

    @Test
    fun `new things first, then improvements, then fixes`() {
        val groups = whatsNewGroups(listOf(fix, older, sync))

        assertEquals(listOf(ReleaseNoteKind.NEW, ReleaseNoteKind.IMPROVED, ReleaseNoteKind.FIXED), groups.map { it.kind })
        assertEquals(listOf(listOf(sync), listOf(older), listOf(fix)), groups.map { it.notes })
    }

    @Test
    fun `a note reads in Spanish when it has Spanish`() {
        assertEquals("Sincroniza.", sync.textIn("es"))
        assertEquals("Sync with Home Assistant.", sync.textIn("en"))
        assertEquals("Ratings stick.", fix.textIn("es"))
    }

    @Test
    fun `a destination is one the app knows, or nothing`() {
        assertEquals(AppDestination.SETTINGS, AppDestination.of("settings"))
        assertEquals(null, AppDestination.of("somewhere-new"))
        assertEquals(null, AppDestination.of(null))
    }
}

/**
 * The destinations a release note may name, as the script that publishes notes
 * knows them, against the ones the app can open. A destination only the script
 * knew would publish a link that does nothing; one only the app knew could
 * never be written.
 */
class ReleaseNoteDestinationsTest {

    @Test
    fun `the script and the app know the same destinations`() {
        val script = File(System.getProperty("releaseNotesScript")).readText()
        val tuple = Regex("""DESTINATIONS = \(([^)]*)\)""").find(script)?.groupValues?.get(1)
            ?: error("No DESTINATIONS in release-notes.py")
        val inScript = Regex("""'([^']+)'""").findAll(tuple).map { it.groupValues[1] }.toList()

        assertEquals(AppDestination.entries.map { it.key }, inScript)
    }
}
