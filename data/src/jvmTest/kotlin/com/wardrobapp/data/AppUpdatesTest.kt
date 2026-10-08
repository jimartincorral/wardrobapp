package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the published document and deciding whether to say anything.
 *
 * Two things could go wrong here and neither would be visible on a phone until it
 * mattered: a document that stops parsing means updates silently stop being
 * offered, and a comparison that is off by one means the app offers the build it is
 * already running, every launch, forever. The third is not silent but is worse: a
 * download address from somewhere that is not this project.
 */
class AppUpdatesTest {

    /** The release as published: an offer carries its notes as well, which these tests are not about. */
    private fun AppRelease.withoutNotes() = copy(notes = emptyList())

    private val published = """
        {
          "version_code": 1120,
          "version_name": "1.1.0",
          "commit": "d2b2e7407e5ef8925343655f95afa1939196e0f8",
          "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
          "apk_sha256": "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
          "changes": ["Read a garment's colours by themselves", "Remove the garment-type suggestions"]
        }
    """.trimIndent()

    @Test
    fun `a published document reads as a release`() {
        val release = parseAppRelease(published)

        assertEquals(1120L, release?.versionCode)
        assertEquals("1.1.0", release?.versionName)
        assertEquals(2, release?.changes?.size)
        assertEquals("Read a garment's colours by themselves", release?.changes?.first())
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", release?.apkSha256)
    }

    @Test
    fun `a hash that is not a SHA-256 is no hash, and a document without one is still a document`() {
        // The download refuses to go ahead without a hash; reading the
        // document is also how the phone learns what is new, so a document
        // short of one still reads.
        val bare = """{"version_code": 9, "apk_url": "https://github.com/a/b/releases/download/nightly/c.apk"}"""
        assertNull(parseAppRelease(bare)?.apkSha256)
        val short = """{"version_code": 9, "apk_url": "https://github.com/a/b/releases/download/nightly/c.apk", "apk_sha256": "abc123"}"""
        assertNull(parseAppRelease(short)?.apkSha256)
        assertEquals(9L, parseAppRelease(short)?.versionCode)
    }

    @Test
    fun `a version code written as a string is still a version code`() {
        // The document is written by a shell script, and one quoting accident makes
        // every field a string. Refusing to read that would turn every future
        // phone silent, which is a poor trade for strictness about a number.
        val quoted = """{"version_code": "1120", "apk_url": "https://github.com/x/y/releases/download/nightly/a.apk"}"""

        assertEquals(1120L, parseAppRelease(quoted)?.versionCode)
    }

    @Test
    fun `anything that is not a usable document is nothing at all`() {
        // All of these mean the same thing to the caller -- say nothing, check
        // again next time -- so all of them are null rather than four exceptions.
        assertNull(parseAppRelease("not json"), "unparseable text")
        assertNull(parseAppRelease("[]"), "not an object")
        assertNull(parseAppRelease("{}"), "no version code")
        assertNull(parseAppRelease("""{"version_code": 0, "apk_url": "https://github.com/a.apk"}"""), "no build is 0")
        assertNull(parseAppRelease("""{"version_code": 12}"""), "nowhere to download from")
    }

    @Test
    fun `a missing name or changelog is not a missing release`() {
        // The document is generated, and the changelog can genuinely be empty --
        // the first build published after this ships has nothing to compare with.
        val bare = """{"version_code": 9, "apk_url": "https://github.com/a/b/releases/download/nightly/c.apk"}"""
        val release = parseAppRelease(bare)

        assertEquals(9L, release?.versionCode)
        assertEquals("", release?.versionName)
        assertEquals(emptyList(), release?.changes)
    }

    @Test
    fun `a download address anywhere but this project's releases is refused`() {
        assertTrue(isTrustedDownload("https://github.com/o/r/releases/download/nightly/wardrobapp.apk"))
        assertTrue(isTrustedDownload("https://objects.githubusercontent.com/github-production-release-asset/1/2"))

        // The address is compared as a host, not as text: this one contains
        // "github.com" and is a different site.
        assertFalse(isTrustedDownload("https://github.com.example.invalid/wardrobapp.apk"), "lookalike host")
        assertFalse(isTrustedDownload("http://github.com/o/r/a.apk"), "cleartext")
        assertFalse(isTrustedDownload("https://example.invalid/wardrobapp.apk"), "another host")
        // Credentials in the address are how one host is made to look like
        // another in something a person reads before tapping install.
        assertFalse(isTrustedDownload("https://github.com@example.invalid/a.apk"), "userinfo")
        assertFalse(isTrustedDownload("file:///data/local/tmp/a.apk"), "not even a fetch")
        assertFalse(isTrustedDownload("github.com/o/r/a.apk"), "no scheme")

        // And a document naming one is not a document, so nothing downstream has
        // to remember to check it again.
        assertNull(parseAppRelease("""{"version_code": 12, "apk_url": "https://example.invalid/a.apk"}"""))
    }

