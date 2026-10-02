package com.wardrobapp.presentation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The glyphs the app draws are still the glyphs in `art/glyphs`.
 *
 * `GlyphVectors.kt` in :app is generated from those SVGs by
 * `scripts/generate-glyphs.py`. Generated code has a particular way of going
 * wrong: somebody edits an SVG -- swaps in a newer upstream drawing, adds a
 * glyph -- and does not run the script, and nothing fails, because the old
 * vectors still compile and still draw. The app then ships a drawing nobody can
 * find the source of. So this compares what the Kotlin draws with what the SVGs
 * say, path by path.
 *
 * Here rather than in :app for the reason the other resource checks are: :app
 * does not compile without the Android SDK, and this is a question about two
 * text files that any machine can answer.
 */
class GlyphSourcesTest {

    private val sources: File = File(
        System.getProperty("glyphSourceDir")
            ?: error("glyphSourceDir was not set; see presentation/build.gradle.kts"),
    )

    private val generated: File = File(
        System.getProperty("appSourceDir")
            ?: error("appSourceDir was not set; see presentation/build.gradle.kts"),
        "GlyphVectors.kt",
    )

    /** One path as both sides describe it: its data, and whether it fills even-odd. */
    private data class GlyphPath(val data: String, val evenOdd: Boolean)

    @Test
    fun `every glyph draws what its SVG says, and there are no others`() {
        val fromSources = sourceGlyphs()
        val fromKotlin = generatedGlyphs()

        assertTrue(fromSources.isNotEmpty(), "no SVGs found in $sources")
        assertEquals(
            fromSources.keys.sorted(),
            fromKotlin.keys.sorted(),
            "the glyphs named in the SVGs and in GlyphVectors.kt differ; run scripts/generate-glyphs.py",
        )
        for ((name, paths) in fromSources) {
            assertEquals(
                paths,
                fromKotlin[name],
                "$name draws something other than its SVG; run scripts/generate-glyphs.py",
            )
        }
    }

    @Test
    fun `the drawables the glyphs replaced are gone`() {
        // One source per glyph. A vector drawable left behind under res/drawable
        // would be a second copy that lint calls unused at best, and at worst the
        // one somebody edits.
        val drawables = File(sources.parentFile.parentFile, "app/src/main/res/drawable")
        val leftovers = drawables.listFiles { file -> file.extension == "xml" }.orEmpty().map { it.name }

        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `the reader of the generated file finds every shape the generator writes`() {
        // Otherwise an empty map on both sides -- a regex that matches nothing --
        // would pass the comparison above while checking nothing.
        val sample = """
            val Tune: ImageVector by lazy {
                glyph(
                    path("M3 17v2h6v-2H3z"),
                    path("M15,9 L15.94,6.93z", evenOdd = true),
                )
            }
        """.trimIndent()

        assertEquals(
            mapOf("Tune" to listOf(GlyphPath("M3 17v2h6v-2H3z", false), GlyphPath("M15,9 L15.94,6.93z", true))),
            parseGenerated(sample),
        )
    }

    private fun sourceGlyphs(): Map<String, List<GlyphPath>> =
        sources.listFiles { file -> file.extension == "svg" }.orEmpty().associate { svg ->
            val document = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(svg)
            val elements = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path")
            val paths = (0 until elements.length).map { index ->
                val element = elements.item(index) as org.w3c.dom.Element
                GlyphPath(
                    // Whitespace runs collapse, as the generator collapses them.
                    data = element.getAttribute("d").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "),
                    evenOdd = element.getAttribute("fill-rule") == "evenodd",
                )
            }
            kotlinName(svg.nameWithoutExtension) to paths
        }

    private fun generatedGlyphs(): Map<String, List<GlyphPath>> = parseGenerated(generated.readText())

    private fun parseGenerated(kotlin: String): Map<String, List<GlyphPath>> {
        val glyph = Regex("""val (\w+): ImageVector by lazy \{\s*glyph\((.*?)\)\s*\}""", RegexOption.DOT_MATCHES_ALL)
        val path = Regex("""path\("([^"]*)"(, evenOdd = true)?\)""")

        return glyph.findAll(kotlin).associate { match ->
            match.groupValues[1] to path.findAll(match.groupValues[2]).map {
                GlyphPath(it.groupValues[1], it.groupValues[2].isNotEmpty())
            }.toList()
        }
    }

    /** `radio_button_unchecked` -> `RadioButtonUnchecked`, as the generator names them. */
    private fun kotlinName(stem: String): String =
        stem.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
}
