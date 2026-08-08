package de.velospot.feature.wrapped.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class WrappedWorkDecisionTest {

    @Test
    fun `disabled schedule yields DISABLED regardless of other flags`() {
        assertEquals(
            WrappedWorkOutcome.DISABLED,
            WrappedWorkDecision.decide(enabled = false, alreadyExists = false, hasReport = true)
        )
        assertEquals(
            WrappedWorkOutcome.DISABLED,
            WrappedWorkDecision.decide(enabled = false, alreadyExists = true, hasReport = false)
        )
    }

    @Test
    fun `existing report is skipped as a duplicate`() {
        assertEquals(
            WrappedWorkOutcome.SKIP_ALREADY_EXISTS,
            WrappedWorkDecision.decide(enabled = true, alreadyExists = true, hasReport = true)
        )
    }

    @Test
    fun `empty period is skipped`() {
        assertEquals(
            WrappedWorkOutcome.SKIP_EMPTY,
            WrappedWorkDecision.decide(enabled = true, alreadyExists = false, hasReport = false)
        )
    }

    @Test
    fun `fresh non-empty report is saved and notified`() {
        assertEquals(
            WrappedWorkOutcome.SAVE_AND_NOTIFY,
            WrappedWorkDecision.decide(enabled = true, alreadyExists = false, hasReport = true)
        )
    }
}

