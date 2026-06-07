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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import kotlin.math.roundToInt

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
                text = uiState.message,
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
    onDismissError: () -> Unit
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
                        text = navigationUiState.message,
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
            val distanceKm = navigationUiState.route.distanceMeters / 1000.0
            val durationMin = (navigationUiState.route.durationSeconds / 60.0).roundToInt()
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
                            durationMin
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
internal fun BoxScope.MapMenuCard(
    favoritesCount: Int,
    isDarkTheme: Boolean,
    currentLanguageFlag: String,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLanguage: () -> Unit,
    onToggleDarkMode: () -> Unit
) {
    Card(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box {
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_open),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = onDismiss
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = R.string.menu_favorites_count,
                                favoritesCount
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null
                        )
                    },
                    onClick = onOpenFavorites
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                id = R.string.menu_language_with_flag,
                                currentLanguageFlag
                            )
                        )
                    },
                    leadingIcon = {
                        Text(text = currentLanguageFlag)
                    },
                    onClick = onOpenLanguage
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isDarkTheme) {
                                stringResource(id = R.string.menu_disable_dark_mode)
                            } else {
                                stringResource(id = R.string.menu_enable_dark_mode)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = null
                        )
                    },
                    onClick = onToggleDarkMode
                )
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

