package com.startupmini.nyachat.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GroupOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.local.SecureStorage
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import com.startupmini.nyachat.data.remote.MyWorkspace
import com.startupmini.nyachat.ui.screens.AddTransactionDialog
import com.startupmini.nyachat.ui.screens.AiReportDialog
import com.startupmini.nyachat.ui.screens.ApiKeyDialog
import com.startupmini.nyachat.ui.screens.ConfirmClearDataDialog
import com.startupmini.nyachat.ui.screens.LogoutDialog
import com.startupmini.nyachat.ui.screens.PinDisplayDialog
import com.startupmini.nyachat.ui.screens.ProfileAccountSheet
import com.startupmini.nyachat.ui.screens.SettingsSheet
import com.startupmini.nyachat.ui.screens.timestampForFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.net.Uri
import java.io.File

/**
 * TASK-1.3 lanjutan — blok SEMUA dialog lapisan konten utama, diekstrak dari
 * MainActivity (no behavior change). Dialog memakai state bersama
 * [MainDialogController] milik MainActivity; dependency (viewModel, prefs,
 * secureStorage, driveController) di-wire dari atas.
 *
 * Menampung: AddTransactionDialog (input manual/edit), SettingsSheet,
 * ApiKeyDialog (Gemini/OpenRouter), PinDisplayDialog, ConfirmClearDataDialog,
 * LogoutDialog, AiReportDialog (audit & bulanan).
 */
