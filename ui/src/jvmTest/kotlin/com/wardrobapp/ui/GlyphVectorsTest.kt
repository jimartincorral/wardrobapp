package com.wardrobapp.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every generated glyph builds.
 *
 * `scripts/generate-glyphs.py` checks that path data holds only the characters
 * path data can hold, which keeps it a valid Kotlin string; whether Compose can
 * parse it is a separate question, and the answer arrives as an exception the
 * first time somebody opens the screen that draws it. This asks every glyph
 * instead, at build time.
 *
 * The first test in :ui. It runs on the JVM, with Compose's own Skia, which is
 * the point of this module having a JVM target at all.
 */
class GlyphVectorsTest {

    private val glyphs: Map<String, ImageVector> = mapOf(
        "Apps" to GlyphVectors.Apps,
        "Archive" to GlyphVectors.Archive,
        "AutoAwesome" to GlyphVectors.AutoAwesome,
        "AutoFixHigh" to GlyphVectors.AutoFixHigh,
        "Bookmark" to GlyphVectors.Bookmark,
        "BookmarkBorder" to GlyphVectors.BookmarkBorder,
        "ChevronRight" to GlyphVectors.ChevronRight,
        "Crop" to GlyphVectors.Crop,
        "DeleteOutline" to GlyphVectors.DeleteOutline,
        "Download" to GlyphVectors.Download,
        "ExpandMore" to GlyphVectors.ExpandMore,
        "GridView" to GlyphVectors.GridView,
        "Insights" to GlyphVectors.Insights,
        "Link" to GlyphVectors.Link,
        "PhotoCamera" to GlyphVectors.PhotoCamera,
        "PushPin" to GlyphVectors.PushPin,
        "RadioButtonUnchecked" to GlyphVectors.RadioButtonUnchecked,
        "RestartAlt" to GlyphVectors.RestartAlt,
        "SkipNext" to GlyphVectors.SkipNext,
        "StarBorder" to GlyphVectors.StarBorder,
        "SwapVert" to GlyphVectors.SwapVert,
        "Tune" to GlyphVectors.Tune,
        "ViewModule" to GlyphVectors.ViewModule,
    )

    @Test
    fun `every glyph builds, at the size the drawables were`() {
        // Twenty-three, as art/glyphs holds; GlyphSourcesTest checks the names.
        assertEquals(23, glyphs.size)

        for ((name, glyph) in glyphs) {
            assertEquals(24.dp, glyph.defaultWidth, name)
            assertEquals(24.dp, glyph.defaultHeight, name)
            assertEquals(24f, glyph.viewportWidth, name)
            assertEquals(24f, glyph.viewportHeight, name)
            assertTrue(glyph.root.size > 0, "$name has nothing to draw")
        }
    }
}
