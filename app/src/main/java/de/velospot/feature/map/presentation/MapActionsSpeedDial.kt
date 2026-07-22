package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import kotlin.math.cos
import kotlin.math.sin

/** One entry of the map actions speed-dial: a labelled mini-FAB. */
internal data class SpeedDialAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/** Radius (from the main FAB centre) the mini-FABs fan out to when expanded. */
private val FAN_RADIUS = 128.dp

/**
 * A Material-3 **speed-dial** FAB that fans out the frequent map *actions* — the
 * things a rider **does** (plan a route, round trip, park the bike, rides, saved
 * routes, favourites). This replaces the old habit of burying those actions
 * inside the Settings sheet, which then only holds actual *settings*.
 *
 * Anchored **centre-end** (right edge, vertically centred) so it stays clear of
 * the bottom-right location / record FABs *and* is easy to reach one-handed even
 * with a bar/spider phone mount clamping the bottom of the device. Tapping the
 * main button fans the labelled actions out over a **half-circle** to the left
 * (top → left → bottom) and dims the map with a tap-to-dismiss scrim; picking an
 * action collapses the dial.
 */
@Composable
internal fun BoxScope.MapActionsSpeedDial(
    actions: List<SpeedDialAction>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Tap-to-dismiss scrim behind the expanded actions (no ripple).
    AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { expanded = false }
        )
    }

    // Drives both the fan-out distance and the fade of the mini-FABs so the
    // half-circle opens and closes smoothly (progress 0 = collapsed, 1 = open).
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        label = "speedDialProgress"
    )

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .navigationBarsPadding()
            .padding(end = 16.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        // ── Fanned-out actions (only composed while the dial is opening/open) ──
        if (progress > 0.001f) {
            actions.forEachIndexed { index, action ->
                // Spread the actions across the left half-circle: from straight up
                // (90°) through straight left (180°) to straight down (270°).
                val fraction = if (actions.size == 1) 0.5f
                               else index.toFloat() / (actions.size - 1)
                val angle = Math.toRadians(90.0 + 180.0 * fraction)
                val dx = (FAN_RADIUS.value * cos(angle) * progress).dp
                val dy = (-FAN_RADIUS.value * sin(angle) * progress).dp

                val trigger = { expanded = false; action.onClick() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Anchored centre-end so the mini-FAB (rightmost element) sits at
                    // the FAB's right edge, then offset onto its point on the arc.
                    // The whole row (label + gap + icon) is a single tap target.
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = dx, y = dy)
                        .alpha(progress)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = trigger
                        )
                ) {
                    Surface(
                        onClick = trigger,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        shadowElevation = 3.dp
                    ) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    SmallFloatingActionButton(
                        onClick = trigger,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Icon(action.icon, contentDescription = action.label)
                    }
                }
            }
        }

        // ── Main FAB (centre of the half-circle) ──────────────────────────────
        val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.map_actions),
                modifier = Modifier.rotate(rotation)
            )
        }
    }
}