@Composable
fun MainAppDialogs(
    viewModel: MainViewModel,
    context: Context,
    appPrefs: SharedPreferences,
    secureStorage: SecureStorage,
    scope: CoroutineScope,
    dialogs: MainDialogController,
    isDarkMode: Boolean,
    backupEncrypted: Boolean,
    lastBackupMillis: Long,
    lastBackupEncrypted: Boolean,
    userName: String?,
    workspaceRole: String?,
    workspacePin: String?,
    geminiKey: String?,
    openRouterKey: String?,
    auditReport: String?,
    monthlyReport: String?,
    // Audit response (2026-08-12): flag error laporan + retry — dialog menampilkan
    // tombol "Coba Lagi" saat generate gagal (Problem → Action).
    auditError: Boolean,
    monthlyError: Boolean,
    onRetryAudit: () -> Unit,
    onRetryMonthly: () -> Unit,
    driveController: DriveBackupController,
    exportCsvLauncher: ManagedActivityResultLauncher<String, Uri?>,
    showSnack: (String, String?, (() -> Unit)?) -> Unit,
    onToggleDarkMode: () -> Unit,
    // Audit menu Pengaturan (2026-08-12): kebijakan privasi dibuka dari
    // Pengaturan → Tentang (MainActivity membuka browser).
    onPrivacyPolicy: () -> Unit = {},
    // 3.7: toggle notifikasi chat (state di MainActivity).
    chatNotificationsEnabled: Boolean,
    onToggleChatNotifications: () -> Unit,
    onToggleBackupEncryption: () -> Unit,
    onGeminiKeySaved: (String) -> Unit,
    onOpenRouterKeySaved: (String) -> Unit,
    // Profil & Akun (r1.2.1).
    userEmail: String?,
    avatarPath: String?,
    hasGooglePhoto: Boolean,
    avatarSource: String?,
    onAvatarSourceChanged: (String?) -> Unit,
    onCustomAvatarPicked: (Uri) -> Unit,
    onRenameUser: (String) -> Unit,
    onPerformLogoutCleanup: () -> Unit,
    // r1.4.0 (keluar dari workspace): MainActivity yang mengeksekusi operasi
    // cloud (leaveWorkspace) + cleanup — dialog hanya memicu konfirmasi.
    onLeaveWorkspaceConfirmed: () -> Unit = {},
    // r1.4.0 (auto-connect): pilihan workspace untuk akun lama yang terikat >1.
    // r1.4.0: name ikut dikirim (dari MyWorkspace.name) supaya picker bisa
    // mengisi userName tanpa meminta ulang nama.
    onPickWorkspace: (pin: String, role: String, name: String) -> Unit = { _, _, _ -> }
) {
    val backupBusy by driveController.busy.collectAsStateWithLifecycle()

    // Launcher pilih foto profil (r1.2.1) — Galeri & Kamera, pola sama seperti
    // lampiran chat di ChatScreen. File hasil kamera lewat FileProvider ke
    // cacheDir, lalu MainActivity yang menyimpan ke AvatarStore (custom.jpg).
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    val profileCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraTempUri
        cameraTempUri = null
        if (success && uri != null) onCustomAvatarPicked(uri)
    }
    val profileGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onCustomAvatarPicked(uri) }

    if (dialogs.showAddDialog) {
        AddTransactionDialog(
            transaction = dialogs.editTarget,
            initialLoggedBy = userName,
            onDismiss = {
                dialogs.showAddDialog = false
                dialogs.editTarget = null
                if (dialogs.resetChatOnDialogClose) dialogs.chatResetTrigger++
            },
            onConfirm = { tx ->
                if (dialogs.editTarget != null) {
                    viewModel.updateTransaction(tx)
                } else {
                    viewModel.addManualTransaction(
                        tx.type, tx.category, tx.amount, tx.description, tx.loggedBy
                    )
                }
                dialogs.showAddDialog = false
                dialogs.editTarget = null
                // Audit bug (2026-08-12): reset trigger chat dipindah ke onDismiss
                // saja — dialog selalu memanggil onDismiss() SETELAH onConfirm(),
                // jadi menaikkannya di sini membuatnya berjalan 2× per simpan.
            }
        )
    }

    // Settings Bottom Sheet — di-ekstrak ke SettingsSheet.kt (P2-13)
    if (dialogs.showSettingsSheet) {
        SettingsSheet(
            isDarkMode = isDarkMode,
            userName = userName,
            workspaceRole = workspaceRole,
            workspacePin = workspacePin,
            backupBusy = backupBusy,
            isBackupEncrypted = backupEncrypted,
            lastBackupMillis = lastBackupMillis,
            lastBackupEncrypted = lastBackupEncrypted,
            onDismiss = { dialogs.showSettingsSheet = false },
            onToggleDarkMode = onToggleDarkMode,
            chatNotificationsEnabled = chatNotificationsEnabled,
            onToggleChatNotifications = onToggleChatNotifications,
            onToggleBackupEncryption = onToggleBackupEncryption,
            onCheckUpdate = {
                dialogs.showSettingsSheet = false
                scope.launch {
                    val release = GitHubUpdateChecker.checkLatest()
                    if (release != null && GitHubUpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                        dialogs.updateInfo = release
                    } else {
                        showSnack(context.getString(R.string.update_no_update), null, null)
                    }
                }
            },
            onGeminiKey = {
                dialogs.showSettingsSheet = false
                dialogs.showGeminiKeyDialog = true
            },
            onOpenRouterKey = {
                dialogs.showSettingsSheet = false
                dialogs.showOpenRouterKeyDialog = true
            },
            onPin = {
                dialogs.showSettingsSheet = false
                dialogs.showPinDialog = true
            },
            onExportCsv = {
                dialogs.showSettingsSheet = false
                exportCsvLauncher.launch("Nyachat-rekap-${timestampForFile()}.csv")
            },
            onBackup = {
                dialogs.showSettingsSheet = false
                driveController.startBackup()
            },
            onRestore = {
                dialogs.showSettingsSheet = false
                driveController.startRestore()
            },
            onLeaveWorkspace = {
                dialogs.showSettingsSheet = false
                dialogs.showLeaveWorkspaceDialog = true
            },
            onClearData = {
                dialogs.showSettingsSheet = false
                dialogs.showConfirmClearDialog = true
            },
            onLogout = {
                dialogs.showSettingsSheet = false
                dialogs.showLogoutDialog = true
            },
            onPrivacyPolicy = {
                dialogs.showSettingsSheet = false
                onPrivacyPolicy()
            },
            avatarPath = avatarPath,
            onOpenProfile = {
                dialogs.showSettingsSheet = false
                dialogs.showProfileAccount = true
            }
        )
    }

    // Profil & Akun (r1.2.1) — dibuka dari kartu profil Settings.
    if (dialogs.showProfileAccount) {
        ProfileAccountSheet(
            displayName = userName ?: stringResource(R.string.pin_default_name),
            email = userEmail,
            workspaceRole = workspaceRole,
            avatarPath = avatarPath,
            hasGooglePhoto = hasGooglePhoto,
            avatarSource = avatarSource,
            onDismiss = { dialogs.showProfileAccount = false },
            onPickGallery = { profileGalleryLauncher.launch("image/*") },
            onPickCamera = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraTempUri = uri
                runCatching { profileCameraLauncher.launch(uri) }
            },
            onUseGooglePhoto = { onAvatarSourceChanged(Constants.AvatarSources.GOOGLE) },
            onResetAvatar = { onAvatarSourceChanged(null) },
            onRename = onRenameUser
        )
    }

    if (dialogs.showGeminiKeyDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.menu_gemini_key),
            hint = stringResource(R.string.gemini_key_hint),
            initialKey = geminiKey ?: "",
            onDismiss = { dialogs.showGeminiKeyDialog = false },
            onSave = { newKey ->
                // Keystore crypto di IO (audit local/ 2026-08-13) — sebelumnya
                // sinkron di main thread (jank saat hardware-backed key).
                scope.launch { secureStorage.putSecretAsync(context, Constants.Prefs.GEMINI_API_KEY, newKey) }
                onGeminiKeySaved(newKey)
                dialogs.showGeminiKeyDialog = false
                // Audit response (2026-08-12): konfirmasi eksplisit kunci tersimpan.
                showSnack(context.getString(R.string.api_key_saved), null, null)
            }
        )
    }

    if (dialogs.showOpenRouterKeyDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.menu_openrouter_key),
            hint = stringResource(R.string.openrouter_key_hint),
            initialKey = openRouterKey ?: "",
            onDismiss = { dialogs.showOpenRouterKeyDialog = false },
            onSave = { newKey ->
                // Keystore crypto di IO (audit local/ 2026-08-13).
                scope.launch { secureStorage.putSecretAsync(context, Constants.Prefs.OPENROUTER_API_KEY, newKey) }
                onOpenRouterKeySaved(newKey)
                dialogs.showOpenRouterKeyDialog = false
                // Audit response (2026-08-12): konfirmasi eksplisit kunci tersimpan.
                showSnack(context.getString(R.string.api_key_saved), null, null)
            }
        )
    }

    if (dialogs.showPinDialog) {
        PinDisplayDialog(
            workspacePin = workspacePin,
            onCopyPin = {
                workspacePin?.let {
                    // L7: ClipData berlabel supaya clipboard privacy/permission
                    // (API ≥ 31) menampilkan origin app dan mencegah app lain
                    // membaca PIN tanpa izin.
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Nyachat", it))
                    showSnack(context.getString(R.string.pin_copied), null, null)
                }
                dialogs.showPinDialog = false
            },
            onDismiss = { dialogs.showPinDialog = false }
        )
    }

    if (dialogs.showConfirmClearDialog) {
        ConfirmClearDataDialog(
            onConfirm = {
                viewModel.clearAllData()
                dialogs.showConfirmClearDialog = false
            },
            onDismiss = { dialogs.showConfirmClearDialog = false }
        )
    }

    if (dialogs.showLogoutDialog) {
        LogoutDialog(
            onKeepData = {
                dialogs.showLogoutDialog = false
                onPerformLogoutCleanup()
            },
            onDeleteData = {
                dialogs.showLogoutDialog = false
                viewModel.logoutAndDeleteAllData {
                    onPerformLogoutCleanup()
                }
            },
            onDismiss = { dialogs.showLogoutDialog = false }
        )
    }

    // r1.4.0 (auto-connect): akun lama bisa terikat >1 workspace (dulu tidak ada
    // fitur keluar) — minta user pilih yang mana.
    if (dialogs.workspaceChoices.isNotEmpty()) {
        WorkspacePickerDialog(
            choices = dialogs.workspaceChoices,
            onPick = { ws ->
                dialogs.workspaceChoices = emptyList()
                onPickWorkspace(ws.pin, ws.role, ws.name)
            },
            onDismiss = { dialogs.workspaceChoices = emptyList() }
        )
    }

    // r1.4.0 (keluar dari workspace): lepaskan diri dari workspace — akun tetap
    // login, tapi tidak lagi terikat → bisa buat/bergabung workspace baru.
    if (dialogs.showLeaveWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { dialogs.showLeaveWorkspaceDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.GroupOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.leave_workspace_confirm_title)) },
            text = { Text(stringResource(R.string.leave_workspace_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogs.showLeaveWorkspaceDialog = false
                        onLeaveWorkspaceConfirmed()
                    }
                ) {
                    Text(
                        stringResource(R.string.menu_leave_workspace),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogs.showLeaveWorkspaceDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    auditReport?.let { report ->
        AiReportDialog(
            reportText = report,
            isError = auditError,
            // Coba Lagi: tutup dialog error lalu generate ulang (spinner muncul di
            // tombol kartu, hasil baru membuka dialog lagi).
            onRetry = if (auditError) {
                {
                    viewModel.dismissAuditReport()
                    onRetryAudit()
                }
            } else null,
            onDismiss = { viewModel.dismissAuditReport() }
        )
    }

    monthlyReport?.let { report ->
        AiReportDialog(
            reportText = report,
            isError = monthlyError,
            onRetry = if (monthlyError) {
                {
                    viewModel.dismissMonthlyReport()
                    onRetryMonthly()
                }
            } else null,
            onDismiss = { viewModel.dismissMonthlyReport() }
        )
    }
}

/**
 * Pilih workspace (r1.4.0 — auto-connect). Untuk akun lama yang terikat >1
 * workspace (sebelum ada fitur keluar): user memilih workspace yang dimasuki.
 */
@Composable
internal fun WorkspacePickerDialog(
    choices: List<MyWorkspace>,
    onPick: (MyWorkspace) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Rounded.GroupOff, contentDescription = null) },
        title = { Text(stringResource(R.string.workspace_picker_title)) },
        text = {
            Column {
                choices.forEach { ws ->
                    TextButton(
                        onClick = { onPick(ws) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = ws.pin + " · " + stringResource(
                                if (ws.role == com.startupmini.nyachat.Constants.Roles.OWNER)
                                    R.string.pin_role_owner
                                else R.string.pin_role_member
                            ),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
