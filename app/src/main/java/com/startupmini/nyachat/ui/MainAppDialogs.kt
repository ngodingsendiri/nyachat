package com.startupmini.nyachat.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.local.SecureStorage
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import com.startupmini.nyachat.ui.screens.AddTransactionDialog
import com.startupmini.nyachat.ui.screens.AiReportDialog
import com.startupmini.nyachat.ui.screens.ApiKeyDialog
import com.startupmini.nyachat.ui.screens.ConfirmClearDataDialog
import com.startupmini.nyachat.ui.screens.LogoutDialog
import com.startupmini.nyachat.ui.screens.PinDisplayDialog
import com.startupmini.nyachat.ui.screens.SettingsSheet
import com.startupmini.nyachat.ui.screens.timestampForFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.net.Uri

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
    driveController: DriveBackupController,
    exportCsvLauncher: ManagedActivityResultLauncher<String, Uri?>,
    showSnack: (String, String?, (() -> Unit)?) -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleBackupEncryption: () -> Unit,
    onGeminiKeySaved: (String) -> Unit,
    onOpenRouterKeySaved: (String) -> Unit,
    onPerformLogoutCleanup: () -> Unit
) {
    val backupBusy by driveController.busy.collectAsStateWithLifecycle()

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
                if (dialogs.resetChatOnDialogClose) dialogs.chatResetTrigger++
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
            onClearData = {
                dialogs.showSettingsSheet = false
                dialogs.showConfirmClearDialog = true
            },
            onLogout = {
                dialogs.showSettingsSheet = false
                dialogs.showLogoutDialog = true
            }
        )
    }

    if (dialogs.showGeminiKeyDialog) {
        ApiKeyDialog(
            title = stringResource(R.string.menu_gemini_key),
            hint = stringResource(R.string.gemini_key_hint),
            initialKey = geminiKey ?: "",
            onDismiss = { dialogs.showGeminiKeyDialog = false },
            onSave = { newKey ->
                secureStorage.putSecret(context, Constants.Prefs.GEMINI_API_KEY, newKey)
                onGeminiKeySaved(newKey)
                dialogs.showGeminiKeyDialog = false
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
                secureStorage.putSecret(context, Constants.Prefs.OPENROUTER_API_KEY, newKey)
                onOpenRouterKeySaved(newKey)
                dialogs.showOpenRouterKeyDialog = false
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

    auditReport?.let { report ->
        AiReportDialog(
            reportText = report,
            onDismiss = { viewModel.dismissAuditReport() }
        )
    }

    monthlyReport?.let { report ->
        AiReportDialog(
            reportText = report,
            onDismiss = { viewModel.dismissMonthlyReport() }
        )
    }
}
