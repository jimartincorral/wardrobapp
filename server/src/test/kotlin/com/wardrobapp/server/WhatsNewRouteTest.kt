package com.wardrobapp.server

import com.wardrobapp.api.Routes
import com.wardrobapp.data.ReleaseNoteKind
import com.wardrobapp.data.parseWebReleases
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The browser's What's new, as the server hands it out. */
class WhatsNewRouteTest {

    @Test
    fun `the notes written into the image are served as they are`() {
        val notes = Files.createTempFile("release-notes", ".json").toFile()
        notes.writeText("""[{"version": "0.3.0", "notes": [{"text": "New.", "kind": "new", "platforms": ["web"]}]}]""")
        try {
            serverTest(settings = ServerSettings(releaseNotes = notes)) {
                val served = parseWebReleases(http.get(Routes.WHATS_NEW).bodyAsText())!!

                assertEquals("0.3.0", served.single().version)
                assertEquals(ReleaseNoteKind.NEW, served.single().notes.single().kind)
            }
        } finally {
            notes.delete()
        }
    }

    @Test
    fun `a server built without them has nothing new to say`() {
        serverTest {
            assertEquals(emptyList(), parseWebReleases(http.get(Routes.WHATS_NEW).bodyAsText()))
        }
        serverTest(settings = ServerSettings(releaseNotes = java.io.File("/nowhere/release-notes.json"))) {
            assertEquals(emptyList(), parseWebReleases(http.get(Routes.WHATS_NEW).bodyAsText()))
        }
    }
}
