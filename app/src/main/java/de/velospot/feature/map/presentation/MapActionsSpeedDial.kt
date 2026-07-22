package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R

/** One entry of the map actions speed-dial: a labelled mini-FAB. */
internal data class SpeedDialAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/** Vertical spacing between neighbouring actions when expanded. */
private val ITEM_SPACING = 58.dp

/** Base horizontal gap the whole stack keeps from the FAB's edge. */
private val STACK_OFFSET = 32.dp

/** How far the middle of the stack bows out (left) beyond the base offset. */
private val STACK_BOW = 46.dp

/** Maximum gentle tilt (degrees) applied to the outermost entries. */
private const val MAX_TILT = 8f

/**
 * A Material-3 **speed-dial** FAB that fans out the frequent map *actions* — the
 * things a rider **does** (plan a route, round trip, park the bike, rides, saved
 * routes, favourites). This replaces the old habit of burying those actions
 * inside the Settings sheet, which then only holds actual *settings*.
 *
 * Anchored **centre-end** (right edge, vertically centred) so it stays clear of
 * the bottom-right location / record FABs *and* is easy to reach one-handed even
 * with a bar/spider phone mount clamping the bottom of the device. Tapping the
 * main button smoothly springs a compact, slightly **bowed stack** of labelled
 * actions out next to it — each entry is *gently tilted* (a few degrees, never
 * so much that the label turns hard to read) so the group fans subtly without
 * overlapping. A tap-to-dismiss scrim dims the map; picking an action collapses
 * the dial.
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

    // Drives the spread, tilt and fade of the entries. A gentle spring keeps the
    // open/close motion smooth (progress 0 = collapsed, 1 = fully open).
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
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
            val centreIndex = (actions.size - 1) / 2f
            actions.forEachIndexed { index, action ->
                // Position relative to the FAB: entries above (rel < 0) and below
                // (rel > 0) the vertically-centred main button. Fixed spacing keeps
                // them from overlapping; the whole stack sits a base gap away from
                // the FAB and bows further out (largest in the middle) into a curve.
                val rel = index - centreIndex
                val norm = if (centreIndex == 0f) 0f else rel / centreIndex // -1..1
                val dy = (rel * ITEM_SPACING.value * progress).dp
                val dx = (-(STACK_OFFSET.value + STACK_BOW.value * (1f - norm * norm)) * progress).dp

                // Gentle tilt: top entries lean up, bottom entries lean down, and
                // it stays subtle so labels remain easy to read. Pivots around the
                // mini-FAB (right edge) so the icons keep their clean vertical line.
                val rowRotation = (-norm * MAX_TILT * progress)

                val trigger = { expanded = false; action.onClick() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Anchored centre-end so the mini-FAB (rightmost element) sits at
                    // the FAB's right edge, then offset onto its point in the stack.
                    // The whole row (label + gap + icon) is a single tap target.
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = dx, y = dy)
                        .graphicsLayer {
                            rotationZ = rowRotation
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
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



