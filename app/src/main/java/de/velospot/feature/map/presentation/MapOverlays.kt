package de.velospot.feature.map.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.MapError
import kotlin.math.roundToInt

internal data class MapMenuCardState(
    val favoritesCount: Int,
    val isDarkTheme: Boolean,
    val currentLanguageFlag: String,
    val isExpanded: Boolean,
    val offlineRoutingUiState: OfflineRoutingUiState = OfflineRoutingUiState.Disabled,
    /** Whether a bike is currently parked — switches the menu entry park ↔ show. */
    val isBikeParked: Boolean = false,
    /** Debug-only: show the GPS route-simulator entry (debug builds only). */
    val showSimulator: Boolean = false,
    /** Debug-only: whether a route is available to simulate (active navigation). */
    val simulatorEnabled: Boolean = false,
    /** Debug-only: whether the GPS route simulator is currently running. */
    val isSimulating: Boolean = false
)

internal data class MapMenuCardActions(
    val onExpand: () -> Unit,
    val onDismiss: () -> Unit,
    val onOpenFavorites: () -> Unit,
    val onOpenLanguage: () -> Unit,
    val onToggleDarkMode: () -> Unit,
    val onOpenLayers: () -> Unit = {},
    val onOpenNavigationView: () -> Unit = {},
    val onActivateOfflineRouting: () -> Unit = {},
    val onOpenProfileSheet: () -> Unit = {},
    val onParkBikeHere: () -> Unit = {},
    val onShowParkedBike: () -> Unit = {},
    val onToggleSimulation: () -> Unit = {}
)

@Composable
private fun MapError.toUserMessage(): String = when (this) {
    is MapError.NetworkUnavailable     -> stringResource(id = R.string.error_network_unavailable)
    is MapError.LocationUnavailable    -> stringResource(id = R.string.error_location_unavailable)
    is MapError.RoutingFailed          -> stringResource(id = R.string.error_routing_failed)
    is MapError.NoRouteFound           -> stringResource(id = R.string.error_no_route_found)
    is MapError.EmptyRouteGeometry     -> stringResource(id = R.string.error_route_geometry_empty)
    is MapError.BRouterProfilesMissing -> stringResource(id = R.string.error_brouter_profiles_missing)
    is MapError.NoInternetConnection   -> stringResource(id = R.string.error_no_internet)
    is MapError.Unknown                -> stringResource(id = R.string.error_unknown)
}

/**
 * Shown on the map while offline routing segment files are being downloaded
 * during the activation flow (not during navigation).
 */
