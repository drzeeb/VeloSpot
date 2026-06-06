package de.velospot.feature.map.presentation

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
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.velospot.core.map.NavigationHandler
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedSpaceSheet(
    space: BikeParkingSpace,
    onDismiss: () -> Unit,
    /**
     * Navigations-Handler — aktuell eine externe App-Weiterleitung (geo:-Intent).
     * Kann jederzeit durch eine interne Routing-Funktion ersetzt werden:
     *   onNavigate = { navController.navigate(Route.Navigate(it.id)) }
     */
    onNavigate: NavigationHandler
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SheetContent(
            space = space,
            onNavigate = onNavigate,
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

@Composable
private fun SheetContent(
    space: BikeParkingSpace,
    onNavigate: NavigationHandler,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        // ── Typ-Icon + Titel ───────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = space.type.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = space.name ?: space.type.label(),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = space.type.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // ── Detailzeilen ───────────────────────────────────────────────────────
        space.address?.let { DetailRow(label = "Adresse", value = it) }
        space.capacity?.let { DetailRow(label = "Stellplätze", value = it.toString()) }
        space.operator?.let { DetailRow(label = "Betreiber", value = it) }
        DetailRow(
            label = "Überdacht",
            value = when (space.isCovered) {
                true  -> "Ja"
                false -> "Nein"
                null  -> "Unbekannt"
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Chip-Zeile ─────────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            space.capacity?.let { InfoChip(label = "$it Stellplätze") }
            if (space.isCovered == true) InfoChip(label = "Überdacht")
            InfoChip(label = space.sourceLayer)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Navigations-Button ─────────────────────────────────────────────────
        Button(
            onClick = { onNavigate(space) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Navigation starten", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun InfoChip(label: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

internal fun BikeParkingType.label(): String = when (this) {
    BikeParkingType.GARAGE    -> "Fahrradgarage"
    BikeParkingType.BIKE_RACK -> "Fahrradbügel"
    BikeParkingType.UNKNOWN   -> "Abstellanlage"
}

private fun BikeParkingType.icon(): ImageVector = when (this) {
    BikeParkingType.GARAGE    -> Icons.Default.Garage
    BikeParkingType.BIKE_RACK -> Icons.AutoMirrored.Filled.DirectionsBike
    BikeParkingType.UNKNOWN   -> Icons.Default.Place
}





