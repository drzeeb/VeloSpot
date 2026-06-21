package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.feature.map.presentation.PrimaryActionButton

/** Selectable round-trip target distances (km). */
private val ROUND_TRIP_DISTANCES_KM = listOf(5, 10, 15, 20, 30, 40, 50)

/**
 * Lets the rider generate a circular round-trip from their current position by
 * picking a target distance. Offline-only (BRouter); the route loops back to the
 * start. The chosen distance is approximate — round-trip routing favours a
 * pleasant loop over an exact length.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RoundTripSheet(
    onStart: (distanceMeters: Double) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedKm by remember { mutableIntStateOf(20) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.round_trip_sheet_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.round_trip_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ROUND_TRIP_DISTANCES_KM.forEach { km ->
                    FilterChip(
                        selected = km == selectedKm,
                        onClick = { selectedKm = km },
                        label = { Text(stringResource(R.string.round_trip_distance_km, km)) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            PrimaryActionButton(
                text = stringResource(R.string.round_trip_start),
                onClick = { onStart(selectedKm * 1000.0) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}


