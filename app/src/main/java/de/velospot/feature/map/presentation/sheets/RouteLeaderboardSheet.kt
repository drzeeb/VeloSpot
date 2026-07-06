package de.velospot.feature.map.presentation.sheets

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.analysis.LeaderboardEntry
import de.velospot.core.analysis.RouteLeaderboard
import de.velospot.core.format.formatRideDuration
import de.velospot.core.format.formatRideSpeed
import de.velospot.domain.model.PlannedRoute
import de.velospot.domain.model.RouteAttempt
import de.velospot.feature.map.presentation.SpotInfoCard
import de.velospot.feature.map.presentation.headingSemantics
import java.text.DateFormat
import java.util.Date

/**
 * A planned route's leaderboard — the rider's own attempts ranked by time. The
 * **forward** and **reverse** rides are shown on separate tabs because reversing
 * the route swaps its climbs and descents, so the achievable time differs and one
 * combined ranking would be unfair.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteLeaderboardSheet(
    route: PlannedRoute,
    attempts: List<RouteAttempt>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val (forward, reverse) = remember(attempts) { RouteLeaderboard.split(attempts) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val entries = if (selectedTab == 0) forward else reverse

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.route_leaderboard_title, route.name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).headingSemantics()
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.route_leaderboard_forward, forward.size)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.route_leaderboard_reverse, reverse.size)) }
                )
            }

            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_leaderboard_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.attempt.id }) { entry ->
                        LeaderboardRow(entry)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    val attempt = entry.attempt
    SpotInfoCard {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (entry.isBest) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = entry.rank.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.isBest) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(0.dp))
            Column(modifier = Modifier.padding(start = 12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatRideDuration(attempt.elapsedSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatRideSpeed(attempt.avgSpeedMps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(Date(attempt.recordedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

