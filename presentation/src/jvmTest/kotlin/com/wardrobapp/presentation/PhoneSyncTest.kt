package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PhoneSyncTest {

    @Test
    fun `an address is read the way people type it`() {
        val cases = mapOf(
            "homeassistant.local" to "http://homeassistant.local:8100/",
            "  192.168.1.20:8100 " to "http://192.168.1.20:8100/",
            "http://HomeAssistant.Local:9000" to "http://homeassistant.local:9000/",
            "HTTP://ha.lan" to "http://ha.lan:8100/",
            // Pasted from the browser, ingress path and all.
            "http://homeassistant.local:8123/hassio/ingress/abc_wardrobapp" to "http://homeassistant.local:8123/",
            // Behind a reverse proxy, where no port means the scheme's own.
            "https://wardrobe.example.org" to "https://wardrobe.example.org/",
            "https://wardrobe.example.org:8443/" to "https://wardrobe.example.org:8443/",
            "[fd00::20]" to "http://[fd00::20]:8100/",
            "http://[fd00::20]:9000/" to "http://[fd00::20]:9000/",
        )
        for ((typed, expected) in cases) assertEquals(expected, syncAddressOf(typed), typed)
    }

    @Test
    fun `what is not an address is refused`() {
        val refused = listOf(
            "",
            "   ",
            "ftp://homeassistant.local",
            "homeassistant local",
            "user@homeassistant.local",
            "http://user:password@homeassistant.local",
            "homeassistant.local:http",
            "homeassistant.local:70000",
            "homeassistant.local:0",
            "fd00::20",
            "[fd00::20",
            "http://",
            "-ha.local",
        )
        for (typed in refused) assertNull(syncAddressOf(typed), typed)
    }

    @Test
    fun `a failure survives being written down`() {
        val failures = listOf(SyncFailure.NotPaired, SyncFailure.Unreachable, SyncFailure.ServerTooOld, SyncFailure.Other("Disk full: photo.jpg"))
        for (failure in failures) assertEquals(failure, syncFailureFor(storedSyncFailure(failure)))
        assertNull(syncFailureFor(null))
        // Something a later build wrote is still a failure, not a success.
        assertEquals(SyncFailure.Other("timed-out"), syncFailureFor("timed-out"))
    }

    private class FakeSource : PhoneSyncSource {
        override val status = MutableStateFlow(PhoneSyncStatus())
        var refuse: SyncFailure? = null
        var connectedWith: Pair<String, String>? = null
        var syncs = 0
        var syncGate: CompletableDeferred<Unit>? = null

        override suspend fun connect(address: String, code: String): SyncFailure? {
            refuse?.let { return it }
            connectedWith = address to code
            status.update { it.copy(address = address) }
            return null
        }

        override suspend fun sync(): SyncFailure? {
            syncs++
            status.update { it.copy(syncing = true) }
            syncGate?.await()
            status.update { it.copy(syncing = false, lastSyncedAt = "2026-10-03T10:00:00.000Z") }
            return null
        }

        override fun disconnect() = status.update { PhoneSyncStatus() }
        override fun setBackground(enabled: Boolean) = status.update { it.copy(background = enabled) }
        override fun setWifiOnly(enabled: Boolean) = status.update { it.copy(wifiOnly = enabled) }
    }

    @Test
    fun `pairing syncs straight away`() = runTest {
        val source = FakeSource()
        val model = PhoneSyncModel(backgroundScope, source)

        model.onConnect("homeassistant.local", "ABCDE-FGHIJ-KMNPQ-RSTVW")
        runCurrent()

        assertEquals("http://homeassistant.local:8100/" to "ABCDE-FGHIJ-KMNPQ-RSTVW", source.connectedWith)
        assertEquals(1, source.syncs)
        assertTrue(model.state.value.status.paired)
        assertEquals("2026-10-03T10:00:00.000Z", model.state.value.status.lastSyncedAt)
        assertFalse(model.state.value.connecting)
    }

    @Test
    fun `an address that is not one is said so, and nothing is asked`() = runTest {
        val source = FakeSource()
        val model = PhoneSyncModel(backgroundScope, source)

        model.onConnect("ftp://nope", "ABCDE")
        runCurrent()

        assertTrue(model.state.value.addressInvalid)
        assertNull(source.connectedWith)

        model.onFormEdited()
        runCurrent()
        assertFalse(model.state.value.addressInvalid)
    }

    @Test
    fun `a code turned down stays unpaired, and says why`() = runTest {
        val source = FakeSource().apply { refuse = SyncFailure.NotPaired }
        val model = PhoneSyncModel(backgroundScope, source)

        model.onConnect("homeassistant.local", "wrong")
        runCurrent()

        assertEquals(SyncFailure.NotPaired, model.state.value.connectFailure)
        assertFalse(model.state.value.status.paired)
        assertEquals(0, source.syncs)
    }

    @Test
    fun `sync now while a sync is running does not start another`() = runTest {
        val source = FakeSource().apply { status.value = PhoneSyncStatus(address = "http://ha:8100/") }
        val gate = CompletableDeferred<Unit>()
        source.syncGate = gate
        val model = PhoneSyncModel(backgroundScope, source)

        model.onSyncNow()
        runCurrent()
        assertTrue(model.state.value.status.syncing)
        model.onSyncNow()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, source.syncs)
        assertFalse(model.state.value.status.syncing)
    }

    @Test
    fun `stopping forgets the pairing and the form`() = runTest {
        val source = FakeSource().apply { refuse = SyncFailure.Unreachable }
        val model = PhoneSyncModel(backgroundScope, source)
        model.onConnect("ha.lan", "code")
        runCurrent()
        source.refuse = null
        model.onConnect("ha.lan", "code")
        runCurrent()

        model.onDisconnect()
        runCurrent()

        assertEquals(PhoneSyncState(), model.state.value)
    }
}
