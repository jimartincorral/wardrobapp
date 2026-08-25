package com.wardrobapp.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The counts on the home screen, and where they lead.
 *
 * A number on a screen that answers a tap is worth a test for one reason: the two
 * counts sit side by side and lead to two different lists, so a wiring mistake is
 * invisible -- both cards look right, and the wrong one opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var wardrobe = 0
    private var archived = 0

    private fun show(state: HomeViewModel.State) {
        compose.setContent {
            HomeScreen(
                state = state,
                onAddRequested = {},
                onWardrobeRequested = { wardrobe++ },
                onArchivedRequested = { archived++ },
                onOutfitsRequested = {},
                onStatisticsRequested = {},
                onSettingsRequested = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun `the item count opens the wardrobe and the archived count opens the archive`() {
        show(HomeViewModel.State(loading = false, items = 14, archived = 3))

        compose.onNodeWithText("14").performClick()
        assertEquals(1, wardrobe)
        assertEquals(0, archived)

        compose.onNodeWithText("3").performClick()
        assertEquals(1, archived)
        assertEquals(1, wardrobe)
    }

    @Test
    fun `a count that is not known yet is a dash, and still a way in`() {
        // The counts read as a dash while the database is being read or after it
        // failed. Tapping is still worth allowing: the wardrobe screen can say what
        // went wrong far better than a card with a dash on it can.
        show(HomeViewModel.State(loading = true))

        // Both of them, which is why this is counted rather than looked up: two
        // nodes with the same text is a match failure, not an assertion.
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }
}
