package com.wardrobapp.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Five tab labels, each on one line, with the whole word showing.
 *
 * Worth measuring rather than looking at, because looking at it is how the bug got
 * here: "Estadisticas" fitted while there were four tabs and wrapped when the
 * Settings tab made it five, and nothing in the build had an opinion. The
 * arithmetic that says 10sp is enough is arithmetic; the fonts are the device's,
 * so this asks the layout instead.
 *
 * It composes [WardrobeBottomBar] itself -- the bar that ships, with the list of
 * tabs that ships -- so a sixth tab, or a longer word in either language, fails
 * here rather than on somebody's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
// Without this the measurements are a fiction: Robolectric's stub metrics give
// every glyph the same width, so "Home" measured 4px and "Inicio" 6px -- their
// character counts -- and no word could ever be too wide for anything. Native
// graphics puts real fonts behind the layout, which is the only way a test can
// have an opinion about whether twelve Spanish characters fit in 82dp.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabLabelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every label fits on one line in English`() {
        assertLabelsFit()
    }

    @Test
    @Config(qualifiers = "+es-rES")
    fun `every label fits on one line in Spanish`() {
        assertLabelsFit()
    }

    @Test
    @Config(qualifiers = "+es-rES")
    fun `the word really does not fit at Material's own label size`() {
        // A canary, not a test of the app. Without it "every label fits" would pass
        // just as happily against a layout that measured nothing -- which is exactly
        // what it did before native graphics went on, when every glyph came back one
        // pixel wide and no word could be too wide for anything.
        //
        // So this composes the bar as it was, at the size that wrapped, and insists
        // the environment still says so. If this ever passes quietly, the test above
        // has stopped meaning anything.
        compose.setContent {
            NavigationBar {
                for (tab in TABS) {
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        }

        val labels = labelLayouts()
        val wrapped = labels.filter { it.lineCount > 1 }.map { it.layoutInput.text.text }

        assertTrue(
            "nothing wrapped at Material's own label size, so this cannot tell a fit " +
                "from a truncation: " +
                labels.joinToString { "${it.layoutInput.text.text}=${it.size}" },
            "Estadísticas" in wrapped,
        )
    }

    private fun assertLabelsFit() {
        compose.setContent { WardrobeBottomBar(route = HOME, onTabSelected = {}) }

        val labels = labelLayouts()
        assertEquals("not every tab drew a label", TABS.size, labels.size)

        for (label in labels) {
            val word = label.layoutInput.text.text

            assertEquals("\"$word\" wrapped onto a second line", 1, label.lineCount)

            // The one that catches an ellipsis. `maxLines = 1` means a word too
            // wide is truncated rather than wrapped, so the line count alone would
            // report a fit for a label reading "Estadisti...".
            //
            // `didOverflowHeight` rather than `hasVisualOverflow`, which was wrong:
            // it folds in `didOverflowWidth`, and that compares the width the
            // paragraph was laid out within against the width the text ended up
            // taking -- so it is true of any label narrower than its slot, which is
            // every label that fits. Truncation is what "did not fit" means here.
            //
            // The numbers are in the message because this runs only in CI, and a
            // bare "did not fit" costs a push to find out what the layout thought.
            assertFalse(
                "\"$word\" was cut short: laid out ${label.size} within " +
                    "${label.multiParagraph.width}x${label.multiParagraph.height}",
                label.didOverflowHeight,
            )
        }
    }

    /**
     * The laid-out text behind every label in the bar.
     *
     * Unmerged, because a navigation item merges its descendants into one
     * clickable node and the text layout belongs to the Text inside it. The icons
     * carry no content description, so the text nodes are the labels and nothing
     * else.
     */
    private fun labelLayouts(): List<TextLayoutResult> =
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.single()
        }
}
