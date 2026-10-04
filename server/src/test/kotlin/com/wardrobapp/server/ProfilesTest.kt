package com.wardrobapp.server

import com.wardrobapp.api.HttpHomeSource
import com.wardrobapp.api.HttpProfiles
import com.wardrobapp.api.NotFoundException
import com.wardrobapp.api.Routes
import com.wardrobapp.api.ServerException
import com.wardrobapp.api.ServerVersion
import com.wardrobapp.api.WardrobeSyncClient
import com.wardrobapp.api.DirectoryPhotoFolder
import com.wardrobapp.api.speakSync
import com.wardrobapp.api.speakWardrobe
import com.wardrobapp.data.GarmentWrites
import com.wardrobapp.data.JdbcSqlDriver
import com.wardrobapp.data.SyncStore
import com.wardrobapp.data.WardrobeSchema
import com.wardrobapp.presentation.GarmentFormState
import com.wardrobapp.presentation.HomeCounts
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * Several wardrobes in one Home Assistant app: the upgrade from one, keeping
 * every profile's wardrobe to itself, whose each Home Assistant user opens,
 * and a phone reaching the profile its code belongs to and no other.
 */
class ProfilesTest {

    private fun directory(): File = Files.createTempDirectory("profiles").toFile()

    private fun ApplicationTestBuilder.browser(user: String? = null, base: String = "http://localhost/") = createClient {
        speakWardrobe(base)
        // What Home Assistant's ingress sets on every request it forwards.
        if (user != null) defaultRequest { header("X-Remote-User-Id", user) }
    }

    private fun profilesTest(block: suspend ApplicationTestBuilder.(ProfileRegistry, File) -> Unit) {
        val data = directory()
        val profiles = ProfileRegistry(data)
        try {
            testApplication {
                application { wardrobeApi(profiles) }
                block(profiles, data)
            }
        } finally {
            profiles.close()
            data.deleteRecursively()
        }
    }

    @Test
    fun `the wardrobe there was becomes the first profile, where it is, code and all`() {
        val data = directory()
        try {
            // A server from before profiles: one wardrobe, one pairing code.
            val before = ServerWardrobe(data)
            val code = before.syncSecret.current()
            before.close()
            val databaseBefore = data.walk().filter { it.extension == "db" }.map { it.relativeTo(data).path }.toSet()

            val profiles = ProfileRegistry(data)

            assertEquals(listOf(ProfileRegistry.FIRST), profiles.list().map { it.id })
            assertEquals("", profiles.list().single().name, "named by somebody, not by the upgrade")
            assertEquals(profiles.wardrobe(ProfileRegistry.FIRST), profiles.pairedWith(code), "a paired phone stopped syncing")
            // Nothing moved.
            assertEquals(databaseBefore, data.walk().filter { it.extension == "db" }.map { it.relativeTo(data).path }.toSet())
            profiles.close()

            // And the same after a restart: the list is kept, not remade.
            val again = ProfileRegistry(data)
            assertEquals(listOf(ProfileRegistry.FIRST), again.list().map { it.id })
            again.close()
        } finally {
            data.deleteRecursively()
        }
    }

    @Test
    fun `each profile's wardrobe is its own`() = profilesTest { profiles, _ ->
        val ana = profiles.create("Ana", owner = null)

        val first = profiles.wardrobe(ProfileRegistry.FIRST)!!
        val photo = first.photos.store(jpeg())
        first.garmentForm.save(
            garmentId = null,
            form = GarmentFormState(imageUris = listOf(photo), bgRemovedUris = listOf(""), category = "tops", colorPalette = listOf("#000000"), colorsChosen = true),
            previouslyStored = emptyList(),
        )

        val home = { id: String -> HttpHomeSource(browser(base = "http://localhost/${Routes.profileBase(id)}")) }
        assertEquals(HomeCounts(items = 1, archived = 0, rated = 0), home(ProfileRegistry.FIRST).counts())
        assertEquals(HomeCounts(items = 0, archived = 0, rated = 0), home(ana.id).counts())

        // Photos are referenced under their own profile, and nowhere else.
        val ref = first.garmentForm.garment(first.sync.snapshot().garments.single().id)!!.imageUri
        assertTrue(ref.startsWith("p/main/photos/"), ref)
        assertFailsWith<NotFoundException> {
            browser().get(ref.replace("p/main/", "p/${ana.id}/"))
        }
    }

    @Test
    fun `a profile there is none of is not found`() = profilesTest { _, _ ->
        assertFailsWith<NotFoundException> {
            HttpHomeSource(browser(base = "http://localhost/${Routes.profileBase("nobody")}")).counts()
        }
    }

    @Test
    fun `each Home Assistant user opens their own, and can make another theirs`() = profilesTest { _, _ ->
        val jose = HttpProfiles(browser(user = "jose-id"))
        val ana = HttpProfiles(browser(user = "ana-id"))

        // Nobody has chosen yet: everybody is offered the list.
        assertNull(jose.list().yours)
        assertTrue(jose.list().signedIn)

        jose.makeYours(ProfileRegistry.FIRST)
        val anas = ana.create("Ana", yours = true)

        assertEquals(ProfileRegistry.FIRST, jose.list().yours)
        assertEquals(anas.id, ana.list().yours)
        assertEquals(listOf(ProfileRegistry.FIRST, anas.id), jose.list().profiles.map { it.id })

        // One profile per person: making another yours lets go of the first.
        jose.makeYours(anas.id)
        assertEquals(anas.id, jose.list().yours)
        assertEquals(anas.id, ana.list().yours, "sharing one is allowed")

        ana.rename(ProfileRegistry.FIRST, "  Family  ")
        assertEquals("Family", ana.list().profiles.first().name)
    }

