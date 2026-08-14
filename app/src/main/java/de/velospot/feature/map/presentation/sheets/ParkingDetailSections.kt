package de.velospot.feature.map.presentation.sheets
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.velospot.R
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BikeParkingType
import de.velospot.feature.map.presentation.DetailRow
/**
 * Localised type-badge label derived from the raw OSM `bicycle_parking` subtype,
 * falling back to the coarse [BikeParkingType]. Always returns a usable label
 * (an unknown facility with no subtype falls back to a generic "parking" label).
 */
@Composable
internal fun parkingTypeBadgeLabel(space: BikeParkingSpace): String {
    val res = when (space.parkingSubtype?.trim()?.lowercase()) {
        "lockers" -> R.string.subtype_lockers
        "shed" -> R.string.subtype_shed
        "building", "garage" -> R.string.parking_badge_type_garage
        "stands", "anchors" -> R.string.subtype_stands
        "wall_loops" -> R.string.parking_badge_type_wall_loops
        "two-tier" -> R.string.subtype_two_tier
        "lean_to" -> R.string.subtype_lean_to
        "informal" -> R.string.subtype_informal
        else -> when (space.type) {
            BikeParkingType.GARAGE -> R.string.parking_badge_type_garage
            BikeParkingType.BIKE_RACK -> R.string.parking_badge_type_rack
            BikeParkingType.UNKNOWN -> R.string.parking_badge_type_generic
        }
    }
    return stringResource(id = res)
}
/** Localised label for a raw OSM `access` value, or `null` when unknown. */
@Composable
internal fun accessLabel(access: String?): String? {
    val res = when (access?.trim()?.lowercase()) {
        "yes", "permissive", "designated" -> R.string.access_public
        "customers" -> R.string.access_customers
        "private" -> R.string.access_private
        "no" -> R.string.access_no
        else -> null
    } ?: return null
    return stringResource(id = res)
}
/**
 * Factual, non-interpretive detail view for a parking spot: a wrapping row of
 * feature badges (only rendered for known, positive attributes -- a null
 * attribute is omitted, never shown as an assumption) followed by simple
 * label/value info rows for the remaining OSM facts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParkingDetailSections(
    space: BikeParkingSpace,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        // -- Badges -----------------------------------------------------------
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Type badge (always shown; describes what kind of parking this is).
            FeatureBadge(
                icon = Icons.Filled.LocalParking,
                label = parkingTypeBadgeLabel(space)
            )
            if (space.isCovered == true || space.indoor == true) {
                FeatureBadge(
                    icon = Icons.Filled.Umbrella,
                    label = stringResource(id = R.string.parking_badge_covered)
                )
            }
            if (space.lit == true) {
                FeatureBadge(
                    icon = Icons.Filled.LightMode,
                    label = stringResource(id = R.string.parking_badge_lit)
                )
            }
            if (space.surveillance == true) {
                FeatureBadge(
                    icon = Icons.Filled.Videocam,
                    label = stringResource(id = R.string.parking_badge_surveillance)
                )
            }
            if (space.supervised == true) {
                FeatureBadge(
                    icon = Icons.Filled.Shield,
                    label = stringResource(id = R.string.parking_badge_supervised)
                )
            }
            when (space.fee) {
                false -> FeatureBadge(
                    icon = Icons.Filled.MoneyOff,
                    label = stringResource(id = R.string.parking_badge_free)
                )
                true -> FeatureBadge(
                    icon = Icons.Filled.Payments,
                    label = stringResource(id = R.string.parking_badge_paid)
                )
                null -> Unit
            }
            if ((space.chargingCapacity ?: 0) > 0) {
                FeatureBadge(
                    icon = Icons.Filled.ElectricBolt,
                    label = stringResource(id = R.string.parking_badge_charging)
                )
            }
            if (space.cargoBike == true || (space.cargoBikeCapacity ?: 0) > 0) {
                FeatureBadge(
                    icon = Icons.Filled.LocalShipping,
                    label = stringResource(id = R.string.parking_badge_cargo)
                )
            }
            if ((space.disabledCapacity ?: 0) > 0) {
                FeatureBadge(
                    icon = Icons.AutoMirrored.Filled.Accessible,
                    label = stringResource(id = R.string.parking_badge_accessible)
                )
            }
            space.capacity?.let { capacity ->
                FeatureBadge(
                    icon = Icons.Filled.Numbers,
                    label = stringResource(id = R.string.parking_badge_capacity, capacity)
                )
            }
        }
        // -- Info rows --------------------------------------------------------
        val networkOrBrand = space.network ?: space.brand
        val accessValue = accessLabel(space.access)
        val hasRows = space.operator != null || networkOrBrand != null ||
            space.openingHours != null || space.maxstay != null ||
            accessValue != null || space.ref != null
        if (hasRows) {
            Spacer(modifier = Modifier.height(14.dp))
            space.operator?.let {
                DetailRow(label = stringResource(id = R.string.detail_operator), value = it)
            }
            networkOrBrand?.let {
                DetailRow(label = stringResource(id = R.string.detail_network), value = it)
            }
            space.openingHours?.let {
                DetailRow(label = stringResource(id = R.string.detail_opening_hours), value = it)
            }
            space.maxstay?.let {
                DetailRow(label = stringResource(id = R.string.detail_maxstay), value = it)
            }
            accessValue?.let {
                DetailRow(label = stringResource(id = R.string.detail_access), value = it)
            }
            space.ref?.let {
                DetailRow(label = stringResource(id = R.string.detail_reference), value = it)
            }
        }
        // -- Data freshness + website -----------------------------------------
        space.checkDate?.let { date ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(id = R.string.detail_last_checked, date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        space.website?.let { url ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openWebsite(context, url) }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(id = R.string.detail_open_website),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.detail_website),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
/** A small pill chip with a leading icon and short label used for parking facts. */
@Composable
private fun FeatureBadge(icon: ImageVector, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
/** Opens [url] in an external browser using the shared `ACTION_VIEW` intent pattern. */
private fun openWebsite(context: Context, url: String) {
    val normalized = if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
        url
    } else {
        "https://$url"
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, normalized.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, normalized, Toast.LENGTH_SHORT).show()
    }
}
