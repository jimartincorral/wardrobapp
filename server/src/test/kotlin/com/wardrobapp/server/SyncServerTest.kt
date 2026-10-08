package com.wardrobapp.server

import com.wardrobapp.api.DirectoryPhotoFolder
import com.wardrobapp.api.ServerException
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.api.SyncPairing
import com.wardrobapp.api.SyncReport
import com.wardrobapp.api.SyncRoutes
import com.wardrobapp.api.WardrobeSyncClient
import com.wardrobapp.api.speakSync
import com.wardrobapp.api.speakWardrobe
import com.wardrobapp.data.GarmentQueries
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.JdbcSqlDriver
import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.data.photoNames
import com.wardrobapp.presentation.GarmentFormState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * A phone syncing with the server: a database and a photo folder of the
 * phone's own, synced over the sync port by :api's client -- the code the phone
 * will run -- against a real server over a real database.
 */
class SyncServerTest {

    private class Phone(directory: File) {
        // A file, opened as the app opens one: foreign keys on, as on a phone.
        val database = JdbcSqlDriver.open(File(directory, "phone.db")).also { WardrobeSchema.applyTo(it) }
        val photos = DirectoryPhotoFolder(File(directory, "phone-photos"))
        val garments = GarmentWrites(database)
        fun garment(id: String) = GarmentQueries(database, "").garment(id)
    }

    private class Setup(val server: ServerWardrobe, val phone: Phone, val builder: ApplicationTestBuilder) {
        fun client(code: String = server.syncSecret.current()): HttpClient =
            builder.createClient { speakSync("http://localhost/", code) }

        fun syncer(code: String = server.syncSecret.current()) =
            WardrobeSyncClient(client(code), SyncStore(phone.database), phone.photos, Dispatchers.IO)

        suspend fun sync(): SyncReport = syncer().sync()
    }

    private fun syncTest(block: suspend Setup.() -> Unit) {
        val directory = Files.createTempDirectory("wardrobe-sync").toFile()
        val profiles = ProfileRegistry(File(directory, "server"))
        val server = profiles.wardrobe(ProfileRegistry.FIRST)!!
        try {
            testApplication {
                application { wardrobeSync(profiles, ServerVersion("0.2.0", 9)) }
                Setup(server, Phone(directory), this).block()
            }
        } finally {
            profiles.close()
            directory.deleteRecursively()
        }
    }

    private val t1 = "2025-03-01T10:00:00.000Z"

    private fun GarmentWrites.add(id: String, photo: String, brand: String? = null) = insert(
        GarmentWrites.NewGarment(
            id = id,
            imageUri = photo,
            imageUris = listOf(photo),
            category = "tops",
            colorPrimary = "#112233",
            colorPalette = listOf("#112233"),
            brand = brand,
            now = t1,
        ),
    )

    @Test
    fun `a phone with the code is answered, and one without is refused`() = syncTest {
        assertEquals(ServerVersion("0.2.0", 9), syncer().check())

        val wrong = assertFailsWith<ServerException> { syncer(code = "AAAAA-BBBBB-CCCCC-DDDDD").check() }
        assertEquals(HttpStatusCode.Unauthorized.value, wrong.status)
        val none = assertFailsWith<ServerException> {
            builder.createClient { speakWardrobe("http://localhost/") }.get(SyncRoutes.STATUS)
        }
        assertEquals(HttpStatusCode.Unauthorized.value, none.status)
    }

    @Test
    fun `the code is read the way a person types it`() = syncTest {
        val code = server.syncSecret.current()
        assertEquals(ServerVersion("0.2.0", 9), syncer(code = code.lowercase().replace("-", " ")).check())
    }

    @Test
    fun `a sync brings each side what the other has, photos and all`() = syncTest {
        val phonePhoto = jpeg(seed = 1)
        phone.photos.write("from-phone.jpg", phonePhoto)
        phone.garments.add("phone-garment", "from-phone.jpg", brand = "Phone")

        val serverPhoto = server.photos.store(jpeg(seed = 2))
        server.garmentForm.save(
            garmentId = null,
            form = GarmentFormState(imageUris = listOf(serverPhoto), bgRemovedUris = listOf(""), category = "bottoms", colorPalette = listOf("#000000"), colorsChosen = true),
            previouslyStored = emptyList(),
        )

        val report = sync()

        assertEquals(1, report.uploaded)
        assertEquals(1, report.downloaded)
        assertTrue(report.changed)
        assertEquals("Phone", server.garmentForm.garment("phone-garment")?.brand)
        assertContentEquals(phonePhoto, server.photos.file("from-phone.jpg")?.readBytes())
        val fromServer = SyncStore(phone.database).snapshot().garments.single { it.category == "bottoms" }
        assertContentEquals(server.photos.file(serverPhoto)!!.readBytes(), phone.photos.read(fromServer.imageUri))

        // Settled: a second sync has nothing to move.
        assertEquals(SyncReport(uploaded = 0, downloaded = 0, changed = false), sync())
        assertEquals(SyncStore(phone.database).snapshot(), server.sync.snapshot())
    }