@Composable
internal fun BoxScope.OfflineSetupProgressOverlay(
    state: OfflineRoutingUiState.Downloading
) {
    Card(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        ),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.offline_routing_downloading_title),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(Modifier.height(8.dp))

            // "Datei X von Y" + MB-Zähler
            if (state.totalFiles > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(
                            R.string.offline_routing_file_of,
                            state.currentFileIndex,
                            state.totalFiles
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.downloadedBytes > 0L) {
                        Text(
                            text = if (state.totalBytes > 0L)
                                "${formatMb(state.downloadedBytes)} MB / ${formatMb(state.totalBytes)} MB"
                            else
                                "${formatMb(state.downloadedBytes)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state.currentFile.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.currentFile,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.fileProgress >= 0f) {
                LinearProgressIndicator(progress = { state.fileProgress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Brief success overlay shown for ~2.5 s after all segment files downloaded.
 */
@Composable
internal fun BoxScope.OfflineSetupSuccessOverlay() {
    Card(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.offline_routing_success_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.offline_routing_success_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun formatMb(bytes: Long): String = "%.1f".format(bytes / (1024.0 * 1024.0))

@Composable
internal fun BoxScope.MapStatusOverlay(uiState: MapUiState) {
    if (uiState is MapUiState.Loading) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }

    if (uiState is MapUiState.Error) {
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = uiState.error.toUserMessage(),
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
internal fun BoxScope.MapNavigationOverlay(
    navigationUiState: NavigationUiState,
    onStopNavigation: () -> Unit,
    onDismissError: () -> Unit,
    progress: de.velospot.core.navigation.NavigationProgress? = null
) {
    when (navigationUiState) {
        is NavigationUiState.Idle -> Unit
        is NavigationUiState.Loading -> {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(text = stringResource(id = R.string.navigation_route_loading))
                }
            }
        }

        is NavigationUiState.DownloadingSegments -> {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(id = R.string.navigation_downloading_segments),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val progress = navigationUiState.progress
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        is NavigationUiState.Error -> {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = navigationUiState.error.toUserMessage(),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(
                        text = stringResource(id = R.string.common_ok),
                        onClick = onDismissError
                    )
                }
            }
        }

        is NavigationUiState.Active -> {
            // Prefer the live, dynamically-shrinking distance + ETA from the
            // NavigationManager; fall back to the static BRouter totals before the
            // first GPS fix arrives.
            val distanceMeters = progress?.remainingMeters ?: navigationUiState.route.distanceMeters
            val durationSecs   = progress?.remainingSeconds ?: navigationUiState.route.durationSeconds
            val distanceKm  = distanceMeters / 1000.0
            val totalMin    = (durationSecs / 60.0).roundToInt()
            val durationText = if (totalMin < 60) {
                stringResource(R.string.duration_minutes, totalMin)
            } else {
                val h   = totalMin / 60
                val min = totalMin % 60
                if (min == 0) stringResource(R.string.duration_hours, h)
                else          stringResource(R.string.duration_hours_minutes, h, min)
            }
            val context = LocalContext.current
            val destinationName = navigationUiState.destination.name
                ?: navigationUiState.destination.type.label(context)

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = destinationName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            id = R.string.navigation_route_summary,
                            distanceKm,
                            durationText
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (progress?.isOffRoute == true) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.navigation_rerouting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    PrimaryActionButton(
                        text = stringResource(id = R.string.navigation_stop),
                        onClick = onStopNavigation
                    )
                }
            }
        }
    }
}

@Composable
internal fun MapMenuCard(
    modifier: Modifier = Modifier,
    state: MapMenuCardState,
    actions: MapMenuCardActions
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box {
            IconButton(onClick = actions.onExpand) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_open),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            DropdownMenu(
                expanded = state.isExpanded,
                onDismissRequest = actions.onDismiss
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = R.string.menu_favorites_count,
                                state.favoritesCount
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null
                        )
                    },
                    onClick = actions.onOpenFavorites
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.menu_layers)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Layers, contentDescription = null)
                    },
                    onClick = actions.onOpenLayers
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.menu_navigation_view)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.ViewInAr, contentDescription = null)
                    },
                    onClick = actions.onOpenNavigationView
                )

                // ── Parked bike (where the user left their bike) ──────────────
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = if (state.isBikeParked) R.string.menu_show_parked_bike
                                     else R.string.menu_park_bike_here
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                            contentDescription = null,
                            tint = if (state.isBikeParked) Color(0xFFF57C00)
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        actions.onDismiss()
                        if (state.isBikeParked) actions.onShowParkedBike() else actions.onParkBikeHere()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = R.string.menu_language_with_flag,
                                state.currentLanguageFlag
                            )
                        )
                    },
                    leadingIcon = {
                        Text(text = state.currentLanguageFlag)
                    },
                    onClick = actions.onOpenLanguage
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (state.isDarkTheme) {
                                stringResource(id = R.string.menu_disable_dark_mode)
                            } else {
                                stringResource(id = R.string.menu_enable_dark_mode)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.DarkMode, contentDescription = null)
                    },
                    onClick = actions.onToggleDarkMode
                )

                HorizontalDivider()

                // ── Offline routing ───────────────────────────────────────────
                when (val offState = state.offlineRoutingUiState) {
                    is OfflineRoutingUiState.Disabled -> {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_offline_routing_activate)) },
                            leadingIcon = { Icon(Icons.Default.SignalWifiOff, contentDescription = null) },
                            onClick = { actions.onDismiss(); actions.onActivateOfflineRouting() }
                        )
                    }
                    is OfflineRoutingUiState.Downloading -> {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_offline_routing_downloading)) },
                            leadingIcon = { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) },
                            onClick = {},
                            enabled = false
                        )
                    }
                    is OfflineRoutingUiState.Enabled -> {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.menu_offline_routing_active,
                                        stringResource(offState.profile.displayNameRes)
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { actions.onDismiss(); actions.onOpenProfileSheet() }
                        )
                    }
                    is OfflineRoutingUiState.DownloadComplete -> {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.offline_routing_success_title)) },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {},
                            enabled = false
                        )
                    }
                }

                // ── Debug: GPS route simulator (couch testing) ────────────────
                if (state.showSimulator) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        enabled = state.simulatorEnabled,
                        text = {
                            Text(
                                stringResource(
                                    when {
                                        state.isSimulating     -> R.string.menu_simulate_route_stop
                                        state.simulatorEnabled -> R.string.menu_simulate_route_start
                                        else                   -> R.string.menu_simulate_route_hint
                                    }
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.isSimulating) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (state.isSimulating) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = { actions.onDismiss(); actions.onToggleSimulation() }
                    )
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.MyLocationFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp),
        containerColor = MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = stringResource(id = R.string.map_center_on_my_location),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

