package de.velospot.core.navigation

import de.velospot.core.navigation.NavigationVoiceCues.VoiceCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationVoiceCuesTest {

    private fun progress(
        nextTurnDistanceMeters: Double? = null,
        nextTurnAngleDegrees: Double? = null,
        remainingMeters: Double = 5_000.0,
        isOffRoute: Boolean = false
    ) = NavigationProgress(
        remainingMeters = remainingMeters,
        remainingSeconds = remainingMeters / 4.5,
        distanceFromRouteMeters = 0.0,
        isOffRoute = isOffRoute,
        nextTurnDistanceMeters = nextTurnDistanceMeters,
        nextTurnAngleDegrees = nextTurnAngleDegrees
    )

    @Test
    fun `no cue when no turn ahead`() {
        val cues = NavigationVoiceCues()
        assertNull(cues.onProgress(progress()))
    }

    @Test
    fun `no cue while turn is still far away`() {
        val cues = NavigationVoiceCues()
        assertNull(cues.onProgress(progress(nextTurnDistanceMeters = 300.0, nextTurnAngleDegrees = -80.0)))
    }

    @Test
    fun `prepare cue fires once within prepare distance`() {
        val cues = NavigationVoiceCues()
        val cue = cues.onProgress(progress(nextTurnDistanceMeters = 140.0, nextTurnAngleDegrees = -80.0))
        assertTrue(cue is VoiceCue.Turn)
        cue as VoiceCue.Turn
        assertTrue(!cue.imminent)
        assertTrue(cue.angleDegrees < 0)

        // A second snapshot still in the prepare band must NOT re-announce.
        assertNull(cues.onProgress(progress(nextTurnDistanceMeters = 120.0, nextTurnAngleDegrees = -80.0)))
    }

    @Test
    fun `imminent cue fires when very close`() {
        val cues = NavigationVoiceCues()
        cues.onProgress(progress(nextTurnDistanceMeters = 140.0, nextTurnAngleDegrees = 80.0))
        val cue = cues.onProgress(progress(nextTurnDistanceMeters = 20.0, nextTurnAngleDegrees = 80.0))
        assertTrue(cue is VoiceCue.Turn)
        assertTrue((cue as VoiceCue.Turn).imminent)
    }

    @Test
    fun `passing a turn re-arms the next turn`() {
        val cues = NavigationVoiceCues()
        // Approach + pass the first turn.
        cues.onProgress(progress(nextTurnDistanceMeters = 100.0, nextTurnAngleDegrees = -80.0))
        cues.onProgress(progress(nextTurnDistanceMeters = 20.0, nextTurnAngleDegrees = -80.0))
        // Next turn comes into range much further ahead -> distance jumps up.
        val cue = cues.onProgress(progress(nextTurnDistanceMeters = 130.0, nextTurnAngleDegrees = 80.0))
        assertTrue(cue is VoiceCue.Turn)
    }

    @Test
    fun `arrival cue fires once near destination`() {
        val cues = NavigationVoiceCues()
        val cue = cues.onProgress(progress(remainingMeters = 10.0))
        assertEquals(VoiceCue.Arrived, cue)
        assertNull(cues.onProgress(progress(remainingMeters = 8.0)))
    }

    @Test
    fun `off route suppresses cues`() {
        val cues = NavigationVoiceCues()
        assertNull(
            cues.onProgress(
                progress(nextTurnDistanceMeters = 50.0, nextTurnAngleDegrees = -80.0, isOffRoute = true)
            )
        )
    }
}

