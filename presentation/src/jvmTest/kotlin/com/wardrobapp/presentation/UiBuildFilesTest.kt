package com.wardrobapp.presentation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * :ui's two build files, kept saying the same thing where they overlap.
 *
 * build.gradle.kts builds the module for Android, the JVM and the browser, and is
 * used where there is an Android SDK. build.wasm.gradle.kts builds it for the
 * browser alone, and is used everywhere else -- including the CI job that runs
 * `./gradlew test` without an SDK. Both compile the same sources, so they have to
 * agree about what those sources depend on; a dependency added to one and not
 * the other would leave the browser build compiling against a different set of
 * libraries from the app, silently, until something only one of them had was
 * used.
 *
 * Compared as text, because neither file can be evaluated from here -- one needs
 * an SDK -- and because what has to match is what is written: the common
 * dependencies, the resource settings and the shared plugins, whose versions are
 * declared once in the root build file. Comments are ignored, so either file can
 * explain itself at whatever length it needs to.
 */
class UiBuildFilesTest {

    private val directory = File(
        System.getProperty("uiModuleDir") ?: error("uiModuleDir was not set; see presentation/build.gradle.kts"),
    )
    private val full = File(directory, "build.gradle.kts").readText()
    private val browserOnly = File(directory, "build.wasm.gradle.kts").readText()

    @Test
    fun `both declare the same common dependencies`() {
        val expected = statements(block(full, "commonMain.dependencies {"))
        assertTrue(expected.isNotEmpty(), "found no common dependencies in build.gradle.kts")
        assertEquals(expected.sorted(), statements(block(browserOnly, "commonMain.dependencies {")).sorted())
    }

    @Test
    fun `both configure the resources the same way`() {
        val expected = statements(block(full, "compose.resources {"))
        assertTrue(expected.isNotEmpty(), "found no resource settings in build.gradle.kts")
        assertEquals(expected.sorted(), statements(block(browserOnly, "compose.resources {")).sorted())
    }

    @Test
    fun `every plugin the browser build applies is the one the full build applies`() {
        val fullPlugins = statements(block(full, "plugins {")).toSet()
        val browserPlugins = statements(block(browserOnly, "plugins {"))
        assertTrue(browserPlugins.isNotEmpty(), "found no plugins in build.wasm.gradle.kts")
        assertEquals(emptyList(), browserPlugins.filterNot { it in fullPlugins }, "plugin or version differs")
    }

    @Test
    fun `the comparison sees through comments but not past code`() {
        // The extraction is the whole test, so it is checked on its own: a
        // comment is not a statement, and a block ends at its own brace rather
        // than at the first one inside it.
        val source = """
            outer {
                // a comment
                inner { a() }
                b() // trailing
            }
            after()
        """.trimIndent()
        assertEquals(listOf("inner { a() }", "b()"), statements(block(source, "outer {")))
    }

    /** What is between [opening] and the brace that closes it. */
    private fun block(source: String, opening: String): String {
        val start = source.indexOf(opening)
        if (start < 0) return ""
        var depth = 0
        var i = start + opening.length - 1
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start + opening.length, i)
            }
            i++
        }
        return ""
    }

    /** The block's lines with comments and blank lines taken out. */
    private fun statements(block: String): List<String> =
        block.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
}
