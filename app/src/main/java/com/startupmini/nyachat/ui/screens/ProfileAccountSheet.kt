package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.ui.util.AvatarImage
import kotlinx.coroutines.launch

/**
 * Halaman "Profil & Akun" (r1.2.1) — dibuka dari kartu profil di Settings.
 *
 * Menampilkan foto profil (Google/custom/inisial), nama user, email akun
 * Google, dan status akun (Pemilik/Anggota). Aksi:
 *  - Ganti foto profil → dialog pilihan sumber (Foto Google / Galeri / Kamera
 *    / Avatar bawaan). Keputusan user PERSISTEN (prefs AVATAR_SOURCE); foto
 *    Google tidak pernah diubah — hanya di-cache lokal sebagai avatar.
 *  - Nama → dialog ubah nama. Nama yang diedit user tidak akan ditimpa lagi
 *    oleh nama dari Google (nama Google hanya default saat onboarding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileAccountSheet(
    displayName: String,
    email: String?,
    workspaceRole: String?,
    avatarPath: String?,
    hasGooglePhoto: Boolean,
    avatarSource: String?,
    onDismiss: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onUseGooglePhoto: () -> Unit,
    onResetAvatar: () -> Unit,
    onRename: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showSourceDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Audit motion (2026-08-12): onDismissRequest juga lewat dismiss() —
    // gesture swipe/scrim/back ikut turun ke bawah, konsisten dengan sheet lain.
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.profile_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Identitas: avatar besar + nama + email + status
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AvatarImage(
                    name = displayName,
                    size = 96,
                    photoPath = avatarPath,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    textStyle = MaterialTheme.typography.displaySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = email ?: stringResource(R.string.profile_email_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                // Status akun: peran workspace (Pemilik/Anggota) + sumber login Google.
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(Constants.Ui.CORNER_S.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                if (workspaceRole == Constants.Roles.OWNER) R.string.pin_role_owner
                                else R.string.pin_role_member
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.profile_account_desc),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Aksi profil
            ProfileRow(
                icon = Icons.Rounded.PhotoCamera,
                title = stringResource(R.string.profile_photo_change),
                onClick = { showSourceDialog = true }
            )
            ProfileRow(
                icon = Icons.Rounded.Badge,
                title = stringResource(R.string.profile_name_title),
                subtitle = displayName,
                onClick = { showRenameDialog = true }
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Dialog pilihan sumber foto profil
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text(stringResource(R.string.profile_photo_title)) },
            text = {
                Column {
                    SourceOption(
                        title = stringResource(R.string.profile_photo_google),
                        subtitle = stringResource(R.string.profile_photo_google_desc),
                        active = avatarSource == Constants.AvatarSources.GOOGLE,
                        // Opsi yang SEDANG AKTIF tidak boleh tampil abu-abu walau
                        // photoUrl sedang tak tersedia (mis. offline) — user tetap
                        // bisa melihat avatarnya dari cache lokal.
                        enabled = hasGooglePhoto || avatarSource == Constants.AvatarSources.GOOGLE,
                        onClick = {
                            onUseGooglePhoto()
                            showSourceDialog = false
                        }
                    )
                    SourceOption(
                        title = stringResource(R.string.profile_photo_gallery),
                        onClick = {
                            onPickGallery()
                            showSourceDialog = false
                        }
                    )
                    SourceOption(
                        title = stringResource(R.string.profile_photo_camera),
                        onClick = {
                            onPickCamera()
                            showSourceDialog = false
                        }
                    )
                    // Reset tersedia hanya bila user sudah memilih sumber tertentu —
                    // kembali ke perilaku default (foto Google bila ada, else inisial).
                    if (avatarSource != null) {
                        SourceOption(
                            title = stringResource(R.string.profile_photo_reset),
                            subtitle = stringResource(R.string.profile_photo_reset_desc),
                            onClick = {
                                onResetAvatar()
                                showSourceDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Dialog ubah nama
    if (showRenameDialog) {
        var nameText by remember { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.profile_name_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { if (it.length <= 40) nameText = it },
                        label = { Text(stringResource(R.string.profile_name_hint)) },
                        singleLine = true,
                        isError = nameText.isBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (nameText.isBlank()) {
                        Text(
                            text = stringResource(R.string.profile_name_empty_error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameText.isNotBlank(),
                    onClick = {
                        onRename(nameText.trim())
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** Baris aksi di halaman Profil: ikon + judul (+ subjudul) + chevron. */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Opsi sumber foto profil di dialog; [active] = pilihan yang sedang dipakai. */
@Composable
private fun SourceOption(
    title: String,
    subtitle: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (active) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.profile_photo_active),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
