package com.wardrobapp.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When Home offers training, and when it keeps quiet. */
class TasteTrainingTest {

    @Test
    fun `offered while the wardrobe can be trained and has not been`() {
        assertTrue(showsTasteTraining(firstStepsVisible = false, garments = 12, rated = 0))
        assertTrue(showsTasteTraining(firstStepsVisible = false, garments = TRAINABLE_GARMENTS, rated = TRAINED_RATINGS - 1))
    }

    @Test
    fun `not while the first steps card already offers it`() {
        assertFalse(showsTasteTraining(firstStepsVisible = true, garments = 12, rated = 0))
    }

    @Test
    fun `not once a round's worth has been rated`() {
        assertFalse(showsTasteTraining(firstStepsVisible = false, garments = 12, rated = TRAINED_RATINGS))
    }

    @Test
    fun `not for a wardrobe too small to build from`() {
        assertFalse(showsTasteTraining(firstStepsVisible = false, garments = TRAINABLE_GARMENTS - 1, rated = 0))
    }

    @Test
    fun `not while the counts are unknown`() {
        // "Not read yet" is never a reason to show a card, as firstStepsFor has it.
        assertFalse(showsTasteTraining(firstStepsVisible = false, garments = null, rated = 0))
        assertFalse(showsTasteTraining(firstStepsVisible = false, garments = 12, rated = null))
    }
}
