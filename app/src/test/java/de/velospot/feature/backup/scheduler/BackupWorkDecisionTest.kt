package de.velospot.feature.backup.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupWorkDecisionTest {

    @Test
    fun `disabled schedule is DISABLED regardless of the rest`() {
        assertEquals(
            BackupWorkOutcome.DISABLED,
            BackupWorkDecision.decide(enabled = false, hasDestination = true, hasPassphrase = true)
        )
    }

    @Test
    fun `no destination skips`() {
        assertEquals(
            BackupWorkOutcome.SKIP_NO_DESTINATION,
            BackupWorkDecision.decide(enabled = true, hasDestination = false, hasPassphrase = true)
        )
    }

    @Test
    fun `no passphrase skips`() {
        assertEquals(
            BackupWorkOutcome.SKIP_NO_PASSPHRASE,
            BackupWorkDecision.decide(enabled = true, hasDestination = true, hasPassphrase = false)
        )
    }

    @Test
    fun `enabled with destination and passphrase runs`() {
        assertEquals(
            BackupWorkOutcome.RUN,
            BackupWorkDecision.decide(enabled = true, hasDestination = true, hasPassphrase = true)
        )
    }
}

