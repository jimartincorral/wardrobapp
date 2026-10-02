package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [asComposeWouldLoadIt] against the cases Compose Multiplatform 1.7 decides.
 *
 * Worth its own test because the parity tests lean on it and would pass
 * vacuously if it were wrong in the same direction as a resource -- and because
 * the one difference from Android that matters, `\'`, is a difference by
 * omission, which an unescaper written from memory gets wrong.
 */
class ComposeStringsTest {

    @Test
    fun `unicode escapes, newlines and tabs become what they stand for`() {
        assertEquals("“%1\$s” · rated", """\u201c%1${'$'}s\u201d \u00b7 rated""".asComposeWouldLoadIt())
        assertEquals("one\ntwo\tthree", """one\ntwo\tthree""".asComposeWouldLoadIt())
    }

    @Test
    fun `an escaped backslash is a backslash, and escapes nothing after it`() {
        assertEquals("""C:\new""", """C:\\new""".asComposeWouldLoadIt())
        assertEquals("""\u0041""", """\\u0041""".asComposeWouldLoadIt())
    }

    @Test
    fun `an escaped apostrophe keeps its backslash, as it does on screen`() {
        assertEquals("""SQLite\'s""", """SQLite\'s""".asComposeWouldLoadIt())
    }
}
