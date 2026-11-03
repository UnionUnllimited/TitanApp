package com.titanvps.app.ui.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan

/**
 * The app's alert, as the iPhone's: on the cards' surface (not Material's grey), rounder
 * corners, in the app's text size (a dialog is a window of its own, with the system's text
 * size otherwise). Buttons: [DialogButton].
 */
@Composable
internal fun TitanAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val palette = Titan.palette
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ProvideTitanTextScale(confirmButton) },
        dismissButton = dismissButton?.let { slot -> { ProvideTitanTextScale(slot) } },
        title = title?.let { slot -> { ProvideTitanTextScale(slot) } },
        text = text?.let { slot -> { ProvideTitanTextScale(slot) } },
        containerColor = palette.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = palette.textSecondary,
        shape = RoundedCornerShape(24.dp),
    )
}

/**
 * A dialog's button: the primary colour, red for what can't be undone ([destructive]: Выйти,
 * Сбросить, Удалить), the main action ([bold]) in semibold, as iOS's alerts.
 */
@Composable
internal fun DialogButton(text: String, onClick: () -> Unit, destructive: Boolean = false, bold: Boolean = false) {
    val palette = Titan.palette
    TextButton(onClick = onClick) {
        Text(
            text,
            color = if (destructive) palette.red else palette.primary,
            fontWeight = if (bold || destructive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
