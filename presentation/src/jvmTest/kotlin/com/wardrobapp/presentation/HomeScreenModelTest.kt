package com.wardrobapp.presentation

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeScreenModelTest {

    @Test
    fun `loads the three counts when it is made`() = runTest {
        val model = HomeScreenModel(this, HomeSource { HomeCounts(items = 12, archived = 3, rated = 5) })
        assertTrue(model.state.value.loading, "a model starts out loading, so nothing reads as an empty wardrobe")

        advanceUntilIdle()

        assertEquals(HomeScreenState(loading = false, items = 12, archived = 3, rated = 5), model.state.value)
    }

    @Test
    fun `a failure is reported rather than shown as an empty wardrobe`() = runTest {
        val model = HomeScreenModel(this, HomeSource { throw IOException("disk is gone") })
        advanceUntilIdle()

        assertEquals(false, model.state.value.loading)
        assertEquals("disk is gone", model.state.value.error)
    }

    @Test
    fun `a failure with no message is named by its class`() = runTest {
        val model = HomeScreenModel(this, HomeSource { throw IllegalStateException() })
        advanceUntilIdle()

        assertEquals("IllegalStateException", model.state.value.error)
    }

    @Test
    fun `refreshing clears the last error and loads again`() = runTest {
        var fail = true
        val model = HomeScreenModel(this, HomeSource {
            if (fail) throw IOException("not yet") else HomeCounts(1, 0, 0)
        })
        advanceUntilIdle()
        fail = false

        model.refresh()
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.error, "the old error is cleared as soon as the reload starts")
        advanceUntilIdle()

        assertEquals(HomeScreenState(loading = false, items = 1), model.state.value)
    }

    @Test
    fun `a load cut short by the screen going away is not reported as a failure`() {
        val scope = TestScope()
        val never = CompletableDeferred<HomeCounts>()
        val model = HomeScreenModel(scope, HomeSource { never.await() })
        scope.advanceUntilIdle()

        scope.cancel()
        scope.advanceUntilIdle()

        assertNull(model.state.value.error)
    }
}
