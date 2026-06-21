package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.map.LayerVisibility
import de.velospot.core.map.MapLayerCategory

private val ParkingColor = Color(0xFF1565C0)
private val FavoriteColor = Color(0xFFD32F2F)
private val SavedColor = Color(0xFF2E7D32)
private val HeatmapColor = Color(0xFFE65100)

/**
 * Bottom sheet to toggle which pin categories ("layers") are shown on the map.
 * Each layer is a tappable card with a coloured pin badge and a switch; active
 * cards are tinted in the layer's accent colour for an at-a-glance overview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayersSheet(
    visibility: LayerVisibility,
    onToggle: (MapLayerCategory, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.layers_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.layers_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            LayerToggleCard(
                icon = Icons.AutoMirrored.Filled.DirectionsBike,
                accent = ParkingColor,
                title = stringResource(id = R.string.layers_parking_title),
                description = stringResource(id = R.string.layers_parking_desc),
                checked = visibility.showParking,
                onCheckedChange = { onToggle(MapLayerCategory.PARKING, it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LayerToggleCard(
                icon = Icons.Default.Favorite,
                accent = FavoriteColor,
                title = stringResource(id = R.string.layers_favorites_title),
                description = stringResource(id = R.string.layers_favorites_desc),
                checked = visibility.showFavorites,
                onCheckedChange = { onToggle(MapLayerCategory.FAVORITES, it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LayerToggleCard(
                icon = Icons.Default.Star,
                accent = SavedColor,
                title = stringResource(id = R.string.layers_saved_title),
                description = stringResource(id = R.string.layers_saved_desc),
                checked = visibility.showSavedPlaces,
                onCheckedChange = { onToggle(MapLayerCategory.SAVED_PLACES, it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LayerToggleCard(
                icon = Icons.Default.LocalFireDepartment,
                accent = HeatmapColor,
                title = stringResource(id = R.string.layers_heatmap_title),
                description = stringResource(id = R.string.layers_heatmap_desc),
                checked = visibility.showHeatmap,
                onCheckedChange = { onToggle(MapLayerCategory.HEATMAP, it) }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayerToggleCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                accent.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ),
        border = if (checked) BorderStroke(1.dp, accent.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (checked) accent else accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
