package com.wardrobapp.server

import com.wardrobapp.api.BackgroundSync
import com.wardrobapp.api.DirectoryPhotoFolder
import com.wardrobapp.api.PhoneSync
import com.wardrobapp.api.SyncPreferences
import com.wardrobapp.api.speakSync
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.JdbcSqlDriver
import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.SyncFailure
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's syncing as the app runs it -- pairing, the three ways a sync
 * starts, and what Settings is told -- against a real server.
 */
class PhoneSyncTest {

    private class Preferences : SyncPreferences {
        override var address: String? = null
        override var code: String? = null
        override var lastSyncedAt: String? = null
        override var lastFailure: String? = null
        override var background: Boolean = true
        override var wifiOnly: Boolean = true
    }

    /** What WorkManager would have been told. */
    private class Schedule : BackgroundSync {
        var scheduled: Boolean? = null
        var wifiOnly: Boolean? = null
        override fun schedule(wifiOnly: Boolean) {
            scheduled = true
            this.wifiOnly = wifiOnly
        }
        override fun cancel() {
            scheduled = false
        }
    }

    private class Setup(val server: ServerWardrobe, val phone: PhoneSync, val preferences: Preferences, val schedule: Schedule, val clock: Clock)

    private class Clock(var millis: Long = 1_790_000_000_000L)

    /** The address a phone types, as the model has it; the test client ignores where it points. */
    private val address = "http://localhost/"

