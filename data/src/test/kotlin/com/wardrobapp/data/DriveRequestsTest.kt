package com.wardrobapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What this app asks Drive for.
 *
 * A malformed query does not fail loudly here. Drive answers a *different*
 * question and hands back a listing that looks entirely reasonable -- so a
 * quoting mistake shows up as a prune deleting out of the wrong folder, which is
 * the kind of thing that is only noticed once the files are gone.
 */
class DriveRequestsTest {

    @Test
    fun `listing asks for one folder and skips the bin`() {
        val url = driveListUrl("folder-1")

        // Percent-encoded, so the assertions are on the decoded intent rather
        // than on one particular encoder's output.
        val query = decodedQueryParam(url, "q")
        assertEquals("'folder-1' in parents and trashed = false", query)
        assertTrue(url.startsWith("$DRIVE_API_BASE/files?"))
    }

    @Test
    fun `listing asks only for the fields it reads`() {
        val fields = decodedQueryParam(driveListUrl("folder-1"), "fields")

        assertEquals("files(id,name,modifiedTime,size)", fields)
    }

    @Test
    fun `an apostrophe in a value cannot end the query early`() {
        // The whole reason quoting is a function. A folder id or name carrying an
        // apostrophe would otherwise close the string and turn the remainder into
        // syntax -- rejected if you are lucky, a different question if not.
        val query = decodedQueryParam(driveListUrl("o'brien"), "q")

        assertEquals("""'o\'brien' in parents and trashed = false""", query)
    }

    @Test
    fun `a backslash in a value is escaped before the apostrophes are`() {
        // Order matters: escaping apostrophes first and backslashes second would
        // double the backslash this step just added.
        val query = decodedQueryParam(driveListUrl("""back\slash"""), "q")

        assertEquals("""'back\\slash' in parents and trashed = false""", query)
    }

    @Test
    fun `finding the folder asks for a folder by name, not in the bin`() {
        val query = decodedQueryParam(driveFindFolderUrl(), "q")

        assertEquals(
            "name = 'Wardrobapp' and " +
                "mimeType = 'application/vnd.google-apps.folder' and " +
                "trashed = false",
            query,
        )
    }

    @Test
    fun `a page size outside what Drive accepts is brought back inside it`() {
        assertTrue(driveListUrl("f", pageSize = 0).endsWith("pageSize=1"))
        assertTrue(driveListUrl("f", pageSize = -10).endsWith("pageSize=1"))
        assertTrue(driveListUrl("f", pageSize = 99999).endsWith("pageSize=1000"))
        assertTrue(driveListUrl("f", pageSize = 50).endsWith("pageSize=50"))
    }

    @Test
    fun `uploads go to the upload host and ask to be resumable`() {
        val url = driveUploadUrl()

        assertTrue(url.startsWith("$DRIVE_UPLOAD_BASE/files?"))
        assertTrue(url.contains("uploadType=resumable"))
    }

    @Test
    fun `downloading asks for the bytes rather than the description`() {
        assertEquals("$DRIVE_API_BASE/files/abc123?alt=media", driveDownloadUrl("abc123"))
    }

    @Test
    fun `an id in a path is encoded as a path, not as a form field`() {
        // URLEncoder writes a space as '+', which in a path is a literal plus.
        assertFalse(driveDownloadUrl("a b").contains("+"))
        assertTrue(driveDownloadUrl("a b").contains("a%20b"))
    }

    @Test
    fun `upload metadata names the file, its type and where it goes`() {
        val body = driveUploadMetadata("wardrobapp-backup-x.zip", "folder-1")

        assertEquals(
            """{"name":"wardrobapp-backup-x.zip","mimeType":"application/zip","parents":["folder-1"]}""",
            body,
        )
    }

    @Test
    fun `folder metadata asks for a folder`() {
        assertEquals(
            """{"name":"Wardrobapp","mimeType":"application/vnd.google-apps.folder"}""",
            driveFolderMetadata(),
        )
    }

    @Test
    fun `an id comes back out of what Drive returns`() {
        assertEquals("1xyz", parseDriveFileId("""{"id": "1xyz", "name": "x.zip"}"""))
        assertEquals(null, parseDriveFileId("""{"id": ""}"""))
        assertEquals(null, parseDriveFileId("""{"name": "x.zip"}"""))
        assertEquals(null, parseDriveFileId("not json"))
        assertEquals(null, parseDriveFileId(""))
    }

    @Test
    fun `a folder that does not exist yet reads as none`() {
        assertEquals("f1", parseDriveFolderId("""{"files":[{"id":"f1","name":"Wardrobapp"}]}"""))
        assertEquals(null, parseDriveFolderId("""{"files":[]}"""))
        assertEquals(null, parseDriveFolderId("""{}"""))
        assertEquals(null, parseDriveFolderId("nonsense"))
    }

    @Test
    fun `an upload session address is accepted only on Google's domain`() {
        assertTrue(isTrustedDriveEndpoint("https://www.googleapis.com/upload/drive/v3/files?upload_id=x"))
        assertTrue(isTrustedDriveEndpoint("https://googleapis.com/upload"))
        // Whichever subdomain Google hands the session out on.
        assertTrue(isTrustedDriveEndpoint("https://upload-eu.googleapis.com/x"))
    }

    @Test
    fun `an address that merely looks like Google's is refused`() {
        // The dot is the whole test: a name ending in the string without the dot
        // is a different domain that anybody can register.
        assertFalse(isTrustedDriveEndpoint("https://evilgoogleapis.com/upload"))
        assertFalse(isTrustedDriveEndpoint("https://googleapis.com.attacker.test/upload"))
        assertFalse(isTrustedDriveEndpoint("https://attacker.test/googleapis.com"))
    }

    @Test
    fun `an upload session must be https and must not carry a userinfo`() {
        assertFalse(isTrustedDriveEndpoint("http://www.googleapis.com/upload"))
        // Userinfo is how one host is made to read as another.
        assertFalse(isTrustedDriveEndpoint("https://www.googleapis.com@attacker.test/upload"))
        assertFalse(isTrustedDriveEndpoint(""))
        assertFalse(isTrustedDriveEndpoint("www.googleapis.com/upload"))
    }

    @Test
    fun `a token is spent before it expires, not after`() {
        val expiry = 1_000_000L

        assertFalse(accessTokenExpired(expiry, nowMillis = expiry - 120_000))
        // Inside the skew: still valid by the clock, treated as spent, because a
        // token that dies mid-request fails somewhere less convenient.
        assertTrue(accessTokenExpired(expiry, nowMillis = expiry - 30_000))
        assertTrue(accessTokenExpired(expiry, nowMillis = expiry))
        assertTrue(accessTokenExpired(expiry, nowMillis = expiry + 1))
    }

    @Test
    fun `a token with no known expiry is treated as expired`() {
        // Refreshing needlessly costs one request. Using a dead token costs the
        // backup somebody asked for.
        assertTrue(accessTokenExpired(null, nowMillis = 0))
    }

    /** The decoded value of one query parameter, so assertions read as intent. */
    private fun decodedQueryParam(url: String, name: String): String {
        val raw = url.substringAfter("?")
            .split("&")
            .first { it.startsWith("$name=") }
            .substringAfter("=")

        return java.net.URLDecoder.decode(raw, "UTF-8")
    }
}
