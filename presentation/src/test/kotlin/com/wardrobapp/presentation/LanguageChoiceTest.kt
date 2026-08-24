package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading a stored language tag.
 *
 * Worth testing away from the screen because every wrong answer here is quiet: a
 * Spanish user whose tag is `es-419` sees English and a picker claiming the app is
 * following the system.
 */
class LanguageChoiceTest {

    @Test
    fun `nothing stored means the device decides`() {
        assertEquals(LanguageChoice.SYSTEM, languageChoiceFor(""))
        assertEquals(LanguageChoice.SYSTEM, languageChoiceFor("   "))
    }

    @Test
    fun `a bare language tag is that language`() {
        assertEquals(LanguageChoice.ENGLISH, languageChoiceFor("en"))
        assertEquals(LanguageChoice.SPANISH, languageChoiceFor("es"))
    }

    @Test
    fun `a region does not make it a different language`() {
        // What AppCompatDelegate hands back on a device set to Latin American
        // Spanish, and what the React Native app's two-character slice also
        // treats as Spanish.
        assertEquals(LanguageChoice.SPANISH, languageChoiceFor("es-419"))
        assertEquals(LanguageChoice.SPANISH, languageChoiceFor("es-ES"))
        assertEquals(LanguageChoice.ENGLISH, languageChoiceFor("en-GB"))
        assertEquals(LanguageChoice.SPANISH, languageChoiceFor("es_MX"))
    }

    @Test
    fun `a list is read by its first entry`() {
        // LocaleListCompat.toLanguageTags() joins with commas when more than one
        // locale is set, and the first is the one in use.
        assertEquals(LanguageChoice.SPANISH, languageChoiceFor("es,en"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(LanguageChoice.SPANISH, languageChoiceFor("ES-es"))
    }

    @Test
    fun `a language this app does not have falls to the device, not to English`() {
        // English would be a guess dressed up as a decision. The device knows
        // what a Catalan speaker should see when Catalan is unavailable; this
        // app does not.
        assertEquals(LanguageChoice.SYSTEM, languageChoiceFor("ca"))
        assertEquals(LanguageChoice.SYSTEM, languageChoiceFor("fr-CA"))
    }

    @Test
    fun `the tag written back is the one that would be read`() {
        for (choice in LanguageChoice.entries) {
            val tag = choice.languageTag
            if (tag == null) {
                assertEquals(LanguageChoice.SYSTEM, choice)
            } else {
                assertEquals(choice, languageChoiceFor(tag), "round trip for $choice")
            }
        }
    }

    @Test
    fun `following the system stores nothing rather than a blank`() {
        assertNull(LanguageChoice.SYSTEM.languageTag)
    }
}
