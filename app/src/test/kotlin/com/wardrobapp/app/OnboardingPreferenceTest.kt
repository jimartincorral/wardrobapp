package com.wardrobapp.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * That a first launch is only a first launch once.
 *
 * The consequence of getting this wrong is not subtle and is also not visible in
 * any single run: a flag that does not persist means the welcome screen comes back
 * on every launch, forever, and nothing but a real preferences file would notice.
 * Same reasoning as [ThemePreferenceTest].
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingPreferenceTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * Start from a fresh install every time.
     *
     * Cleared through the file rather than by setting each flag false, because
     * "never written" is the state the first assertion below is about and writing
     * false is a different one.
     */
    @Before
    fun forgetEverything() {
        context
            .getSharedPreferences("wardrobapp_onboarding", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `a fresh install has seen nothing and dismissed nothing`() {
        val fresh = OnboardingPreference(context)

        assertFalse(fresh.seen)
        assertFalse(fresh.firstStepsDismissed)
        assertFalse(fresh.bulkAddUsed)
    }

    @Test
    fun `each flag survives into the next launch`() {
        // A second instance rather than the same one, because that is what the
        // next launch is: the value has to come back from the file rather than
        // from a field that happens to still hold it.
        OnboardingPreference(context).seen = true
        assertTrue(OnboardingPreference(context).seen)

        OnboardingPreference(context).firstStepsDismissed = true
        assertTrue(OnboardingPreference(context).firstStepsDismissed)

        OnboardingPreference(context).bulkAddUsed = true
        assertTrue(OnboardingPreference(context).bulkAddUsed)
    }

    @Test
    fun `the three flags are three flags`() {
        // They are written at different moments by different screens, and one that
        // shared a key with another would dismiss a card somebody never saw.
        OnboardingPreference(context).seen = true

        val after = OnboardingPreference(context)
        assertTrue(after.seen)
        assertFalse(after.firstStepsDismissed)
        assertFalse(after.bulkAddUsed)
    }

    @Test
    fun `both card flags come back together`() {
        val preference = OnboardingPreference(context)
        preference.bulkAddUsed = true

        assertEquals(
            FirstStepFlags(dismissed = false, bulkAddUsed = true),
            OnboardingPreference(context).firstStepFlags(),
        )
    }

    @Test
    fun `onboarding state is not something a backup carries`() {
        // Deliberately outside AppSettings' allowlist: a restore answers all three
        // questions itself, and a wardrobe that arrived whole should not also
        // arrive with somebody else's checklist half ticked. Asserted rather than
        // left to the comment on OnboardingPreference, because the allowlist is a
        // list somebody could add a line to.
        OnboardingPreference(context).seen = true

        val captured = AppSettings(context).capture()

        assertFalse("wardrobapp_onboarding" in captured.preferences.keys)
    }
}
