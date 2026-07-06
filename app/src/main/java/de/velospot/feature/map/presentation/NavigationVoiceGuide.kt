package de.velospot.feature.map.presentation

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import de.velospot.R
import de.velospot.core.navigation.NavigationProgress
import de.velospot.core.navigation.NavigationVoiceCues
import de.velospot.core.navigation.NavigationVoiceCues.VoiceCue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Speaks turn-by-turn navigation instructions via Android [TextToSpeech].
 *
 * The *when/what to say* decision lives in the pure, unit-tested
 * [NavigationVoiceCues]; this class only owns the Android TTS engine and turns
 * the emitted [VoiceCue]s into localised, spoken strings — e.g. the same data
 * the on-screen turn banner already shows ("Turn left in 100 m") read aloud.
 *
 * Voice guidance is opt-in: nothing is spoken unless [setEnabled] has been called
 * with `true` (mirroring the persisted voice-guidance setting in
 * [de.velospot.domain.repository.MapSettingsRepository]). The engine is lazily
 * initialised on first enable and released via [shutdown].
 */
class NavigationVoiceGuide(private val context: Context) {

    private val cues = NavigationVoiceCues()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var enabled = false

    /** Enables/disables spoken guidance, lazily creating the TTS engine on enable. */
    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (enabled) ensureEngine() else stop()
    }

    /** Clears the announcement state; call when (re)starting navigation. */
    fun reset() = cues.reset()

    /** Stops any in-flight utterance (e.g. when navigation ends). */
    fun stop() {
        tts?.stop()
    }

    /** Releases the TTS engine. Call when the screen leaves composition. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    /** Feeds the latest navigation [progress]; speaks a cue when one is due. */
    fun onProgress(progress: NavigationProgress) {
        if (!enabled) return
        val cue = cues.onProgress(progress) ?: return
        if (!ttsReady) {
            // Engine still warming up — ensure it is being created so later cues work.
            ensureEngine()
            return
        }
        speak(cueText(cue))
    }

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Match the app's current locale; fall back to the device default.
                val locale = Locale.getDefault()
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
                ttsReady = true
            }
        }
    }

    private fun speak(text: String) {
        // QUEUE_FLUSH: a fresh instruction supersedes any still-playing one so the
        // rider always hears the most relevant cue rather than a stale backlog.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun cueText(cue: VoiceCue): String = when (cue) {
        is VoiceCue.Arrived -> context.getString(R.string.nav_voice_arrived)
        is VoiceCue.Turn -> {
            val maneuver = context.getString(maneuverRes(cue.angleDegrees))
            if (cue.imminent) {
                context.getString(R.string.nav_voice_now, maneuver)
            } else {
                context.getString(
                    R.string.nav_voice_prepare,
                    spokenDistance(cue.distanceMeters),
                    maneuver
                )
            }
        }
    }

    /** Localised, spoken distance: "150 m" rounded to 10 m, otherwise "1.2 km". */
    private fun spokenDistance(meters: Double): String =
        if (meters < 1000) {
            context.getString(R.string.nav_voice_distance_m, (meters / 10).roundToInt() * 10)
        } else {
            val locale = Locale.getDefault()
            context.getString(
                R.string.nav_voice_distance_km,
                String.format(locale, "%.1f", meters / 1000.0)
            )
        }

    private fun maneuverRes(angle: Double): Int {
        val left = angle < 0
        val mag = abs(angle)
        return when {
            mag < 50 -> if (left) R.string.nav_voice_maneuver_slight_left
                        else R.string.nav_voice_maneuver_slight_right
            mag <= 115 -> if (left) R.string.nav_voice_maneuver_left
                          else R.string.nav_voice_maneuver_right
            else -> if (left) R.string.nav_voice_maneuver_sharp_left
                    else R.string.nav_voice_maneuver_sharp_right
        }
    }

    private companion object {
        const val UTTERANCE_ID = "velospot_nav_voice"
    }
}

