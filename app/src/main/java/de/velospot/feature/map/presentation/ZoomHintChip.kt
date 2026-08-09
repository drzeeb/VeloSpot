package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import kotlinx.coroutines.delay

/** How long the hint lingers on screen (ms) before auto-fading, even while still zoomed out. */
private const val ZOOM_HINT_AUTO_HIDE_MS = 4_000L

/**
 * Subtle, non-blocking on-map hint telling the user to zoom in to reveal parking
 * markers. Replaces the old intrusive [android.widget.Toast].
 *
 * Presence is **state-driven** by [visible] (the zoomed-out-for-parking boolean),
 * so it never stacks or re-interrupts on every zoom change: it simply fades in
 * while zoomed out and fades out when the user zooms back in. As a courtesy it
 * also auto-hides after [ZOOM_HINT_AUTO_HIDE_MS] so it does not linger forever,
 * without re-popping on tiny zoom deltas (it is keyed on the boolean transition,
 * not the raw zoom level).
 *
 * Styling mirrors the map's other rounded overlay pills (e.g. [WeatherChip]) so it
 * respects light / dark / AMOLED theming.
 */
@Composable
internal fun ZoomHintChip(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    // Auto-hide latch: reset whenever the zoomed-out transition fires, then release
    // after a short delay. Keyed on `visible` only, so ordinary zoom jitter (which
    // does not flip the boolean) never re-arms or re-pops the hint.
    var autoHidden by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            autoHidden = false
            delay(ZOOM_HINT_AUTO_HIDE_MS)
            autoHidden = true
        } else {
            autoHidden = false
        }
    }

    AnimatedVisibility(
        visible = visible && !autoHidden,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 }
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ZoomIn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.zoom_in_for_parking),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

