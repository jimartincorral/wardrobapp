package com.wardrobapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * The icons Material's core set does not carry.
 *
 * This app depends on `material-icons-core`, which is about thirty glyphs. The
 * design asks for more than that, and twenty-three of them -- `tune`, `grid_view`,
 * `auto_awesome`, `chevron_right` and the rest -- live only in
 * `material-icons-extended`, a dependency that carries every glyph Google has
 * ever drawn. Adding it to obtain twenty-three of them is a trade this project
 * has refused before, for the same reason each time.
 *
 * So they are vendored instead: the same Apache-licensed 24px sources, kept as
 * SVG under `art/glyphs` and generated into [GlyphVectors] by
 * `scripts/generate-glyphs.py`. A few kilobytes for the set, against tens of
 * megabytes for the library, and what ships is exactly what is used.
 *
 * They were vector drawables under `res/drawable` until the screens started
 * moving to Compose Multiplatform, whose common code has no `R.drawable` to load
 * a drawable from. Built in Kotlin, a glyph draws the same on every platform,
 * and a missing one is a compile error rather than a crash while composing.
 *
 * Named here rather than reached for as `GlyphVectors.*` at each call site for
 * the reason `Icons.Filled` exists, and kept as painters so no call site had to
 * change when the source of the glyphs did. Public, and in :ui, because the
 * glyphs were the first thing to move there: the screens still in :app reach
 * them across the module boundary until they follow.
 */
object Glyph {
    val Apps: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Apps)
    val Archive: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Archive)
    val AutoAwesome: Painter @Composable get() = rememberVectorPainter(GlyphVectors.AutoAwesome)
    val AutoFixHigh: Painter @Composable get() = rememberVectorPainter(GlyphVectors.AutoFixHigh)
    val Bookmark: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Bookmark)
    val BookmarkBorder: Painter
        @Composable get() = rememberVectorPainter(GlyphVectors.BookmarkBorder)
    val ChevronRight: Painter @Composable get() = rememberVectorPainter(GlyphVectors.ChevronRight)
    val Crop: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Crop)
    val DeleteOutline: Painter @Composable get() = rememberVectorPainter(GlyphVectors.DeleteOutline)
    val Download: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Download)
    val ExpandMore: Painter @Composable get() = rememberVectorPainter(GlyphVectors.ExpandMore)
    val GridView: Painter @Composable get() = rememberVectorPainter(GlyphVectors.GridView)
    val Insights: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Insights)
    val Link: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Link)
    val PhotoCamera: Painter @Composable get() = rememberVectorPainter(GlyphVectors.PhotoCamera)
    val PushPin: Painter @Composable get() = rememberVectorPainter(GlyphVectors.PushPin)
    val RadioButtonUnchecked: Painter
        @Composable get() = rememberVectorPainter(GlyphVectors.RadioButtonUnchecked)
    val RestartAlt: Painter @Composable get() = rememberVectorPainter(GlyphVectors.RestartAlt)
    val SkipNext: Painter @Composable get() = rememberVectorPainter(GlyphVectors.SkipNext)
    val StarBorder: Painter @Composable get() = rememberVectorPainter(GlyphVectors.StarBorder)
    val SwapVert: Painter @Composable get() = rememberVectorPainter(GlyphVectors.SwapVert)
    val Tune: Painter @Composable get() = rememberVectorPainter(GlyphVectors.Tune)
    val ViewModule: Painter @Composable get() = rememberVectorPainter(GlyphVectors.ViewModule)
}
