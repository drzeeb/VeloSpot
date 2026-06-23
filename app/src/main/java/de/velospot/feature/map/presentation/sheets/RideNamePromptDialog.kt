package de.velospot.feature.map.presentation.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.velospot.R

/**
 * Asks the rider to name a **manual** recording they are about to finish. The text
 * field is pre-filled with [suggestion] — the reverse-geocoded current place — which
 * may arrive a moment after the dialog opens (it's resolved over the network), so it
 * is filled in lazily as long as the rider hasn't started typing.
 *
 * Confirming saves the ride with the (possibly edited, possibly empty → unnamed)
 * name; dismissing keeps the recording running so an accidental stop is recoverable.
 */
@Composable
internal fun RideNamePromptDialog(
    suggestion: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(suggestion.orEmpty()) }
    // Whether the rider has manually edited the field — once they do, the late
    // arriving suggestion must not overwrite their input.
    var edited by remember { mutableStateOf(false) }

    // Fill the suggestion in once it resolves, unless the rider already typed.
    LaunchedEffect(suggestion) {
        if (!edited && text.isBlank() && !suggestion.isNullOrBlank()) {
            text = suggestion
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ride_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; edited = true },
                singleLine = true,
                label = { Text(stringResource(R.string.ride_rename_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.ride_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ride_rename_cancel))
            }
        }
    )
}

