package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.feature.map.presentation.MapMenuCardActions
import de.velospot.feature.map.presentation.MapMenuCardState
import de.velospot.feature.map.presentation.OfflineRoutingUiState
import de.velospot.feature.map.presentation.headingSemantics

/**
 * The **main Settings sheet** — deliberately short. It holds only *settings*
 * (frequent **actions** live on the map's speed-dial FAB), grouped into a few
 * top-level entries that each open a focused sub-sheet, so the list is scannable
 * at a glance instead of being one long scroll:
 *  - **Appearance & map** → dark mode, language, 2D/3D, layers,
 *  - **Navigation & routing** → voice guidance, keep screen on, offline routing,
 *  - **About**.
 * The debug GPS simulator (debug builds only) stays inline at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    state: MapMenuCardState,
    actions: MapMenuCardActions
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    ModalBottomSheet(onDismissRequest = actions.onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .headingSemantics()
            )

            SettingsRow(
                icon = Icons.Default.Map,
                title = stringResource(R.string.settings_section_appearance),
                onClick = actions.onOpenDisplaySettings,
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )
            SettingsRow(
                icon = Icons.Default.Navigation,
                iconTint = if (state.offlineRoutingUiState is OfflineRoutingUiState.Enabled)
                    MaterialTheme.colorScheme.primary else null,
                title = stringResource(R.string.settings_group_navigation),
                onClick = actions.onOpenNavRouting,
                trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
            )
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.DirectionsBike,
                title = stringResource(R.string.bike_garage_settings_entry),
                onClick = actions.onOpenBikeGarage
            )
            SettingsRow(
                icon = Icons.Default.Info,
                title = stringResource(R.string.menu_about),
                onClick = { actions.onDismiss(); actions.onOpenAbout() }
            )

            // Debug: GPS route simulator (debug builds only).
            if (state.showSimulator) {
                Spacer(Modifier.height(8.dp))
                SettingsSectionHeader(stringResource(R.string.settings_section_debug))
                SettingsRow(
                    icon = if (state.isSimulating) Icons.Default.Stop else Icons.Default.PlayArrow,
                    iconTint = if (state.isSimulating) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary,
                    title = stringResource(
                        when {
                            state.isSimulating     -> R.string.menu_simulate_route_stop
                            state.simulatorEnabled -> R.string.menu_simulate_route_start
                            else                   -> R.string.menu_simulate_route_hint
                        }
                    ),
                    enabled = state.simulatorEnabled,
                    onClick = { actions.onDismiss(); actions.onToggleSimulation() }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Sub-sheet: **Appearance & map** — dark mode, language, 2D/3D map view and the
 * pin layers. Opened from the main Settings sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisplaySettingsSheet(
    state: MapMenuCardState,
    actions: MapMenuCardActions,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

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
                text = stringResource(R.string.settings_section_appearance),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).headingSemantics()
            )
            SettingsRow(
                icon = Icons.Default.DarkMode,
                title = stringResource(
                    if (state.isDarkTheme) R.string.menu_disable_dark_mode
                    else R.string.menu_enable_dark_mode
                ),
                onClick = actions.onToggleDarkMode
            )
            SettingsRow(
                icon = Icons.Default.Language,
                title = stringResource(R.string.menu_language_with_flag, state.currentLanguageFlag),
                onClick = actions.onOpenLanguage
            )
            SettingsRow(
                icon = Icons.Default.ViewInAr,
                title = stringResource(R.string.menu_navigation_view),
                onClick = actions.onOpenNavigationView
            )
            SettingsRow(
                icon = Icons.Default.Layers,
                title = stringResource(R.string.menu_layers),
                onClick = actions.onOpenLayers
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Sub-sheet: **Navigation & routing** — spoken voice guidance, keep-screen-on and
 * the offline routing setup/profile. Opened from the main Settings sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationRoutingSheet(
    state: MapMenuCardState,
    actions: MapMenuCardActions,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

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
                text = stringResource(R.string.settings_group_navigation),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).headingSemantics()
            )
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = stringResource(R.string.menu_voice_guidance),
                onClick = actions.onToggleVoiceGuidance,
                trailing = {
                    Switch(
                        checked = state.voiceGuidanceEnabled,
                        onCheckedChange = { actions.onToggleVoiceGuidance() }
                    )
                }
            )
            SettingsRow(
                icon = Icons.Default.LightMode,
                title = stringResource(R.string.menu_keep_screen_on),
                onClick = actions.onToggleKeepScreenOn,
                trailing = {
                    Switch(
                        checked = state.keepScreenOnEnabled,
                        onCheckedChange = { actions.onToggleKeepScreenOn() }
                    )
                }
            )

            Spacer(Modifier.height(4.dp))
            SettingsSectionHeader(stringResource(R.string.settings_section_routing))
            when (val offState = state.offlineRoutingUiState) {
                is OfflineRoutingUiState.Disabled -> SettingsRow(
                    icon = Icons.Default.SignalWifiOff,
                    title = stringResource(R.string.menu_offline_routing_activate),
                    onClick = { onDismiss(); actions.onActivateOfflineRouting() }
                )
                is OfflineRoutingUiState.Downloading -> SettingsRow(
                    icon = null,
                    leading = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
                    title = stringResource(R.string.menu_offline_routing_downloading),
                    enabled = false,
                    onClick = {}
                )
                is OfflineRoutingUiState.Enabled -> SettingsRow(
                    icon = Icons.Default.Wifi,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(
                        R.string.menu_offline_routing_active,
                        stringResource(offState.profile.displayNameRes)
                    ),
                    onClick = { onDismiss(); actions.onOpenProfileSheet() }
                )
                is OfflineRoutingUiState.DownloadComplete -> SettingsRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.offline_routing_success_title),
                    enabled = false,
                    onClick = {}
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null    -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = (iconTint ?: contentColor)
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        if (trailing != null) {
            trailing()
        }
    }
}
