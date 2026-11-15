package com.wardrobapp.presentation

import com.wardrobapp.domain.MATERIALS
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every material the app offers has words, in both languages, and the screens
 * look them up.
 *
 * Here rather than in :ui because :ui is built for the browser alone on a
 * machine without the Android SDK, where its own JVM tests do not run; reading
 * its files from here is how the other checks on it run everywhere (see
 * StringResourceParityTest). A material added to MATERIALS without a label
 * would otherwise be offered in the form as its bare English tag.
 */
class MaterialLabelsTest {

    private val resources = File(System.getProperty("uiResDir") ?: error("uiResDir was not set; see presentation/build.gradle.kts"))
    private val sources = File(System.getProperty("uiSourceDir") ?: error("uiSourceDir was not set; see presentation/build.gradle.kts"))

    private fun stringNames(directory: String): Set<String> =
        Regex("""<string name="([^"]+)"""").findAll(File(resources, "$directory/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every material has a label in English and in Spanish`() {
        val expected = MATERIALS.map { "material_$it" }.toSet()

        for (directory in listOf("values", "values-es")) {
            val named = stringNames(directory).filter { it.startsWith("material_") }.toSet()
            assertEquals(expected, named, "$directory/strings.xml")
        }
    }

    @Test
    fun `the screens look every material up by its tag`() {
        val vocabulary = sources.walkTopDown().single { it.name == "Vocabulary.kt" }.readText()
        val mapped = Regex(""""([a-z]+)" to Res\.string\.material_\1""").findAll(vocabulary).map { it.groupValues[1] }.toList()

        assertEquals(MATERIALS, mapped)
    }
}
