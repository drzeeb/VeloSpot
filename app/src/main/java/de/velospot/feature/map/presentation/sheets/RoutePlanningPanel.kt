package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.format.formatRideDistance
import de.velospot.domain.model.BikeRoute
import de.velospot.domain.model.RouteWaypoint
import de.velospot.feature.map.presentation.headingSemantics

/**
 * Non-modal planning panel shown at the bottom of the map while route-planning
 * mode is active. It is non-modal on purpose (no scrim) so the map stays tappable
 * — each tap on an empty spot drops the next waypoint. Shows the running distance
 * / elevation of the routed preview and lets the rider undo the last stop, cancel,
 * or save the route once at least two stops make a routable line.
 */
@Composable
internal fun RoutePlanningPanel(
    waypoints: List<RouteWaypoint>,
    previewRoute: BikeRoute?,
    isComputing: Boolean,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
            ),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = stringResource(R.string.route_plan_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.headingSemantics()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.route_plan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.route_plan_stops, waypoints.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    when {
                        isComputing -> {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp).height(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        previewRoute != null -> {
                            Text(
                                text = formatRideDistance(previewRoute.distanceMeters),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            previewRoute.estimatedKcal?.let { kcal ->
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "≈ $kcal kcal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        waypoints.size < 2 -> Text(
                            text = stringResource(R.string.route_plan_need_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.route_plan_cancel))
                    }
                    OutlinedButton(
                        onClick = onUndo,
                        enabled = waypoints.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.route_plan_undo))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onSave,
                        enabled = previewRoute != null && !isComputing
                    ) {
                        Text(stringResource(R.string.route_plan_save))
                    }
                }
            }
        }
    }
}



