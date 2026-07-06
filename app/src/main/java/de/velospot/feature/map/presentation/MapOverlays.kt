package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideSpeed
import de.velospot.domain.model.MapError
import kotlin.math.roundToInt

/**
 * How long the navigation pill stays auto-expanded at the start of a trip (so the
 * rider glimpses the route's elevation profile) before it smoothly collapses.
 */
private const val AUTO_COLLAPSE_DELAY_MS = 3_000L

internal data class MapMenuCardState(
    val favoritesCount: Int,
    val isDarkTheme: Boolean,
    val currentLanguageFlag: String,
    val isExpanded: Boolean,
    val offlineRoutingUiState: OfflineRoutingUiState = OfflineRoutingUiState.Disabled,
    /** Whether a bike is currently parked — switches the menu entry park ↔ show. */
    val isBikeParked: Boolean = false,
    /** Whether spoken turn-by-turn voice guidance (TTS) is enabled. */
    val voiceGuidanceEnabled: Boolean = false,
    /** Whether the display is kept awake during navigation / ride recording. */
    val keepScreenOnEnabled: Boolean = true,
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
    val onToggleVoiceGuidance: () -> Unit = {},
    val onToggleKeepScreenOn: () -> Unit = {},
    val onToggleSimulation: () -> Unit = {},
    val onOpenAbout: () -> Unit = {},
    val onOpenRides: () -> Unit = {},
    val onOpenRoundTrip: () -> Unit = {},
    val onStartRoutePlanning: () -> Unit = {},
    val onOpenPlannedRoutes: () -> Unit = {}
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
    progress: de.velospot.core.navigation.NavigationProgress? = null,
    onCancel: () -> Unit = {}
) {
    when (navigationUiState) {
        is NavigationUiState.Idle -> Unit
        is NavigationUiState.Loading -> {
            // Tick an elapsed-seconds counter while the (potentially long) route is
            // computed, so the wait feels alive and the user can cancel it.
            var elapsedSeconds by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    elapsedSeconds++
                }
            }
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
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(id = R.string.navigation_route_loading),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(id = R.string.navigation_elapsed_seconds, elapsedSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(
                        text = stringResource(id = R.string.common_cancel),
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    )
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

            // Slim, glanceable pill that expands on tap (destination name).
            // It starts **expanded** so the rider briefly sees the route's
            // elevation profile before setting off, then auto-collapses smoothly
            // after a short delay. Keyed on the destination so a fresh navigation
            // (or a reroute to a new target) re-triggers the preview.
            val destinationId = navigationUiState.destination.id
            var expanded by remember(destinationId) { mutableStateOf(true) }
            LaunchedEffect(destinationId) {
                kotlinx.coroutines.delay(AUTO_COLLAPSE_DELAY_MS)
                expanded = false
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    id = R.string.navigation_route_summary,
                                    distanceKm,
                                    durationText
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            progress?.currentSpeedMps?.let { speedMps ->
                                Text(
                                    text = formatRideSpeed(speedMps),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FloatingActionButton(
                            onClick = onStopNavigation,
                            modifier = Modifier.size(46.dp),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(id = R.string.navigation_stop)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = destinationName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            RouteElevationProfile(points = navigationUiState.route.points)
                            // BRouter's kinematic model also yields the route's
                            // mechanical work → a rough calorie estimate (kJ ≈ kcal).
                            navigationUiState.route.estimatedKcal?.let { kcal ->
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LocalFireDepartment,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(
                                            id = R.string.navigation_route_calories,
                                            kcal
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

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
    // The clutter that used to live in a dropdown now lives in a dedicated
    // Settings sheet (see SettingsSheet). The top-bar carries just this single
    // tidy menu button, matching the search field's pill shape and elevation.
    Card(
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
            val badged = state.offlineRoutingUiState is OfflineRoutingUiState.Enabled
            IconButton(onClick = actions.onExpand) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_open),
                    tint = if (badged) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Compact right-edge toggle stack shown **only while inspecting a past ride**
 * (below the menu button): one switch for the on-map "max speed" bubble and one
 * for colouring the track by speed. The choices are persisted globally by the
 * caller, so they carry over to the next ride opened.
 */
@Composable
internal fun BoxScope.RideViewOptionsControls(
    visible: Boolean,
    showMaxSpeedBubble: Boolean,
    colorTrackBySpeed: Boolean,
    onToggleMaxSpeedBubble: (Boolean) -> Unit,
    onToggleColorBySpeed: (Boolean) -> Unit,
    colorBySpeedEnabled: Boolean = true
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            // Tucked directly under the 52 dp menu button (top 12 + 52 + 12 gap).
            .padding(top = 76.dp, end = 12.dp),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RideViewOptionRow(
                    icon = Icons.Default.Speed,
                    label = stringResource(R.string.ride_option_max_speed_bubble),
                    checked = showMaxSpeedBubble,
                    onCheckedChange = onToggleMaxSpeedBubble
                )
                RideViewOptionRow(
                    icon = Icons.Default.Gradient,
                    label = stringResource(R.string.ride_option_color_by_speed),
                    checked = colorTrackBySpeed && colorBySpeedEnabled,
                    enabled = colorBySpeedEnabled,
                    onCheckedChange = onToggleColorBySpeed
                )
            }
        }
    }
}

@Composable
private fun RideViewOptionRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (checked) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .alpha(if (enabled) 1f else 0.38f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }
        )
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

/**
 * Dedicated "re-centre & follow" button that **appears** only while a follow
 * session (navigation or ride recording) is running and the user has panned the
 * map away (follow unlocked). Tapping it snaps the camera back onto the live
 * position and resumes following until the user pans again. Stacked on the right
 * edge above the location / record FABs ([bottomPadding] is chosen by the caller
 * so it never overlaps them).
 */
@Composable
internal fun BoxScope.RecenterFollowFab(
    visible: Boolean,
    bottomPadding: Dp,
    onClick: () -> Unit
) {
    if (!visible) return
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = bottomPadding),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = {
            Icon(
                imageVector = Icons.Default.GpsFixed,
                contentDescription = null
            )
        },
        text = { Text(stringResource(id = R.string.map_recenter_follow_short)) }
    )
}

/**
 * Record/stop FAB — same size and right-edge placement as the [MyLocationFab],
 * stacked directly above it. A red dot while idle (tap to start recording) that
 * turns into a stop icon — gently pulsing — while a ride is being recorded.
 * Hidden during active navigation, where the ride is auto-recorded and the
 * navigation card already owns the bottom area.
 */
@Composable
internal fun BoxScope.RecordRideFab(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "recordPulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 0.45f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordPulseAlpha"
    )
    // The idle state is a plain red dot with no icon/label, so the FAB itself
    // carries the accessible name for both states.
    val recordCd = stringResource(
        id = if (isRecording) R.string.ride_stop else R.string.ride_start
    )
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            // Same right inset as MyLocationFab, lifted one FAB height (56 dp) +
            // 16 dp gap + 16 dp base inset so the two buttons stack neatly.
            .padding(end = 16.dp, bottom = 88.dp)
            .semantics { contentDescription = recordCd },
        containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer
                         else MaterialTheme.colorScheme.surface
    ) {
        if (isRecording) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error.copy(alpha = pulse)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}

/**
 * Compact live-stats card shown at the top of the map while a ride is being
 * recorded: running time, distance and current/avg speed, plus stop & discard
 * actions. Suppressed during active navigation (the navigation card takes over,
 * and the ride is auto-recorded in the background).
 */
@Composable
internal fun BoxScope.RideTrackingOverlay(
    stats: de.velospot.domain.model.LiveRideStats,
    onStop: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 72.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.ride_recording),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RideStatCell(
                    label = stringResource(id = R.string.ride_stat_time),
                    value = formatRideDuration(stats.elapsedSeconds)
                )
                RideStatCell(
                    label = stringResource(id = R.string.ride_stat_distance),
                    value = formatRideDistance(stats.distanceMeters)
                )
                RideStatCell(
                    label = stringResource(id = R.string.ride_stat_speed),
                    value = formatRideSpeed(stats.currentSpeedMps)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                SecondaryActionButton(
                    text = stringResource(id = R.string.ride_discard),
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                PrimaryActionButton(
                    text = stringResource(id = R.string.ride_stop),
                    onClick = onStop,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RideStatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
