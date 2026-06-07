package de.velospot.feature.map.presentation

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.velospot.R
import de.velospot.core.map.NavigationHandler
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedSpaceSheet(
    space: BikeParkingSpace,
    onDismiss: () -> Unit,
    /**
     * Navigation handler — currently an external app redirect (geo: intent).
     * Can be replaced with internal routing at any time:
     *   onNavigate = { navController.navigate(Route.Navigate(it.id)) }
     */
    onNavigate: NavigationHandler,
    isFavorite: Boolean = false,
    onToggleFavorite: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SheetContent(
            space = space,
            onNavigate = onNavigate,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

@Composable
private fun SheetContent(
    space: BikeParkingSpace,
    onNavigate: NavigationHandler,
    isFavorite: Boolean = false,
    onToggleFavorite: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        // ── Type icon + Title + Favorite button ───────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = space.type.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            SheetHeader(
                title = space.name ?: space.type.label(context),
                subtitle = space.type.label(context),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onToggleFavorite(space.id) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) {
                        stringResource(id = R.string.favorites_remove)
                    } else {
                        stringResource(id = R.string.favorites_add)
                    },
                    tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        space.imageUrl?.let { imageUrl ->
            SpotInfoCard {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = space.name ?: stringResource(id = R.string.type_bike_rack),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Detail rows -------------------------------------------------------
        space.address?.let { DetailRow(label = stringResource(id = R.string.detail_address), value = it) }
        space.capacity?.let { DetailRow(label = stringResource(id = R.string.detail_capacity), value = it.toString()) }
        space.operator?.let { DetailRow(label = stringResource(id = R.string.detail_operator), value = it) }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Chip row ----------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            space.capacity?.let {
                MetaInfoChip(label = stringResource(id = R.string.favorites_spaces_format, it))
            }
            MetaInfoChip(label = space.sourceLayer)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Navigation button -------------------------------------------------
        PrimaryActionButton(
            text = stringResource(id = R.string.navigation_start),
            onClick = { onNavigate(space) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            icon = Icons.AutoMirrored.Filled.ArrowForward
        )
    }
}


internal fun BikeParkingType.label(context: Context): String = when (this) {
    BikeParkingType.GARAGE -> context.getString(R.string.type_garage)
    BikeParkingType.BIKE_RACK -> context.getString(R.string.type_bike_rack)
    BikeParkingType.UNKNOWN -> context.getString(R.string.type_unknown)
}

private fun BikeParkingType.icon(): ImageVector = when (this) {
    BikeParkingType.GARAGE    -> Icons.Default.Garage
    BikeParkingType.BIKE_RACK -> Icons.AutoMirrored.Filled.DirectionsBike
    BikeParkingType.UNKNOWN   -> Icons.Default.Place
}





