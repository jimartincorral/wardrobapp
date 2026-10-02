package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingTest {

    @Test
    fun `the flow is welcome, then adding, then learning`() {
        // Pinned, because the order *is* the argument: what the app is, then the
        // one flow every wardrobe starts with, then what rating buys. A reorder
        // that read fine as a diff would change what a new reader is told first.
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.ADDING,
                OnboardingStep.LEARNING,
            ),
            OnboardingStep.entries,
        )
    }

    @Test
    fun `forward walks to the end and then stops`() {
        assertEquals(OnboardingStep.ADDING, OnboardingStep.WELCOME.next)
        assertEquals(OnboardingStep.LEARNING, OnboardingStep.ADDING.next)

        // Not a cycle: the last screen's button leaves the flow, and a wrap-around
        // would send somebody who tapped "Got it" back to the welcome screen.
        assertNull(OnboardingStep.LEARNING.next)
    }

    @Test
    fun `back walks out of the flow rather than round it`() {
        assertEquals(OnboardingStep.ADDING, OnboardingStep.LEARNING.previous)
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.ADDING.previous)

        // Back on the first screen is the answer "Not now" gives.
        assertNull(OnboardingStep.WELCOME.previous)
    }
}
