package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.data.brouter.BRouterProfile
import de.velospot.data.brouter.ElevationPreference
import kotlin.math.roundToInt


/**
 * Bottom sheet for selecting the active BRouter routing profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingProfileSheet(
    currentProfile: BRouterProfile,
    onSelectProfile: (BRouterProfile) -> Unit,
    currentElevation: ElevationPreference,
    onSelectElevation: (ElevationPreference) -> Unit,
    onDismiss: () -> Unit
) {
    // Open fully (skip the half-height state) so the whole profile list and the
    // hilliness slider are visible at once.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.offline_routing_profile_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.offline_routing_profile_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            BRouterProfile.selectableEntries.forEach { profile ->
                val isSelected = profile == currentProfile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectProfile(profile) }
                        .semantics(mergeDescendants = true) {
                            role = Role.RadioButton
                            selected = isSelected
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(profile.displayNameRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = stringResource(profile.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                HorizontalDivider()
            }

            // ── Route hilliness slider ───────────────────────────────────────
            ElevationPreferenceSlider(
                current = currentElevation,
                onSelect = onSelectElevation
            )
        }
    }
}

/**
 * Discrete slider letting the rider choose how strongly route calculation should
 * avoid climbing ([ElevationPreference]). The current level's label is shown above
 * the slider; only affects offline (BRouter) routes.
 */
@Composable
private fun ElevationPreferenceSlider(
    current: ElevationPreference,
    onSelect: (ElevationPreference) -> Unit
) {
    val levels = ElevationPreference.entries
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.elevation_slider_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(current.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
    Slider(
        value = current.ordinal.toFloat(),
        onValueChange = { value ->
            val index = value.roundToInt().coerceIn(0, levels.lastIndex)
            if (levels[index] != current) onSelect(levels[index])
        },
        valueRange = 0f..levels.lastIndex.toFloat(),
        steps = (levels.size - 2).coerceAtLeast(0)
    )
    Text(
        text = stringResource(R.string.elevation_slider_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
}

/**
 * Alert dialog shown when the user tries to activate offline routing while
 * the device is not connected to Wi-Fi.
 */
@Composable
fun WifiWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.wifi_warning_title)) },
        text  = { Text(stringResource(R.string.wifi_warning_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.wifi_warning_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