    @Test
    fun `a photo that is a web address on the phone is no photo on the server`() = syncTest {
        // A phone's record can name a web address where a photo should be;
        // the server keeps the garment and drops the address, so no browser
        // in the household fetches it and no phone is asked for it.
        phone.garments.add("tracked", "https://tracker.example/pixel.gif", brand = "Tracked")

        val report = sync()

        assertEquals(0, report.uploaded, "nothing was asked for")
        val onServer = server.sync.snapshot().garments.single()
        assertEquals("Tracked", onServer.brand)
        assertEquals("", onServer.imageUri)
        assertTrue(onServer.imageUris.all { it.isEmpty() }, "nothing in any slot: ${onServer.imageUris}")
        assertEquals(emptyList(), onServer.photoNames())

        // And it stays that way: the phone sends the address again, the
        // server drops it again, and nothing counts as changed.
        assertEquals(SyncReport(uploaded = 0, downloaded = 0, changed = false), sync())
    }

    @Test
    fun `refusals are logged once a minute, with a count, not once a request`() {
        var now = 1_000_000L
        val refusals = Refusals { now }

        assertEquals("Refused a sync request from 192.168.1.9: no pairing code, or the wrong one", refusals.noting("192.168.1.9"))
        now += 10_000
        assertNull(refusals.noting("192.168.1.9"), "within the minute: counted, not written")
        assertNull(refusals.noting("192.168.1.10"))
        now += 55_000
        assertEquals(
            "Refused a sync request from 192.168.1.10: no pairing code, or the wrong one (and 2 more since the last line)",
            refusals.noting("192.168.1.10"),
        )
        now += 60_000
        assertEquals("Refused a sync request from 10.0.0.2: no pairing code, or the wrong one", refusals.noting("10.0.0.2"), "the count was spent")
    }

    @Test
    fun `a garment deleted on the phone goes from the server, files and all`() = syncTest {
        phone.photos.write("gone.jpg", jpeg())
        phone.garments.add("gone", "gone.jpg")
        sync()
        assertNotNull(server.photos.file("gone.jpg"))

        val photos = phone.garments.delete("gone", "2025-04-01T00:00:00.000Z")
        for (photo in photos) phone.photos.delete(photo)
        sync()

        assertNull(server.garmentForm.garment("gone"))
        assertNull(server.photos.file("gone.jpg"), "the server kept the photo of a deleted garment")
    }

    @Test
    fun `a photo whose name says one thing and whose bytes say another is refused`() = syncTest {
        val refused = assertFailsWith<ServerException> {
            client().put(SyncRoutes.photo("looks-like.jpg")) { setBody(png()) }
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType.value, refused.status)
        assertFalse(File(server.photos.directory, "looks-like.jpg").exists())
    }

    @Test
    fun `resetting the code unpairs the phone`() = syncTest {
        val old = server.syncSecret.current()
        assertEquals(ServerVersion("0.2.0", 9), syncer(old).check())

        val new = server.syncSecret.reset()

        assertTrue(old != new)
        assertEquals(401, assertFailsWith<ServerException> { syncer(old).check() }.status)
        assertEquals(ServerVersion("0.2.0", 9), syncer(new).check())
    }

    @Test
    fun `the code survives a restart`() {
        val directory = Files.createTempDirectory("wardrobe-sync").toFile()
        try {
            val first = ServerWardrobe(directory).let { it.syncSecret.current().also { _ -> it.close() } }
            val second = ServerWardrobe(directory).let { it.syncSecret.current().also { _ -> it.close() } }
            assertEquals(first, second)
            assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}").matches(first), first)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Settings in the browser shows the code, and only through ingress`() =
        serverTest {
            val pairing = http.get(com.wardrobapp.api.Routes.SYNC_PAIRING).body<SyncPairing>()
            assertEquals(SyncPairing(code = wardrobe.syncSecret.current(), port = ServerSettings.DEFAULT_SYNC_PORT), pairing)

            val reset = http.post(com.wardrobapp.api.Routes.SYNC_PAIRING_RESET).body<SyncPairing>()
            assertTrue(reset.code != pairing.code)
            assertEquals(wardrobe.syncSecret.current(), reset.code)
        }

    @Test
    fun `Settings in the browser says which host port phones reach, when Home Assistant says`() {
        serverTest(hostPorts = { port -> if (port == ServerSettings.DEFAULT_SYNC_PORT) HostPort.Open(18100) else HostPort.Unknown }) {
            val pairing = http.get(com.wardrobapp.api.Routes.SYNC_PAIRING).body<SyncPairing>()
            assertEquals(SyncPairing(wardrobe.syncSecret.current(), ServerSettings.DEFAULT_SYNC_PORT, true, 18100), pairing)
            // And a new code comes with it, so the QR code that replaces the
            // old one is as complete.
            val reset = http.post(com.wardrobapp.api.Routes.SYNC_PAIRING_RESET).body<SyncPairing>()
            assertEquals(18100, reset.hostPort)
        }
        serverTest(hostPorts = { HostPort.Closed }) {
            val pairing = http.get(com.wardrobapp.api.Routes.SYNC_PAIRING).body<SyncPairing>()
            assertTrue(pairing.hostPortKnown)
            assertEquals(null, pairing.hostPort)
        }
    }
}
