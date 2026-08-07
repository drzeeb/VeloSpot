package de.velospot.feature.map.presentation.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import de.velospot.R
import de.velospot.core.health.HealthConnectAvailability
import de.velospot.feature.map.presentation.headingSemantics

/**
 * Sub-sheet: **Health Connect** — mirrors the Sensors sheet. Shows the current
 * availability, offers a primary action to grant/manage the write permissions (or
 * install the provider when it's missing) and an opt-in **auto-export** toggle that
 * writes every finished ride into Health Connect automatically.
 *
 * VeloSpot only ever *writes* health data (exercise session + distance, calories,
 * elevation and speed); it never reads any back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthConnectSheet(
    availability: HealthConnectAvailability,
    writePermissions: Set<String>,
    autoExportEnabled: Boolean,
    checkGranted: suspend () -> Boolean,
    onSetAutoExport: (Boolean) -> Unit,
    onInstallHealthConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    var granted by remember { mutableStateOf(false) }
    val available = availability == HealthConnectAvailability.AVAILABLE
    LaunchedEffect(availability) {
        granted = available && checkGranted()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { result ->
        granted = result.containsAll(writePermissions)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.health_connect_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).headingSemantics()
            )
            Text(
                text = stringResource(R.string.health_connect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ── Availability status ──────────────────────────────────────────
            Text(
                text = stringResource(
                    when (availability) {
                        HealthConnectAvailability.AVAILABLE -> R.string.health_connect_status_available
                        HealthConnectAvailability.NOT_INSTALLED -> R.string.health_connect_status_not_installed
                        HealthConnectAvailability.UPDATE_REQUIRED -> R.string.health_connect_status_update_required
                        HealthConnectAvailability.UNSUPPORTED -> R.string.health_connect_status_unsupported
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            when {
                available -> {
                    Button(
                        onClick = { permissionLauncher.launch(writePermissions) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Text(
                            stringResource(
                                if (granted) R.string.health_connect_manage_permissions
                                else R.string.health_connect_grant_permissions
                            )
                        )
                    }
                }
                availability.isInstallable -> {
                    Button(
                        onClick = onInstallHealthConnect,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Text(stringResource(R.string.health_connect_install_action))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Auto-export toggle ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.health_connect_auto_export_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoExportEnabled,
                    onCheckedChange = onSetAutoExport
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