    @Test
    fun `only a build newer than this one is worth mentioning`() {
        val release = parseAppRelease(published)!!

        assertEquals(release, updateWorthOffering(installed = 1119, skipped = 0, release = release)?.withoutNotes())
        assertNull(updateWorthOffering(installed = 1120, skipped = 0, release = release), "the build it is running")
        assertNull(updateWorthOffering(installed = 1121, skipped = 0, release = release), "an older published build")
        assertNull(updateWorthOffering(installed = 1, skipped = 0, release = null), "nothing was read")
    }

    @Test
    fun `skipping a build hides that one, not every one after it`() {
        val release = parseAppRelease(published)!!

        assertNull(updateWorthOffering(installed = 1000, skipped = 1120, release = release), "the skipped build")
        assertNull(updateWorthOffering(installed = 1000, skipped = 1200, release = release), "and anything older")

        // But the next build is a new decision, not a settled one.
        assertEquals(release, updateWorthOffering(installed = 1000, skipped = 1119, release = release)?.withoutNotes())
    }

    /*
     * The changelog since the phone's own build. Every merge publishes a build,
     * and each build's document used to carry only its own notes -- so a phone
     * two builds behind was told about the newest change and nothing before it.
     */

    private val withHistory = """
        {
          "version_code": 1123,
          "version_name": "1.1.0",
          "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
          "changes": ["Outfits keep their rating when edited."],
          "history": [
            {"build": 1123, "text": "Outfits keep their rating when edited."},
            {"build": 1122, "text": "The grid remembers how many columns you chose."},
            {"build": 1120, "text": "Photos import the right way up."},
            {"build": 1120, "text": "Brands sort the way you read them."}
          ]
        }
    """.trimIndent()

    @Test
    fun `history is read with the build each note arrived in`() {
        val release = parseAppRelease(withHistory)!!

        assertEquals(4, release.history.size)
        assertEquals(ReleaseNote(1122, "The grid remembers how many columns you chose."), release.history[1])
    }

    @Test
    fun `a phone several builds behind is told everything since its own build`() {
        val offered = updateWorthOffering(installed = 1119, skipped = 0, release = parseAppRelease(withHistory))!!

        assertEquals(
            listOf(
                "Outfits keep their rating when edited.",
                "The grid remembers how many columns you chose.",
                "Photos import the right way up.",
                "Brands sort the way you read them.",
            ),
            offered.changes,
        )
    }

    @Test
    fun `a phone is not told about the builds it already has`() {
        val offered = updateWorthOffering(installed = 1120, skipped = 0, release = parseAppRelease(withHistory))!!

        assertEquals(
            listOf("Outfits keep their rating when edited.", "The grid remembers how many columns you chose."),
            offered.changes,
        )
    }

    @Test
    fun `a skipped build does not hide its notes from the next offer`() {
        // Skipping 1122 was "not that one", and the phone is still on 1119: what
        // 1122 brought is still something installing 1123 would bring.
        val offered = updateWorthOffering(installed = 1119, skipped = 1122, release = parseAppRelease(withHistory))!!

        assertTrue("The grid remembers how many columns you chose." in offered.changes)
    }

    @Test
    fun `a document from before history existed keeps its own changes`() {
        // Every document published until this change -- and the one the first
        // build after it replaces -- has no history. Its changes are the best
        // there is, and must not be thrown away for want of a build number.
        val offered = updateWorthOffering(installed = 1000, skipped = 0, release = parseAppRelease(published))!!

        assertEquals(listOf("Read a garment's colours by themselves", "Remove the garment-type suggestions"), offered.changes)
    }

    @Test
    fun `when nothing since the phone's build had a note, the published line still says so`() {
        // Builds whose merges all said "Release-Note: none" carry no history
        // entries; the published changes then hold the sentence saying there is
        // nothing to notice, and that is better than an empty dialog.
        val quiet = """
            {
              "version_code": 1125,
              "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
              "changes": ["Fixes and groundwork. Nothing you should notice."],
              "history": [{"build": 1120, "text": "Photos import the right way up."}]
            }
        """.trimIndent()

        val offered = updateWorthOffering(installed = 1123, skipped = 0, release = parseAppRelease(quiet))!!

        assertEquals(listOf("Fixes and groundwork. Nothing you should notice."), offered.changes)
    }

    @Test
    fun `a note claiming a build newer than the one offered is not believed`() {
        val ahead = withHistory.replace(""""build": 1123""", """"build": 1999""")

        val offered = updateWorthOffering(installed = 1121, skipped = 0, release = parseAppRelease(ahead))!!

        assertEquals(listOf("The grid remembers how many columns you chose."), offered.changes)
    }

