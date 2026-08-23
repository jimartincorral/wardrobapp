package com.wardrobapp.parity

import com.wardrobapp.parity.Parity.double
import com.wardrobapp.parity.Parity.optionalDouble
import com.wardrobapp.parity.Parity.sameNumber
import com.wardrobapp.parity.Parity.string
import com.wardrobapp.parity.Parity.strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the parity machinery itself.
 *
 * Every parity suite in the port decides whether it passed by calling into here,
 * so a fault in this file would be invisible in all of them at once -- a
 * comparison that always agreed would make thousands of assertions vacuous.
 */
class ParityTest {

    @Test
    fun `absent and zero are not the same number`() {
        // The distinction the whole abstention design rests on: a signal with no
        // data must not compare equal to one that scored zero. If this returned
        // true, every suite would accept exactly the divergence it exists to
        // catch.
        assertFalse(sameNumber(null, 0.0), "null must not equal 0.0")
        assertFalse(sameNumber(0.0, null), "0.0 must not equal null")
    }

    @Test
    fun `two absent values agree`() {
        assertTrue(sameNumber(null, null))
    }

    @Test
    fun `numbers agree within the tolerance and not outside it`() {
        assertTrue(sameNumber(1.0, 1.0))
        assertTrue(sameNumber(1.0, 1.0 + Parity.TOLERANCE / 2))
        assertFalse(sameNumber(1.0, 1.0 + Parity.TOLERANCE * 10))

        // Tight enough that no difference it admits could change a decision:
        // every threshold in the port is orders of magnitude coarser than this.
        assertTrue(Parity.TOLERANCE < 1e-6, "tolerance ${Parity.TOLERANCE} is too loose to be meaningful")
    }

    @Test
    fun `a missing fixture fails loudly rather than reading as empty`() {
        val error = assertFails { Parity.load("does-not-exist.jsonl") }

        // The message has to say how to fix it: a stale checkout is the most
        // likely cause and regenerating is the remedy.
        assertTrue(
            error.message?.contains("parity:dump") == true,
            "the failure should name the command that regenerates fixtures, got: ${error.message}"
        )
    }

    @Test
    fun `a fixture is parsed line by line`() {
        val cases = Parity.load("sample.jsonl")

        assertEquals(2, cases.size)
        assertEquals("first", cases[0].string("name"))
        assertEquals(1.5, cases[0].double("value"))
        assertEquals(listOf("a", "b"), cases[0].strings("items"))
        assertEquals(emptyList(), cases[1].strings("items"))
    }

    @Test
    fun `a null in the fixture reads as absent, not as zero`() {
        val cases = Parity.load("sample.jsonl")

        assertNull(cases[0].optionalDouble("absent"))
        assertEquals(0.0, cases[1].double("value"))

        // Asking for a required number where the fixture holds null is a
        // mistake in the test, so it fails rather than defaulting.
        assertFails { cases[0].double("absent") }
    }

    @Test
    fun `a missing key fails rather than reading as absent`() {
        val cases = Parity.load("sample.jsonl")

        // "the fixture says null" and "the fixture has no such field" are
        // different problems, and only the first is a legitimate value.
        assertFails { cases[0].optionalDouble("no-such-field") }
        assertFails { cases[0].string("no-such-field") }
    }
}
