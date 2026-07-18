package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(routes, key = { it.id }) { route ->
                        PlannedRouteRow(
                            route = route,
                            onRideForward = { onRide(route, false) },
                            onRideReverse = { onRide(route, true) },
                            onOpenLeaderboard = { onOpenLeaderboard(route) },
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
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenLeaderboard) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = stringResource(R.string.route_leaderboard_open))
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.route_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.route_stats_line,
                    formatRideDistance(route.distanceMeters),
                    route.waypoints.size,
                    formatRideElevation(route.elevationGainMeters)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRideForward) {
                    Text(stringResource(R.string.route_ride))
                }
                TextButton(onClick = onRideReverse) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(stringResource(R.string.route_ride_reverse))
                }
            }
        }
    }
}

