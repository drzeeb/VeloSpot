package de.velospot.core.navigation

/**
 * Pure, Android-free decision logic that turns a stream of [NavigationProgress]
 * snapshots into discrete spoken [VoiceCue]s. Kept separate from the actual
 * Text-to-Speech engine so the (stateful) "what should we say, and when?" logic
 * is fully unit-testable without an Android runtime.
 *
 * Behaviour per upcoming turn:
 *  1. A **prepare** cue once the turn comes within [PREPARE_DISTANCE_M]
 *     ("In 150 metres, turn left").
 *  2. An **imminent** cue once it is within [NOW_DISTANCE_M] ("Now turn left").
 *
 * Each cue fires at most once per turn; passing a turn (the reported distance
 * jumps back up as the *next* turn comes into range) re-arms both cues. A single
 * **arrived** cue fires when the destination is reached.
 */
class NavigationVoiceCues {

    companion object {
        /** Distance (m) at which the early "prepare to turn" cue is spoken. */
        const val PREPARE_DISTANCE_M = 150.0

        /** Distance (m) at which the final "now turn" cue is spoken. */
        const val NOW_DISTANCE_M = 30.0

        /**
         * If the reported turn distance grows by more than this between fixes, the
         * previous turn was passed and a new one is ahead — re-arm both cues.
         */
        const val NEW_TURN_RESET_MARGIN_M = 40.0

        /** Remaining distance (m) to the destination that triggers the arrival cue. */
        const val ARRIVED_DISTANCE_M = 25.0
    }

    /** A single spoken instruction the [NavigationVoiceCues] decided to emit. */
    sealed interface VoiceCue {
        /**
         * A turn announcement.
         * @property distanceMeters Distance to the turn (for the spoken text).
         * @property angleDegrees Signed heading change (negative = left).
         * @property imminent `true` for the final "now" cue, `false` for the early
         *  "prepare" cue.
         */
        data class Turn(
            val distanceMeters: Double,
            val angleDegrees: Double,
            val imminent: Boolean
        ) : VoiceCue

        /** The rider has reached the destination. */
        data object Arrived : VoiceCue
    }

    private var preparedAnnounced = false
    private var nowAnnounced = false
    private var lastTurnDistance = Double.MAX_VALUE
    private var arrivedAnnounced = false

    /** Clears all per-route state; call when (re)starting navigation. */
    fun reset() {
        resetTurn()
        arrivedAnnounced = false
    }

    private fun resetTurn() {
        preparedAnnounced = false
        nowAnnounced = false
        lastTurnDistance = Double.MAX_VALUE
    }

    /**
     * Feeds the latest [progress] and returns the [VoiceCue] to speak now, or
     * `null` when nothing new should be announced.
     */
    fun onProgress(progress: NavigationProgress): VoiceCue? {
        // Off-route: the turn ahead is meaningless until a reroute lands. Re-arm
        // the turn cues so the first turn of the new route is announced again.
        if (progress.isOffRoute) {
            resetTurn()
            return null
        }

        // Arrival takes priority over any (tiny) remaining turn.
        if (progress.remainingMeters <= ARRIVED_DISTANCE_M) {
            if (!arrivedAnnounced) {
                arrivedAnnounced = true
                return VoiceCue.Arrived
            }
            return null
        }
        arrivedAnnounced = false

        val distance = progress.nextTurnDistanceMeters
        val angle = progress.nextTurnAngleDegrees
        if (distance == null || angle == null) {
            resetTurn()
            return null
        }

        // A jump back up in distance means we passed the previous turn — re-arm.
        if (distance > lastTurnDistance + NEW_TURN_RESET_MARGIN_M) {
            preparedAnnounced = false
            nowAnnounced = false
        }
        lastTurnDistance = distance

        return when {
            distance <= NOW_DISTANCE_M && !nowAnnounced -> {
                nowAnnounced = true
                preparedAnnounced = true
                VoiceCue.Turn(distance, angle, imminent = true)
            }
            distance in NOW_DISTANCE_M..PREPARE_DISTANCE_M && !preparedAnnounced -> {
                preparedAnnounced = true
                VoiceCue.Turn(distance, angle, imminent = false)
            }
            else -> null
        }
    }
}

