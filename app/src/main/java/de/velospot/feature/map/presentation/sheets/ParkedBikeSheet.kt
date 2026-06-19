package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.navigation.GeoMath
import de.velospot.domain.model.GeoCoordinate
import de.velospot.domain.model.ParkedBike
import kotlin.math.roundToInt

/**
 * Bottom sheet shown when the user taps the parked-bike marker (or opens it from
 * the menu). Surfaces how long ago the bike was parked, how far away it is, and
 * offers to navigate back to it or mark it as collected ("Fahrrad abgeholt").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParkedBikeSheet(
    bike: ParkedBike,
    userLocation: GeoCoordinate?,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
    onPickUp: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    val parkedAgoText = rememberParkedAgoText(bike.parkedAt)
    val distanceText = userLocation?.let {
        formatDistance(
            GeoMath.distanceMeters(it.latitude, it.longitude, bike.latitude, bike.longitude)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.DirectionsBike,
                    contentDescription = null,
                    tint               = Color(0xFFF57C00),
                    modifier           = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                SheetHeader(
                    title    = stringResource(R.string.parked_bike_title),
                    subtitle = parkedAgoText
                )
            }

            // ── Address ──────────────────────────────────────────────────────
            bike.address?.let { address ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Distance chip ────────────────────────────────────────────────
            distanceText?.let {
                Spacer(Modifier.height(12.dp))
                MetaInfoChip(label = stringResource(R.string.parked_bike_distance_away, it))
            }

            Spacer(Modifier.height(20.dp))

            // ── Primary action: navigate back to the bike ────────────────────
            PrimaryActionButton(
                text     = stringResource(R.string.parked_bike_navigate),
                icon     = Icons.Default.Navigation,
                onClick  = onNavigate,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // ── Secondary action: pick the bike up (clears the marker) ───────
            TextButton(
                onClick  = onPickUp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.parked_bike_pick_up),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Human-readable "parked X ago" line, e.g. "Just parked" / "Parked 12 min ago". */
@Composable
private fun rememberParkedAgoText(parkedAt: Long): String {
    val elapsedMin = ((System.currentTimeMillis() - parkedAt) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        elapsedMin < 1   -> stringResource(R.string.parked_bike_just_now)
        elapsedMin < 60  -> stringResource(
            R.string.parked_bike_parked_ago,
            stringResource(R.string.duration_minutes, elapsedMin)
        )
        else -> {
            val h = elapsedMin / 60
            val m = elapsedMin % 60
            val duration = if (m == 0) stringResource(R.string.duration_hours, h)
                           else stringResource(R.string.duration_hours_minutes, h, m)
            stringResource(R.string.parked_bike_parked_ago, duration)
        }
    }
}

/** Formats a metre distance as "350 m" (< 1 km) or "1.2 km". */
private fun formatDistance(meters: Double): String =
    if (meters < 1_000) "${meters.roundToInt()} m"
    else "%.1f km".format(meters / 1_000.0)

