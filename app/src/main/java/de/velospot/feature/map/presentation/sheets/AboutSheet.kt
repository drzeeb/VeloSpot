package de.velospot.feature.map.presentation.sheets

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PrivacyTip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.velospot.R

private const val APP_URL = "https://velospot.app"
private const val PRIVACY_URL = "https://velospot.app/privacy"
private const val SUPPORT_URL = "https://buymeacoffee.com/velospot"

/** Release date of the bundled Germany OSM parking dataset. */
private const val DATA_DATE_GERMANY = "08.08.2026"

/** Release date of the bundled France & Luxembourg OSM parking datasets. */
private const val DATA_DATE_FRANCE = "18.06.2026"
private const val DATA_DATE_LUXEMBOURG = "18.06.2026"

/**
 * "About VeloSpot" bottom sheet, reachable from the menu.
 *
 * Shows the app name, a link to the website, the per-country status (release date)
 * of the bundled parking datasets and a link to the privacy policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSheet(
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

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
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(id = R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Website link
            AboutLinkRow(
                icon = Icons.Default.Language,
                title = stringResource(id = R.string.about_website),
                subtitle = "velospot.app",
                onClick = { openUrl(APP_URL) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Dataset status
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(id = R.string.about_data_status),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.height(8.dp))
            DatasetStatusRow(stringResource(R.string.about_country_germany), DATA_DATE_GERMANY)
            DatasetStatusRow(stringResource(R.string.about_country_france), DATA_DATE_FRANCE)
            DatasetStatusRow(stringResource(R.string.about_country_luxembourg), DATA_DATE_LUXEMBOURG)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Privacy policy link
            AboutLinkRow(
                icon = Icons.Default.PrivacyTip,
                title = stringResource(id = R.string.about_privacy),
                subtitle = "velospot.app/privacy",
                onClick = { openUrl(PRIVACY_URL) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Support / donation link (opens externally in the browser)
            AboutLinkRow(
                icon = Icons.Default.Coffee,
                title = stringResource(id = R.string.about_support),
                subtitle = "buymeacoffee.com/velospot",
                onClick = { openUrl(SUPPORT_URL) }
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AboutLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(id = R.string.about_open_link),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DatasetStatusRow(country: String, date: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = country, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = date,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

