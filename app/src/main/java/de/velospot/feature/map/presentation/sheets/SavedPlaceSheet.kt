package de.velospot.feature.map.presentation.sheets

import de.velospot.feature.map.presentation.*

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.SavedPlace

/**
 * Bottom sheet shown when the user taps a saved place marker.
 * Offers navigation to the place and an option to remove it from favourites.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedPlaceSheet(
    place: SavedPlace,
    onDismiss: () -> Unit,
    onNavigate: (SavedPlace) -> Unit,
    onRemove: (String) -> Unit,
    onShare: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Default.Star,
                    contentDescription = null,
                    tint               = Color(0xFF2E7D32),
                    modifier           = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                SheetHeader(title = place.name)
            }

            place.address?.let { address ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Primary action ───────────────────────────────────────────────
            PrimaryActionButton(
                text     = stringResource(R.string.custom_pin_navigate),
                icon     = Icons.Default.Navigation,
                onClick  = { onNavigate(place) },
                modifier = Modifier.fillMaxWidth()
            )

            // ── Share location ───────────────────────────────────────────────
            onShare?.let { share ->
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    text     = stringResource(R.string.custom_pin_share),
                    icon     = Icons.Default.Share,
                    onClick  = share,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Secondary action ─────────────────────────────────────────────
            TextButton(
                onClick  = { onRemove(place.id) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.favorites_remove),
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

