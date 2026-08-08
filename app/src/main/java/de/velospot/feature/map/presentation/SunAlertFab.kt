package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.util.SunAlertState
import de.velospot.core.util.SunEventKind
import java.time.Duration
import java.time.Instant

/**
 * Warm golden-hour palette for the imminent **sunset** — amber → orange → deep
 * pink, evoking a sun sinking over the horizon.
 */
private val SunsetGradient = listOf(
    Color(0xFFFFC24B), // warm amber
    Color(0xFFFF7A3C), // orange
    Color(0xFFE23E77)  // deep pink
)

/**
 * Cooler dawn palette for the imminent **sunrise** — soft blue/violet fading to a
 * warm peach glow on the horizon.
 */
private val SunriseGradient = listOf(
    Color(0xFF7C6CF0), // soft violet
    Color(0xFF6AA9F0), // dawn blue
    Color(0xFFFFC48C)  // warm peach
)

/**
 * The golden-hour alert FAB shown on the map when the sun is about to rise or set.
 *
 * Rendered only when [sunAlert] is non-null (the caller further gates it on
 * `activeNavigation == null`). A gradient circular button — warm for sunset, cool
 * for sunrise — with a subtle pulsing halo to signal imminence. Tapping it toggles
 * a small card showing a **live-updating countdown** to the event.
 *
 * Anchored to the **bottom-left** ([Alignment.BottomStart]) so it never collides
 * with the right-centre speed-dial or the bottom-right location/record/recenter
 * FABs.
 */
@Composable
internal fun BoxScope.SunAlertFab(sunAlert: SunAlertState?) {
    AnimatedVisibility(
        visible = sunAlert != null,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 16.dp, bottom = 16.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 0.7f)
    ) {
        // Keep the last non-null alert during the exit animation so the content
        // does not flicker while fading out.
        val shown = remember(sunAlert != null) { sunAlert }
        if (shown != null) {
            SunAlertFabContent(state = shown)
        }
    }
}

@Composable
private fun SunAlertFabContent(state: SunAlertState) {
    val isSunrise = state.kind == SunEventKind.SUNRISE
    val gradient = if (isSunrise) SunriseGradient else SunsetGradient
    val icon = if (isSunrise) Icons.Filled.WbSunny else Icons.Filled.WbTwilight
    val label = stringResourceOf(
        if (isSunrise) R.string.sun_alert_sunrise else R.string.sun_alert_sunset
    )
    val contentDesc = stringResourceOf(
        if (isSunrise) R.string.sun_alert_fab_sunrise_desc
        else R.string.sun_alert_fab_sunset_desc
    )

    var expanded by remember { mutableStateOf(false) }

    // Subtle infinite pulse (scale + halo alpha) to signal imminence.
    val infinite = rememberInfiniteTransition(label = "sunAlertPulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sunAlertPulseScale"
    )
    val haloAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sunAlertHaloAlpha"
    )

    Column(horizontalAlignment = Alignment.Start) {
        // The live countdown card appears above the FAB when expanded.
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f)
        ) {
            SunAlertCountdownCard(state = state, label = label, gradient = gradient)
            Spacer(Modifier.size(12.dp))
        }

        Box(contentAlignment = Alignment.Center) {
            // Soft halo behind the button, gently breathing to draw the eye.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(gradient.last().copy(alpha = haloAlpha))
            )
            Surface(
                onClick = { expanded = !expanded },
                shape = CircleShape,
                color = Color.Transparent,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = contentDesc }
            ) {
                Box(
                    modifier = Modifier.background(Brush.linearGradient(gradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * Small card anchored above the FAB with a live-ticking countdown to the event.
 * Ticks once per second **only while open** (the [LaunchedEffect] is torn down
 * when the card leaves composition on collapse).
 */
@Composable
private fun SunAlertCountdownCard(
    state: SunAlertState,
    label: String,
    gradient: List<Color>
) {
    val isSunrise = state.kind == SunEventKind.SUNRISE
    var remaining by remember(state.eventTime) {
        mutableStateOf(Duration.between(Instant.now(), state.eventTime))
    }
    LaunchedEffect(state.eventTime) {
        while (true) {
            remaining = Duration.between(Instant.now(), state.eventTime)
            kotlinx.coroutines.delay(1_000L)
        }
    }

    val untilLabel = stringResourceOf(
        if (isSunrise) R.string.sun_alert_until_sunrise else R.string.sun_alert_until_sunset
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small gradient accent dot echoing the FAB colour.
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradient))
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = stringResourceOf(R.string.sun_alert_golden_hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatCountdown(remaining),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = untilLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Formats a countdown [Duration] as `mm:ss` (clamped to zero). The digits are
 * computed in code so no locale-specific string resource is needed for them.
 */
internal fun formatCountdown(duration: Duration): String {
    val totalSeconds = duration.seconds.coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/** Thin wrapper so the string lookups above read cleanly. */
@Composable
private fun stringResourceOf(id: Int): String =
    androidx.compose.ui.res.stringResource(id = id)

