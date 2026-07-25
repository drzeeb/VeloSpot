package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.core.format.formatRideElevation
import de.velospot.domain.model.PlannedRoute
import de.velospot.feature.map.presentation.SpotInfoCard
import de.velospot.feature.map.presentation.headingSemantics

/**
 * Lists the rider's saved multi-waypoint routes with per-route actions: ride it
 * forward, ride it reversed (own leaderboard), open its leaderboard, or delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannedRoutesSheet(
    routes: List<PlannedRoute>,
    onDismiss: () -> Unit,
    onRide: (PlannedRoute, Boolean) -> Unit,
    onOpenLeaderboard: (PlannedRoute) -> Unit,
    onShowOnMap: (PlannedRoute) -> Unit,
    onDownloadOffline: (PlannedRoute) -> Unit,
    onDelete: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.route_my_routes_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).headingSemantics()
            )

            if (routes.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_my_routes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(routes, key = { it.id }) { route ->
                        PlannedRouteRow(
                            route = route,
                            onRideForward = { onRide(route, false) },
                            onRideReverse = { onRide(route, true) },
                            onOpenLeaderboard = { onOpenLeaderboard(route) },
                            onShowOnMap = { onShowOnMap(route) },
                            onDownloadOffline = { onDownloadOffline(route) },
                            onDelete = { onDelete(route.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlannedRouteRow(
    route: PlannedRoute,
    onRideForward: () -> Unit,
    onRideReverse: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onShowOnMap: () -> Unit,
    onDownloadOffline: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.confirm_delete_route_title),
            message = stringResource(R.string.confirm_delete_route_message),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }

    SpotInfoCard {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header: route avatar + name + delete ─────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.route_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── Stat pills ───────────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(
                    icon = Icons.Default.Straighten,
                    value = formatRideDistance(route.distanceMeters)
                )
                StatPill(
                    icon = Icons.Default.Terrain,
                    value = "↑ " + formatRideElevation(route.elevationGainMeters)
                )
                StatPill(
                    icon = Icons.Default.Place,
                    value = route.waypoints.size.toString()
                )
            }

            // ── Primary actions ──────────────────────────────────────────────
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRideForward, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsBike,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.route_ride))
                }
                OutlinedButton(onClick = onRideReverse, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.route_ride_reverse))
                }
            }

            // ── Secondary actions (all visible, nothing hidden) ──────────────
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SecondaryAction(
                    icon = Icons.Default.Map,
                    label = stringResource(R.string.route_map_short),
                    onClick = onShowOnMap,
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction(
                    icon = Icons.Default.EmojiEvents,
                    label = stringResource(R.string.route_leaderboard_short),
                    onClick = onOpenLeaderboard,
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction(
                    icon = Icons.Default.CloudDownload,
                    label = stringResource(R.string.route_offline_short),
                    onClick = onDownloadOffline,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** A compact vertical icon-over-label text button for a route's secondary actions. */
@Composable
private fun SecondaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** A compact rounded stat chip: a small leading icon plus its value. */
@Composable
private fun StatPill(icon: ImageVector, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

