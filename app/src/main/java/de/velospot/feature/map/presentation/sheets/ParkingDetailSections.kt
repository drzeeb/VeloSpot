package de.velospot.feature.map.presentation.sheets

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.velospot.R
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.feature.map.presentation.DetailRow
import de.velospot.feature.map.presentation.label

/**
 * Localised label for a raw OSM `bicycle_parking` subtype. Unknown/blank subtypes
 * fall back to `null` so the caller can omit the row entirely.
 */
@Composable
internal fun parkingSubtypeLabel(subtype: String?): String? {
    val res = when (subtype?.trim()?.lowercase()) {
        "lockers" -> R.string.subtype_lockers
        "stands" -> R.string.subtype_stands
        "shed" -> R.string.subtype_shed
        "two-tier" -> R.string.subtype_two_tier
        "wall_loops" -> R.string.subtype_wall_loops
        "building" -> R.string.subtype_building
        "anchors" -> R.string.subtype_anchors
        "informal" -> R.string.subtype_informal
        "lean_to" -> R.string.subtype_lean_to
        else -> null
    } ?: return null
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
 * Grouped, enriched detail rows for a parking spot. Every row is rendered **only
 * when its value is known** — a `null` attribute is omitted entirely rather than
 * shown as "unknown".
 */
@Composable
internal fun ParkingDetailSections(
    space: BikeParkingSpace,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val yes = stringResource(id = R.string.common_yes)

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Security ─────────────────────────────────────────────────────────
        val subtypeLabel = parkingSubtypeLabel(space.parkingSubtype)
        val hasSecurity = subtypeLabel != null || space.isCovered == true ||
            space.indoor == true || space.surveillance == true ||
            space.lit == true || space.supervised == true
        if (hasSecurity) {
            DetailSectionHeader(text = stringResource(id = R.string.parking_section_security))
            DetailRow(
                label = stringResource(id = R.string.detail_subtype),
                value = subtypeLabel ?: space.type.label(context)
            )
            if (space.isCovered == true) {
                DetailRow(label = stringResource(id = R.string.detail_covered), value = yes)
            }
            if (space.indoor == true) {
                DetailRow(label = stringResource(id = R.string.detail_indoor), value = yes)
            }
            if (space.surveillance == true) {
                DetailRow(label = stringResource(id = R.string.detail_surveillance), value = yes)
            }
            if (space.lit == true) {
                DetailRow(label = stringResource(id = R.string.detail_lit), value = yes)
            }
            if (space.supervised == true) {
                DetailRow(label = stringResource(id = R.string.detail_supervised), value = yes)
            }
        }

        // ── Access ───────────────────────────────────────────────────────────
        val accessValue = accessLabel(space.access)
        val hasAccess = accessValue != null || space.fee != null ||
            space.openingHours != null || space.maxstay != null
        if (hasAccess) {
            SectionSpacer()
            DetailSectionHeader(text = stringResource(id = R.string.parking_section_access))
            accessValue?.let {
                DetailRow(label = stringResource(id = R.string.detail_access), value = it)
            }
            space.fee?.let { fee ->
                DetailRow(
                    label = stringResource(id = R.string.detail_fee),
                    value = stringResource(
                        id = if (fee) R.string.detail_fee_paid else R.string.detail_fee_free
                    )
                )
            }
            space.openingHours?.let {
                DetailRow(label = stringResource(id = R.string.detail_opening_hours), value = it)
            }
            space.maxstay?.let {
                DetailRow(label = stringResource(id = R.string.detail_maxstay), value = it)
            }
        }

        // ── Capacity ─────────────────────────────────────────────────────────
        val hasCapacity = space.capacity != null || space.cargoBikeCapacity != null ||
            space.disabledCapacity != null || space.chargingCapacity != null
        if (hasCapacity) {
            SectionSpacer()
            DetailSectionHeader(text = stringResource(id = R.string.parking_section_capacity))
            space.capacity?.let {
                DetailRow(label = stringResource(id = R.string.detail_capacity), value = it.toString())
            }
            space.cargoBikeCapacity?.let {
                DetailRow(label = stringResource(id = R.string.detail_capacity_cargo), value = it.toString())
            }
            space.disabledCapacity?.let {
                DetailRow(label = stringResource(id = R.string.detail_capacity_disabled), value = it.toString())
            }
            space.chargingCapacity?.let {
                DetailRow(label = stringResource(id = R.string.detail_capacity_charging), value = it.toString())
            }
        }

        // ── Context ──────────────────────────────────────────────────────────
        val networkOrBrand = space.network ?: space.brand
        val hasContext = space.operator != null || networkOrBrand != null || space.ref != null
        if (hasContext) {
            SectionSpacer()
            DetailSectionHeader(text = stringResource(id = R.string.parking_section_context))
            space.operator?.let {
                DetailRow(label = stringResource(id = R.string.detail_operator), value = it)
            }
            networkOrBrand?.let {
                DetailRow(label = stringResource(id = R.string.detail_network), value = it)
            }
            space.ref?.let {
                DetailRow(label = stringResource(id = R.string.detail_reference), value = it)
            }
        }

        // ── Data freshness + website ─────────────────────────────────────────
        space.checkDate?.let { date ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
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

@Composable
private fun DetailSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun SectionSpacer() {
    Spacer(modifier = Modifier.height(14.dp))
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



