package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.maptiles.OfflineMapRegions
import de.velospot.feature.map.presentation.OfflineRegionsUiState
import de.velospot.feature.map.presentation.headingSemantics

/**
 * The unified **offline usage** manager. Lists every downloaded region (each a
 * combined map-tiles + routing pack), lets the rider add a new region — around their
 * current position **or** a spot they pick on the map — delete regions, and open the
 * routing-profile sheet. Replaces the two separate "offline routing" / "offline map"
 * setup entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineRegionsSheet(
    state: OfflineRegionsUiState,
    onAddCurrentRegion: () -> Unit,
    onPickOnMap: () -> Unit,
    onDeleteRegion: (String) -> Unit,
    onOpenProfileSheet: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.offline_regions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.headingSemantics()
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.offline_regions_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            val downloading = state.downloading
            if (downloading != null) {
                DownloadProgress(downloading)
            } else {
                if (state.regions.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.offline_regions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(state.regions, key = { it.id }) { region ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Map,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = region.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDeleteRegion(region.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.offline_region_delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.totalSizeBytes > 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.offline_regions_total_size,
                                formatSize(state.totalSizeBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                // Coverage hint: the map box is a fixed radius; routing covers much more.
                Text(
                    text = stringResource(
                        R.string.offline_regions_coverage_hint,
                        OfflineMapRegions.DEFAULT_REGION_RADIUS_KM.toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onAddCurrentRegion,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.offline_regions_add))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPickOnMap,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.offline_regions_pick_on_map))
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            // Routing profile — still relevant whenever offline routing data exists.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfileSheet)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.offline_regions_profile),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(state.profile.displayNameRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(downloading: OfflineRegionsUiState.Downloading) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(
                    when (downloading.phase) {
                        OfflineRegionsUiState.Phase.MAP -> R.string.offline_regions_downloading_map
                        OfflineRegionsUiState.Phase.ROUTING -> R.string.offline_regions_downloading_routing
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (downloading.downloadedBytes > 0L) {
                Text(
                    text = "${formatSize(downloading.downloadedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (downloading.fraction >= 0f) {
            LinearProgressIndicator(
                progress = { downloading.fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Formats a byte count as a compact "12.3 MB" / "1.2 GB" string. */
private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}


