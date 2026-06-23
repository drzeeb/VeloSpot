package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.core.map.NavigationHandler
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.SavedPlace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesSheet(
    spaces: List<BikeParkingSpace>,
    favoriteIds: List<String>,
    savedPlaces: List<SavedPlace>,
    onDismiss: () -> Unit,
    onStartNavigation: NavigationHandler,
    onShowDetails: NavigationHandler,
    onToggleFavorite: (String) -> Unit,
    onNavigateSavedPlace: (SavedPlace) -> Unit,
    onShowSavedPlace: (SavedPlace) -> Unit,
    onRemoveSavedPlace: (String) -> Unit
) {
    val context = LocalContext.current
    val favoriteSpaces = remember(spaces, favoriteIds) {
        spaces.filter { favoriteIds.contains(it.id) }
            .sortedBy { it.name ?: it.type.label(context) }
    }
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
                text = stringResource(id = R.string.favorites_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.headingSemantics()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (favoriteSpaces.isEmpty() && savedPlaces.isEmpty()) {
                    stringResource(id = R.string.favorites_empty_text)
                } else {
                    stringResource(id = R.string.favorites_hint_text)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (favoriteSpaces.isEmpty() && savedPlaces.isEmpty()) {
                SpotInfoCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.favorites_empty_card_text),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (savedPlaces.isNotEmpty()) {
                        item(key = "saved-places-header") {
                            Text(
                                text = stringResource(id = R.string.favorites_saved_places_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(savedPlaces, key = { "saved-${it.id}" }) { place ->
                            SavedPlaceCard(
                                place = place,
                                onNavigate = onNavigateSavedPlace,
                                onShowDetails = onShowSavedPlace,
                                onRemove = onRemoveSavedPlace
                            )
                        }
                    }
                    items(favoriteSpaces, key = { it.id }) { space ->
                        FavoriteSpaceCard(
                            space = space,
                            onStartNavigation = onStartNavigation,
                            onShowDetails = onShowDetails,
                            onToggleFavorite = onToggleFavorite
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPlaceCard(
    place: SavedPlace,
    onNavigate: (SavedPlace) -> Unit,
    onShowDetails: (SavedPlace) -> Unit,
    onRemove: (String) -> Unit
) {
    SpotInfoCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32)
                    )
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = { onRemove(place.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.favorites_remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            place.address?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimaryActionButton(
                    text = stringResource(id = R.string.navigation_start),
                    onClick = { onNavigate(place) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Filled.ArrowForward
                )
                SecondaryActionButton(
                    text = stringResource(id = R.string.favorites_show_spot),
                    onClick = { onShowDetails(place) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FavoriteSpaceCard(
    space: BikeParkingSpace,
    onStartNavigation: NavigationHandler,
    onShowDetails: NavigationHandler,
    onToggleFavorite: (String) -> Unit
) {
    val context = LocalContext.current
    SpotInfoCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = space.name ?: space.type.label(context),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = { onToggleFavorite(space.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.favorites_remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            space.address?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                space.capacity?.let { cap ->
                    MetaInfoChip(
                        label = stringResource(id = R.string.favorites_spaces_format, cap)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimaryActionButton(
                    text = stringResource(id = R.string.navigation_start),
                    onClick = { onStartNavigation(space) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Filled.ArrowForward
                )
                SecondaryActionButton(
                    text = stringResource(id = R.string.favorites_show_spot),
                    onClick = { onShowDetails(space) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