    @Test
    fun `a malformed history entry is a line fewer, not a release fewer`() {
        val messy = """
            {
              "version_code": 1123,
              "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
              "changes": ["Kept."],
              "history": [
                {"build": "1123", "text": "A build number written as a string still counts."},
                {"build": 1122},
                {"text": "No build at all."},
                {"build": 1121, "text": "   "},
                {"build": 1121, "text": 42},
                "not an entry",
                {"build": 1120, "text": "Kept too."}
              ]
            }
        """.trimIndent()

        val release = parseAppRelease(messy)

        assertEquals(
            listOf(
                ReleaseNote(1123, "A build number written as a string still counts."),
                ReleaseNote(1120, "Kept too."),
            ),
            release?.history,
        )
    }

    @Test
    fun `the same line arriving in two builds is told once`() {
        val repeated = """
            {
              "version_code": 1123,
              "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
              "changes": ["Photos import the right way up."],
              "history": [
                {"build": 1123, "text": "Photos import the right way up."},
                {"build": 1121, "text": "Photos import the right way up."}
              ]
            }
        """.trimIndent()

        val offered = updateWorthOffering(installed = 1100, skipped = 0, release = parseAppRelease(repeated))!!

        assertEquals(listOf("Photos import the right way up."), offered.changes)
    }

    /*
     * Notes with a kind, the apps they are about, Spanish and a destination --
     * scripts/release-notes.py's grammar, as published.
     */

    private val structured = """
        {
          "version_code": 1130,
          "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
          "changes": ["Sync with Home Assistant."],
          "history": [
            {"build": 1130, "text": "Sync with Home Assistant.", "kind": "new", "platforms": ["android"],
             "text_es": "Sincroniza con Home Assistant.", "to": "settings"},
            {"build": 1130, "text": "The browser keeps its place.", "kind": "fixed", "platforms": ["web"]},
            {"build": 1129, "text": "Both apps.", "kind": "improved", "platforms": ["android", "web"]},
            {"build": 1128, "text": "From a later build of the script.", "kind": "sparkly", "platforms": ["watch"], "to": "somewhere"}
          ]
        }
    """.trimIndent()

    @Test
    fun `a note's kind, apps, Spanish and destination are read`() {
        val history = parseAppRelease(structured)!!.history

        assertEquals(
            ReleaseNote(
                build = 1130,
                text = "Sync with Home Assistant.",
                kind = ReleaseNoteKind.NEW,
                platforms = setOf(ReleasePlatform.ANDROID),
                textEs = "Sincroniza con Home Assistant.",
                destination = "settings",
            ),
            history[0],
        )
        assertEquals(setOf(ReleasePlatform.ANDROID, ReleasePlatform.WEB), history[2].platforms)
        // What this build does not know costs the detail, not the note -- and a
        // note naming no app it knows is about the phone, as old ones were.
        assertEquals(ReleaseNoteKind.IMPROVED, history[3].kind)
        assertEquals(setOf(ReleasePlatform.ANDROID), history[3].platforms)
        assertEquals("somewhere", history[3].destination)
    }

    @Test
    fun `a note from before the grammar is an improvement to the phone`() {
        val note = parseAppRelease(withHistory)!!.history[0]

        assertEquals(ReleaseNoteKind.IMPROVED, note.kind)
        assertEquals(setOf(ReleasePlatform.ANDROID), note.platforms)
        assertNull(note.textEs)
        assertNull(note.destination)
    }

    @Test
    fun `a phone is not told about the browser`() {
        val offer = updateWorthOffering(installed = 1127, skipped = 0, release = parseAppRelease(structured))!!

        assertEquals(
            listOf("Sync with Home Assistant.", "Both apps.", "From a later build of the script."),
            offer.changes,
        )
        assertEquals(offer.changes, offer.notes.map { it.text })
        assertEquals("Sincroniza con Home Assistant.", offer.notes[0].textEs)
    }

    @Test
    fun `when only the browser changed, the published line still speaks for the phone`() {
        val browserOnly = parseAppRelease(structured)!!.copy(
            history = listOf(ReleaseNote(1130, "Browser only.", platforms = setOf(ReleasePlatform.WEB))),
            changes = listOf("Fixes and groundwork. Nothing you should notice."),
        )

        val offer = updateWorthOffering(installed = 1129, skipped = 0, release = browserOnly)!!

        assertEquals(listOf("Fixes and groundwork. Nothing you should notice."), offer.changes)
        assertEquals(listOf(ReleaseNote(1130, "Fixes and groundwork. Nothing you should notice.")), offer.notes)
    }
}
