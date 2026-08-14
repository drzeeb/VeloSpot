package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R
import de.velospot.feature.map.presentation.headingSemantics

/**
 * Small chooser opened from the speed-dial "Park bike" action while no bike is
 * parked. Offers two ways to park: save the current spot right away, or navigate
 * to the nearest bike-parking facility. Dismisses on tap-outside / back (and each
 * option dismisses before invoking its action).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParkBikeChooserSheet(
    onParkHere: () -> Unit,
    onFindNearest: () -> Unit,
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
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.park_chooser_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .headingSemantics()
            )
            Spacer(Modifier.height(4.dp))

            ParkOptionRow(
                icon = Icons.Default.LocationOn,
                title = stringResource(R.string.park_chooser_here),
                subtitle = stringResource(R.string.park_chooser_here_sub),
                onClick = { onDismiss(); onParkHere() }
            )
            ParkOptionRow(
                icon = Icons.Default.PedalBike,
                title = stringResource(R.string.park_chooser_find),
                subtitle = stringResource(R.string.park_chooser_find_sub),
                onClick = { onDismiss(); onFindNearest() }
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ParkOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}





