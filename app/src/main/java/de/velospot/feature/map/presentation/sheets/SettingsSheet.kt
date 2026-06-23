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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
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
 * The single, tidy entry point for everything that used to clutter the top-bar
 * dropdown menu. Groups quick actions (favourites, parked bike, rides) and
 * settings (appearance, map view, layers, offline routing) into a clean modal
 * sheet, so the map stays uncluttered with just a search field + one menu icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    state: MapMenuCardState,
    actions: MapMenuCardActions
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Cap the sheet a little below the status bar and make it scrollable, so a tall
    // settings list never expands all the way to the top — which otherwise makes
    // Material3 paint (animate) the status-bar inset and looks like the top edge
    // "slowly filling in".
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState
    ) {
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

            // ── Quick actions ─────────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_quick_actions))
            SettingsRow(
                icon = Icons.Default.Favorite,
                title = stringResource(R.string.menu_favorites_count, state.favoritesCount),
                onClick = actions.onOpenFavorites
            )
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.DirectionsBike,
                iconTint = if (state.isBikeParked) Color(0xFFF57C00) else null,
                title = stringResource(
                    if (state.isBikeParked) R.string.menu_show_parked_bike
                    else R.string.menu_park_bike_here
                ),
                onClick = {
                    actions.onDismiss()
                    if (state.isBikeParked) actions.onShowParkedBike() else actions.onParkBikeHere()
                }
            )
            SettingsRow(
                icon = Icons.Default.Timeline,
                title = stringResource(R.string.menu_my_rides),
                onClick = { actions.onDismiss(); actions.onOpenRides() }
            )
            SettingsRow(
                icon = Icons.Default.Loop,
                title = stringResource(R.string.round_trip_menu),
                onClick = { actions.onDismiss(); actions.onOpenRoundTrip() }
            )

            Spacer(Modifier.height(8.dp))

            // ── Appearance & map ──────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
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
            SettingsRow(
                icon = Icons.Default.Layers,
                title = stringResource(R.string.menu_layers),
                onClick = actions.onOpenLayers
            )

            Spacer(Modifier.height(8.dp))

            // ── Offline routing ───────────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_routing))
            when (val offState = state.offlineRoutingUiState) {
                is OfflineRoutingUiState.Disabled -> SettingsRow(
                    icon = Icons.Default.SignalWifiOff,
                    title = stringResource(R.string.menu_offline_routing_activate),
                    onClick = { actions.onDismiss(); actions.onActivateOfflineRouting() }
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
                    onClick = { actions.onDismiss(); actions.onOpenProfileSheet() }
                )
                is OfflineRoutingUiState.DownloadComplete -> SettingsRow(
                    icon = Icons.Default.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.offline_routing_success_title),
                    enabled = false,
                    onClick = {}
                )
            }
            SettingsRow(
                icon = Icons.Default.Info,
                title = stringResource(R.string.menu_about),
                onClick = { actions.onDismiss(); actions.onOpenAbout() }
            )

            // ── Debug: GPS route simulator ────────────────────────────────────
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
