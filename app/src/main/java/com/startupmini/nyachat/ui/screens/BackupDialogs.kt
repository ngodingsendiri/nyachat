package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.backup.DriveBackupFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Dialog backup/restore Drive diekstrak dari MainActivity (TASK-1.3) — aksi
// di-hoist ke DriveBackupController via callback, tanpa perubahan behavior.

/**
 * Format waktu `createdTime` dari Drive (ISO-8601, mis. "2026-08-12T07:42:23.259Z")
 * ke tampilan lokal: "12 Agu 2026 · 14:42". Gagal parse → kembalikan string
 * mentah (backup lama dengan format berbeda tidak boleh membuat dialog error).
 */
internal fun formatBackupTime(isoTime: String): String {
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(isoTime) ?: return@runCatching null
        val display = SimpleDateFormat("d MMM yyyy · HH:mm", Locale("id", "ID"))
        display.format(parsed as Date)
    }.getOrNull() ?: isoTime
}

/** Modal progres backup — bisa dibatalkan (B3). */
@Composable
fun BackupProgressDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.backup_progress_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.backup_progress_detail),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    stringResource(R.string.backup_please_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Pilih file backup dari Google Drive untuk di-restore. */
@Composable
fun RestorePickerDialog(
    files: List<DriveBackupFile>,
    onPick: (DriveBackupFile) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_pick_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.restore_pick_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                files.forEachIndexed { index, f ->
                    val isNewest = index == 0
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isNewest) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        TextButton(
                            onClick = { onPick(f) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = f.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isNewest) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.restore_pick_newest_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    // Badge 🔒 — indikator file backup terenkripsi di picker
                                    // restore (temuan #4 live test). `encrypted == true`
                                    // (null = status belum diketahui, tampil netral).
                                    if (f.encrypted == true) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "🔒 ${stringResource(R.string.settings_backup_encrypted_yes)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = formatBackupTime(f.createdTime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Konfirmasi restore file backup tertentu. */
@Composable
fun RestoreConfirmDialog(
    file: DriveBackupFile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = { Text(stringResource(R.string.restore_confirm_message, file.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Backup milik workspace lain → konfirmasi eksplisit sebelum menimpa (P1). */
@Composable
fun CrossFamilyRestoreDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_cross_family_title)) },
        text = { Text(stringResource(R.string.restore_cross_family_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Prompt passphrase (backup terenkripsi / restore terenkripsi). */
@Composable
fun PassphraseDialog(
    prompt: DriveBackupController.PassphrasePrompt,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val isBackupPrompt = prompt is DriveBackupController.PassphrasePrompt.Backup
    var passphrase by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    if (isBackupPrompt) R.string.backup_passphrase_title
                    else R.string.restore_passphrase_title
                )
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (isBackupPrompt) R.string.backup_passphrase_message
                        else R.string.restore_passphrase_message
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.backup_passphrase_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.length >= 8,
                onClick = { onSubmit(passphrase) }
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
