package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.velospot.R

/**
 * Chooser shown when a `.gpx` file is opened from outside the app (file manager,
 * e-mail attachment, browser download, share sheet, …). Offers two actions:
 *  - **[onImport]** — parse and persist the GPX as a ride, then open its detail view.
 *  - **[onPreview]** — parse and show it in the detail view transiently, without
 *    saving; the sheet then offers its own "Import" button to keep it.
 *
 * All three actions (import / preview / cancel) are laid out in a single **vertical
 * stack** inside the `confirmButton` slot with **no** `dismissButton`: Material3 places
 * `confirmButton` and `dismissButton` side by side on one row, which would squash a
 * full-width option row next to "Cancel". Stacking them ourselves keeps every option
 * full-width and legible.
 */
@Composable
internal fun GpxOpenChooserDialog(
    onImport: () -> Unit,
    onPreview: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gpx_open_chooser_title)) },
        text = { Text(stringResource(R.string.gpx_open_chooser_message)) },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(R.string.gpx_open_import))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(R.string.gpx_open_preview))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.gpx_open_cancel))
                }
            }
        }
    )
}

