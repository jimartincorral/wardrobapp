package com.wardrobapp.app

import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Closing an error dialog has to actually close it.
 *
 * The bug this guards against: most errors on this screen carry no message of
 * their own and are shown through [GarmentFormViewModel.State.errorFallback], a
 * resource id rather than a string. `onErrorDismissed` cleared only `error`, so
 * the moment the dialog closed, `errorText()` fell back to the still-set
 * `errorFallback` and drew the same dialog again -- immediately, since nothing
 * else changed. An `AlertDialog` is modal, so this read as the close button doing
 * nothing and the screen refusing to let go.
 */
@RunWith(RobolectricTestRunner::class)
class GarmentFormErrorDismissalTest {

    @Test
    fun `dismissing an error clears both the message and its fallback`() {
        val model = GarmentFormViewModel(AppContainer(RuntimeEnvironment.getApplication()), garmentId = null)

        // Saving with no photo attached is the simplest path to a fallback-only
        // error -- no camera, no background model, nothing to fake.
        model.onSaveRequested()
        model.onErrorDismissed()

        val state = model.state.value
        assertNull("the message survived being dismissed", state.error)
        assertNull("the fallback survived being dismissed, which is what reopened the dialog", state.errorFallback)
    }
}
