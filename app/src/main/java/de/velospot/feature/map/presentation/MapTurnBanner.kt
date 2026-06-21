package de.velospot.feature.map.presentation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.navigation.NavigationProgress
import kotlin.math.abs
import kotlin.math.roundToInt

/** Don't surface a turn until it is within this distance — keeps the banner timely. */
private const val TURN_BANNER_MAX_DISTANCE_M = 400.0

/**
 * Turn-by-turn banner shown at the top during active navigation. Derived purely
 * from the route geometry ([NavigationProgress.nextTurnAngleDegrees] /
 * [NavigationProgress.nextTurnDistanceMeters]), so it works for both BRouter
 * offline and OSRM online routes. Animates in/out as a turn approaches.
 */
@Composable
internal fun BoxScope.MapTurnBanner(progress: NavigationProgress?) {
    val distance = progress?.nextTurnDistanceMeters
    val angle = progress?.nextTurnAngleDegrees
    val visible = distance != null && angle != null &&
        distance <= TURN_BANNER_MAX_DISTANCE_M && !progress.isOffRoute

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        if (distance == null || angle == null) return@AnimatedVisibility
        Card(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 78.dp, start = 16.dp, end = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    // Rotate the "straight ahead" arrow by the signed turn angle so
                    // it visually points left/right (clamped for very sharp turns).
                    modifier = Modifier
                        .size(34.dp)
                        .rotate(angle.coerceIn(-135.0, 135.0).toFloat())
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.nav_turn_in, formatTurnDistance(distance)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(maneuverLabel(angle)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/** Localised maneuver label from the signed turn angle (negative = left). */
@StringRes
private fun maneuverLabel(angle: Double): Int {
    val left = angle < 0
    val mag = abs(angle)
    return when {
        mag < 50   -> if (left) R.string.nav_turn_slight_left else R.string.nav_turn_slight_right
        mag <= 115 -> if (left) R.string.nav_turn_left else R.string.nav_turn_right
        else       -> if (left) R.string.nav_turn_sharp_left else R.string.nav_turn_sharp_right
    }
}

/** "120 m" (rounded to 10 m) below 1 km, otherwise "1.4 km". */
private fun formatTurnDistance(meters: Double): String =
    if (meters < 1000) "${(meters / 10).roundToInt() * 10} m"
    else "%.1f km".format(meters / 1000.0)