    private fun phoneTest(block: suspend Setup.() -> Unit) {
        val directory = Files.createTempDirectory("phone-sync").toFile()
        val profiles = ProfileRegistry(File(directory, "server"))
        val server = profiles.wardrobe(ProfileRegistry.FIRST)!!
        val database = JdbcSqlDriver.open(File(directory, "phone.db")).also { WardrobeSchema.applyTo(it) }
        try {
            testApplication {
                application { wardrobeSync(profiles, ServerVersion("0.2.0", 9)) }
                val preferences = Preferences()
                val schedule = Schedule()
                val clock = Clock()
                val phone = PhoneSync(
                    preferences = preferences,
                    store = SyncStore(database),
                    photos = DirectoryPhotoFolder(File(directory, "phone-photos")),
                    background = schedule,
                    clientFor = { address, code -> createClient { speakSync(address, code) } },
                    now = { clock.millis },
                )
                Setup(server, phone, preferences, schedule, clock).block()
            }
        } finally {
            database.close()
            profiles.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun ServerWardrobe.addGarment() = garmentForm.save(
        garmentId = null,
        form = GarmentFormState(imageUris = listOf(photos.store(jpeg())), bgRemovedUris = listOf(""), category = "tops", colorPalette = listOf("#000000"), colorsChosen = true),
        previouslyStored = emptyList(),
    )

    @Test
    fun `a wrong code pairs nothing`() = phoneTest {
        assertEquals(SyncFailure.NotPaired, phone.connect(address, "AAAAA-BBBBB-CCCCC-DDDDD"))

        assertFalse(phone.status.value.paired)
        assertNull(preferences.code)
        assertNull(schedule.scheduled, "a phone that is not paired was scheduled to sync")
    }

    @Test
    fun `pairing stores the code and schedules the background sync`() = phoneTest {
        assertNull(phone.connect(address, " ${server.syncSecret.current()} "))

        assertTrue(phone.status.value.paired)
        assertEquals(server.syncSecret.current(), preferences.code, "the code was not trimmed")
        assertEquals(true, schedule.scheduled)
        assertEquals(true, schedule.wifiOnly)
    }

    @Test
    fun `a sync is recorded, and tells the screens when it changed something`() = phoneTest {
        phone.connect(address, server.syncSecret.current())
        server.addGarment()

        assertNull(phone.sync())

        assertEquals("2026-09-21T14:13:20.000Z", phone.status.value.lastSyncedAt)
        assertNull(phone.status.value.lastFailure)
        assertFalse(phone.status.value.syncing)
        assertEquals(1, phone.changes.value)

        // Nothing new: recorded, but the screens have nothing to read again.
        clock.millis += 60_000
        assertNull(phone.sync())
        assertEquals("2026-09-21T14:14:20.000Z", phone.status.value.lastSyncedAt)
        assertEquals(1, phone.changes.value)
    }

    @Test
    fun `a sync that fails keeps when the last one worked, and says why`() = phoneTest {
        phone.connect(address, server.syncSecret.current())
        phone.sync()
        val worked = phone.status.value.lastSyncedAt
        assertNotNull(worked)

        server.syncSecret.reset()
        assertEquals(SyncFailure.NotPaired, phone.sync())

        assertEquals(worked, phone.status.value.lastSyncedAt)
        assertEquals(SyncFailure.NotPaired, phone.status.value.lastFailure)
        assertTrue(phone.status.value.paired, "a refused code unpaired the phone; the person decides that")
    }

    @Test
    fun `opening the app syncs, but not twice in a minute, and not on mobile data when told`() = phoneTest {
        assertNull(phone.syncOnOpen(metered = false), "an unpaired phone tried to sync")
        assertNull(phone.status.value.lastSyncedAt)

        phone.connect(address, server.syncSecret.current())
        phone.syncOnOpen(metered = true)
        assertNull(phone.status.value.lastSyncedAt, "synced on mobile data while told to wait for Wi-Fi")

        phone.syncOnOpen(metered = false)
        val first = phone.status.value.lastSyncedAt
        assertNotNull(first)

        clock.millis += 30_000
        phone.syncOnOpen(metered = false)
        assertEquals(first, phone.status.value.lastSyncedAt, "opening the app again at once synced again")

        clock.millis += 5 * 60_000
        phone.syncOnOpen(metered = false)
        assertTrue(phone.status.value.lastSyncedAt!! > first)

        // Allowed on mobile data, it syncs there too.
        phone.setWifiOnly(false)
        clock.millis += 5 * 60_000
        val before = phone.status.value.lastSyncedAt!!
        phone.syncOnOpen(metered = true)
        assertTrue(phone.status.value.lastSyncedAt!! > before)
    }

    @Test
    fun `the switches reschedule, and stopping cancels`() = phoneTest {
        phone.connect(address, server.syncSecret.current())

        phone.setWifiOnly(false)
        assertEquals(false, schedule.wifiOnly)

        phone.setBackground(false)
        assertEquals(false, schedule.scheduled)
        assertNull(phone.syncInBackground())
        assertNull(phone.status.value.lastSyncedAt, "the worker synced with background sync off")

        phone.setBackground(true)
        assertEquals(true, schedule.scheduled)
        phone.syncInBackground()
        assertNotNull(phone.status.value.lastSyncedAt)

        phone.disconnect()
        assertEquals(false, schedule.scheduled)
        assertFalse(phone.status.value.paired)
        assertNull(preferences.code)
        // The switches are the person's, and outlive one pairing.
        assertFalse(phone.status.value.wifiOnly)
    }

    @Test
    fun `the phone's own client syncs with a server listening on a real port`() {
        // Everything above goes through Ktor's in-memory test client. This is the
        // client the phone builds -- CIO, its timeouts, the code in a header --
        // over a socket, to the server as Main starts it.
        val directory = Files.createTempDirectory("phone-sync-socket").toFile()
        val profiles = ProfileRegistry(File(directory, "server"))
        val wardrobe = profiles.wardrobe(ProfileRegistry.FIRST)!!
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(CIO, port = port) { wardrobeSync(profiles, ServerVersion("0.2.0", 9)) }.start(wait = false)
        val database = JdbcSqlDriver.open(File(directory, "phone.db")).also { WardrobeSchema.applyTo(it) }
        try {
            runBlocking {
                wardrobe.addGarment()
                val phone = PhoneSync(
                    preferences = Preferences(),
                    store = SyncStore(database),
                    photos = DirectoryPhotoFolder(File(directory, "phone-photos")),
                    background = Schedule(),
                )

                assertEquals(SyncFailure.NotPaired, phone.connect("http://127.0.0.1:$port/", "AAAAA-BBBBB-CCCCC-DDDDD"))
                assertNull(phone.connect("http://127.0.0.1:$port/", wardrobe.syncSecret.current().lowercase()))
                assertNull(phone.sync())

                assertEquals(1, SyncStore(database).snapshot().garments.size)
                assertEquals(1, File(directory, "phone-photos").list()?.size)
            }
        } finally {
            server.stop(0, 0)
            database.close()
            profiles.close()
            directory.deleteRecursively()
        }
    }
}
