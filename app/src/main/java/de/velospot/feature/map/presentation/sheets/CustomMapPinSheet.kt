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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.domain.model.GeoCoordinate

/**
 * Bottom sheet shown for a freely placed map pin — either a location tapped on the
 * map ("custom pin") or a selected address-search result. Both share the exact same
 * card and actions: navigate, save as favourite and remove the pin.
 *
 * Shows the resolved address from Nominatim (or the raw coordinates as fallback while loading).
 *
 * @param pin       The pinned coordinate.
 * @param address   The resolved address string, or null while it is still loading.
 * @param title     Sheet header title (defaults to the custom-pin title).
 * @param subtitle  Optional hint shown under the header; pass null to hide it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomMapPinSheet(
    pin: GeoCoordinate,
    address: String?,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
    onRemove: () -> Unit,
    onSaveAsFavorite: () -> Unit,
    onParkBikeHere: (() -> Unit)? = null,
    title: String = stringResource(R.string.custom_pin_title),
    subtitle: String? = stringResource(R.string.custom_pin_subtitle)
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
                    imageVector        = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                SheetHeader(title = title)
            }

            // ── Hint ─────────────────────────────────────────────────────────
            subtitle?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Address or coordinates ────────────────────────────────────────
            when {
                address != null -> {
                    // Resolved address from Nominatim
                    Text(
                        text  = address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                else -> {
                    // Still loading – show spinner next to raw coordinates
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color       = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text  = "%.5f, %.5f".format(pin.latitude, pin.longitude),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Primary action ───────────────────────────────────────────────
            PrimaryActionButton(
                text     = stringResource(R.string.custom_pin_navigate),
                icon     = Icons.Default.Navigation,
                onClick  = onNavigate,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // ── Save as favourite ────────────────────────────────────────────
            SecondaryActionButton(
                text     = stringResource(R.string.save_place_as_favorite),
                icon     = Icons.Default.StarBorder,
                onClick  = onSaveAsFavorite,
                modifier = Modifier.fillMaxWidth()
            )

            // ── Park bike here (only offered for the freely-placed custom pin) ─
            onParkBikeHere?.let { park ->
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    text     = stringResource(R.string.parked_bike_park_here),
                    icon     = Icons.AutoMirrored.Filled.DirectionsBike,
                    onClick  = park,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Secondary action ─────────────────────────────────────────────
            TextButton(
                onClick  = onRemove,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.custom_pin_remove),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}