    @Test
    fun `what cannot be a profile is refused`() = profilesTest { _, _ ->
        val anybody = HttpProfiles(browser())

        assertEquals(400, assertFailsWith<ServerException> { anybody.create("   ", yours = false) }.status)
        assertEquals(400, assertFailsWith<ServerException> { anybody.rename(ProfileRegistry.FIRST, "") }.status)
        assertFailsWith<NotFoundException> { anybody.rename("nobody", "Name") }
        // Nobody signed in: there is nobody to make it theirs.
        assertEquals(400, assertFailsWith<ServerException> { anybody.makeYours(ProfileRegistry.FIRST) }.status)
        assertEquals(false, anybody.list().signedIn)
    }

    @Test
    fun `a deleted profile is gone, files, code and all, and nobody's any more`() = profilesTest { profiles, data ->
        val jose = HttpProfiles(browser(user = "jose-id"))
        val kids = jose.create("Kids", yours = true)
        val wardrobe = profiles.wardrobe(kids.id)!!
        wardrobe.photos.store(jpeg())
        val code = wardrobe.syncSecret.current()
        assertTrue(File(data, "profiles/${kids.id}").isDirectory)

        jose.delete(kids.id)

        assertEquals(listOf(ProfileRegistry.FIRST), jose.list().profiles.map { it.id })
        assertNull(jose.list().yours, "still opening a wardrobe there is none of")
        assertFalse(File(data, "profiles/${kids.id}").exists())
        assertNull(profiles.pairedWith(code), "a phone with its code still syncs")
        assertFailsWith<NotFoundException> {
            HttpHomeSource(browser(base = "http://localhost/${Routes.profileBase(kids.id)}")).counts()
        }
        // A request that was holding it fails, rather than making an empty one where it was.
        assertFailsWith<IllegalStateException> { wardrobe.sync.snapshot() }
        assertFalse(File(data, "profiles/${kids.id}").exists())
        // And the list is kept that way.
        assertEquals(listOf(ProfileRegistry.FIRST), ProfileRegistry(data).list().map { it.id })
    }

    @Test
    fun `deleting the first profile deletes its files and nothing else in the data directory`() = profilesTest { profiles, data ->
        val anybody = HttpProfiles(browser())
        val first = profiles.wardrobe(ProfileRegistry.FIRST)!!
        first.photos.store(jpeg())
        first.syncSecret.current()
        first.sync.snapshot()
        val ana = anybody.create("Ana", yours = false)
        profiles.wardrobe(ana.id)!!.photos.store(jpeg())

        anybody.delete(ProfileRegistry.FIRST)

        assertEquals(listOf(ana.id), anybody.list().profiles.map { it.id })
        val left = data.walk().filter { it.isFile }.map { it.relativeTo(data).path }.toSet()
        assertEquals(setOf("profiles.json"), left.filter { !it.startsWith("profiles/") }.toSet(), "the first wardrobe's files stayed: $left")
        assertTrue(left.any { it.startsWith("profiles/${ana.id}/") }, "another profile's photo went with it: $left")
    }

    @Test
    fun `the only profile cannot be deleted, nor one there is none of`() = profilesTest { _, _ ->
        val anybody = HttpProfiles(browser())

        assertEquals(409, assertFailsWith<ServerException> { anybody.delete(ProfileRegistry.FIRST) }.status)
        assertFailsWith<NotFoundException> { anybody.delete("nobody") }
        assertEquals(listOf(ProfileRegistry.FIRST), anybody.list().profiles.map { it.id })
    }

    @Test
    fun `a phone reaches the profile its code belongs to, and no other`() {
        val data = directory()
        val profiles = ProfileRegistry(data)
        try {
            testApplication {
                application { wardrobeSync(profiles, ServerVersion("0.3.0", 1)) }

                val ana = profiles.create("Ana", owner = null)
                val anas = profiles.wardrobe(ana.id)!!
                val first = profiles.wardrobe(ProfileRegistry.FIRST)!!

                fun phone(name: String, code: String): Pair<WardrobeSyncClient, GarmentWrites> {
                    val database = JdbcSqlDriver.open(File(data, "$name.db")).also { WardrobeSchema.applyTo(it) }
                    val client = WardrobeSyncClient(
                        createClient { speakSync("http://localhost/", code) },
                        SyncStore(database),
                        DirectoryPhotoFolder(File(data, "$name-photos")),
                        Dispatchers.IO,
                    )
                    return client to GarmentWrites(database)
                }

                val (josePhone, joseGarments) = phone("jose", first.syncSecret.current())
                val (anaPhone, anaGarments) = phone("ana", anas.syncSecret.current())
                joseGarments.insert(newGarment("jose-shirt"))
                anaGarments.insert(newGarment("ana-dress"))

                josePhone.sync()
                anaPhone.sync()

                assertEquals(listOf("jose-shirt"), first.sync.snapshot().garments.map { it.id })
                assertEquals(listOf("ana-dress"), anas.sync.snapshot().garments.map { it.id })

                // A profile nobody has shown a code for has no phone that could know it.
                profiles.create("Kids", owner = null)
                assertEquals(401, assertFailsWith<ServerException> {
                    phone("stranger", "AAAAA-BBBBB-CCCCC-DDDDD").first.check()
                }.status)
            }
        } finally {
            profiles.close()
            data.deleteRecursively()
        }
    }

    private fun newGarment(id: String) = GarmentWrites.NewGarment(
        id = id,
        imageUri = "",
        imageUris = emptyList(),
        category = "tops",
        colorPrimary = "#112233",
        colorPalette = listOf("#112233"),
        now = "2026-10-03T10:00:00.000Z",
    )
}
