package de.velospot.feature.map.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.AddressSearchResult
import de.velospot.domain.model.RecentDestination

/**
 * Floating address search bar placed at the top of the map.
 *
 * Shows a text field with a result list dropdown.
 * Pass a [modifier] from the parent (e.g. `Modifier.weight(1f)`) to control sizing.
 */
@Composable
internal fun AddressSearchBar(
    modifier: Modifier = Modifier,
    query: String,
    results: List<AddressSearchResult>,
    isSearching: Boolean,
    recentDestinations: List<RecentDestination> = emptyList(),
    onQueryChange: (String) -> Unit,
    onResultSelected: (AddressSearchResult) -> Unit,
    onRecentSelected: (RecentDestination) -> Unit = {},
    onClear: () -> Unit
) {
    val focusManager   = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    val hasResults = results.isNotEmpty()
    val showResults = query.isNotBlank() && (hasResults || isSearching)
    // When the field is focused and empty, initially expand to show the last few
    // destinations the rider navigated to — a one-tap way back to a frequent place.
    val recents = recentDestinations.take(3)
    val showRecents = isFocused && query.isBlank() && recents.isNotEmpty()
    val showDropdown = showResults || showRecents

    Column(modifier = modifier) {
        // ── Search field ──────────────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            TextField(
                value         = query,
                onValueChange = onQueryChange,
                modifier      = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                placeholder   = {
                    Text(
                        text  = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon   = {
                    Icon(
                        imageVector        = Icons.Default.Search,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon  = {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            onClear()
                            focusManager.clearFocus()
                        }) {
                            Icon(
                                imageVector        = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.search_clear),
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                colors        = TextFieldDefaults.colors(
                    focusedContainerColor   = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor   = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                textStyle     = MaterialTheme.typography.bodyMedium
            )
        }

        // ── Results dropdown ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showDropdown,
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            Card(
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                if (hasResults) {
                    LazyColumn {
                        // Photon can return several hits sharing the same
                        // display name AND latitude (e.g. a place plus its
                        // administrative boundary), so a content-based key is not
                        // unique and crashes LazyColumn. The list is ephemeral and
                        // never reordered, so the item index is a safe unique key.
                        itemsIndexed(results, key = { index, _ -> index }) { index, result ->
                            SearchResultItem(
                                result   = result,
                                onClick  = {
                                    onResultSelected(result)
                                    focusManager.clearFocus()
                                }
                            )
                            if (index < results.lastIndex) {
                                HorizontalDivider(
                                    modifier  = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp,
                                    color     = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                } else if (showRecents) {
                    Column {
                        Text(
                            text     = stringResource(R.string.search_recent_title),
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            style    = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color    = MaterialTheme.colorScheme.primary
                        )
                        recents.forEach { destination ->
                            RecentItem(
                                destination = destination,
                                onClick = {
                                    onRecentSelected(destination)
                                    focusManager.clearFocus()
                                }
                            )
                        }
                    }
                } else {
                    // Only shown when isSearching=false and no results
                    if (!isSearching && query.length >= 3) {
                        Text(
                            text     = stringResource(R.string.search_no_results),
                            modifier = Modifier.padding(16.dp),
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: AddressSearchResult,
    onClick: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Announce the whole row as one focusable list item in TalkBack.
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = Icons.Default.LocationOn,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text     = result.displayName,
            style    = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecentItem(
    destination: RecentDestination,
    onClick: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = Icons.Default.History,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text     = destination.name,
            style    = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

