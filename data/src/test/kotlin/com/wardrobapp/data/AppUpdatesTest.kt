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

    private val published = """
        {
          "version_code": 1120,
          "version_name": "1.1.0",
          "commit": "d2b2e7407e5ef8925343655f95afa1939196e0f8",
          "apk_url": "https://github.com/jimartincorral/wardrobapp/releases/download/nightly/wardrobapp.apk",
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

        assertEquals(release, updateWorthOffering(installed = 1119, skipped = 0, release = release))
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
        assertEquals(release, updateWorthOffering(installed = 1000, skipped = 1119, release = release))
    }
}
