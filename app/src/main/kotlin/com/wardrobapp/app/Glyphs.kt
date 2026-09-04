package com.wardrobapp.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * The icons Material's core set does not carry.
 *
 * This app depends on `material-icons-core`, which is about thirty glyphs. The
 * design asks for thirty-one, and twenty-two of them -- `tune`, `grid_view`,
 * `auto_awesome`, `chevron_right` and the rest -- live only in
 * `material-icons-extended`, a dependency that carries every icon Google has
 * ever drawn. Adding it to obtain twenty-two of them is the trade this project
 * already refused once, in the comment above `GridGlyph`.
 *
 * So they are vendored instead: the same Apache-licensed 24px sources, converted
 * to vector drawables and committed under `res/drawable`. About four kilobytes
 * for the set, against tens of megabytes for the library, and what ships is
 * exactly what is used.
 *
 * Named here rather than reached for as `R.drawable.ic_*` at each call site for
 * the reason `Icons.Filled` exists: an icon referred to by resource id reads as
 * a resource, and the thing a reader wants to know at the call site is which
 * glyph it is.
 */
internal object Glyph {
    val Apps: Painter @Composable get() = painterResource(R.drawable.ic_apps)
    val Archive: Painter @Composable get() = painterResource(R.drawable.ic_archive)
    val AutoAwesome: Painter @Composable get() = painterResource(R.drawable.ic_auto_awesome)
    val AutoFixHigh: Painter @Composable get() = painterResource(R.drawable.ic_auto_fix_high)
    val Bookmark: Painter @Composable get() = painterResource(R.drawable.ic_bookmark)
    val BookmarkBorder: Painter @Composable get() = painterResource(R.drawable.ic_bookmark_border)
    val ChevronRight: Painter @Composable get() = painterResource(R.drawable.ic_chevron_right)
    val Crop: Painter @Composable get() = painterResource(R.drawable.ic_crop)
    val DeleteOutline: Painter @Composable get() = painterResource(R.drawable.ic_delete_outline)
    val Download: Painter @Composable get() = painterResource(R.drawable.ic_download)
    val ExpandMore: Painter @Composable get() = painterResource(R.drawable.ic_expand_more)
    val GridView: Painter @Composable get() = painterResource(R.drawable.ic_grid_view)
    val Insights: Painter @Composable get() = painterResource(R.drawable.ic_insights)
    val Link: Painter @Composable get() = painterResource(R.drawable.ic_link)
    val PhotoCamera: Painter @Composable get() = painterResource(R.drawable.ic_photo_camera)
    val PushPin: Painter @Composable get() = painterResource(R.drawable.ic_push_pin)
    val RestartAlt: Painter @Composable get() = painterResource(R.drawable.ic_restart_alt)
    val SkipNext: Painter @Composable get() = painterResource(R.drawable.ic_skip_next)
    val StarBorder: Painter @Composable get() = painterResource(R.drawable.ic_star_border)
    val SwapVert: Painter @Composable get() = painterResource(R.drawable.ic_swap_vert)
    val Tune: Painter @Composable get() = painterResource(R.drawable.ic_tune)
    val ViewModule: Painter @Composable get() = painterResource(R.drawable.ic_view_module)
}
