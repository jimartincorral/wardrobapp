package com.wardrobapp.presentation

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Properties of the rating summary that the parity fixture cannot see.
 *
 * The fixture is replayed on whatever JVM runs it, and that JVM's locale is
 * English -- so it compares the label without ever exercising the one thing about
 * the label that varies by device.
 */
class OutfitRatingTest {

    @Test
    fun `the label does not follow the device locale`() {
        val original = Locale.getDefault()
        try {
            // Half of Europe writes 4,5 for this number. The label is compared
            // against what the TypeScript's toFixed(1) produced, which is always
            // a dot, so following the device here would fail a fixture on a
            // Spanish phone and pass on the machine that generated it.
            //
            // Showing it the reader's way is a localization decision, and belongs
            // with the rest of localization rather than being smuggled in as a
            // formatting default.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("4.5", ratingSummary(listOf(4, 5)).label)
            assertEquals("3.0", ratingSummary(listOf(3)).label)
        } finally {
            Locale.setDefault(original)
        }
    }
}
