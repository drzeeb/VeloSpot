package de.velospot.feature.map.presentation.sheets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.velospot.R

/**
 * A small reusable "are you sure?" dialog for destructive actions — deleting a
 * ride or a route, or removing a favourite. Prevents accidental one-tap deletions.
 *
 * [onConfirm] performs the deletion; the dialog dismisses itself afterwards (and
 * on cancel). The confirm action is tinted with the error colour to signal that
 * it is destructive.
 */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

