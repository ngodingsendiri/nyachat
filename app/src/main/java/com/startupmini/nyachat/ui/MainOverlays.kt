package com.startupmini.nyachat.ui

import android.content.Context
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import com.startupmini.nyachat.ui.screens.BackupProgressDialog
import com.startupmini.nyachat.ui.screens.CrossFamilyRestoreDialog
import com.startupmini.nyachat.ui.screens.ManageMembersScreen
import com.startupmini.nyachat.ui.screens.MembershipGateScreen
import com.startupmini.nyachat.ui.screens.PassphraseDialog
import com.startupmini.nyachat.ui.screens.PinSwitchDialog
import com.startupmini.nyachat.ui.screens.RestoreConfirmDialog
import com.startupmini.nyachat.ui.screens.RestorePickerDialog
import com.startupmini.nyachat.ui.screens.UpdateAvailableDialog
import com.startupmini.nyachat.ui.screens.UpdateMessageDialog
import com.startupmini.nyachat.ui.screens.installApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * TASK-1.3 lanjutan — overlay GLOBAL yang tampil di SEMUA layar (termasuk
 * layar login/PIN/gate), diekstrak dari MainActivity (no behavior change).
 *
 * Menampung: MembershipGateScreen, ManageMembersScreen, PinSwitchDialog,
 * UpdateAvailableDialog + UpdateMessageDialog, dialog backup/restore Drive
 * (progress, picker, konfirmasi, lintas-workspace, passphrase) dan SnackbarHost.
 * State dialog dipakai dari [MainDialogController]; state backup dipakai dari
 * [DriveBackupController] langsung.
 */
/** [BoxScope]: SnackbarHost memakai [Modifier.align] terhadap Box konten induk. */
@Composable
fun BoxScope.MainOverlays(
    viewModel: MainViewModel,
    context: Context,
    scope: CoroutineScope,
    dialogs: MainDialogController,
    driveController: DriveBackupController,
    snackbarHostState: SnackbarHostState,
    workspacePin: String?,
    workspaceRole: String?,
    onApplyPinConnect: (String, String, String) -> Unit
) {
    // Gate keanggotaan: setelah PIN dimasukkan, sebelum data terbuka.
    // Owner menyiapkan workspace; anggota kirim permintaan & menunggu
    // persetujuan pemilik. Layar penuh menimpa semua konten lain.
    dialogs.connectGate?.let { (pin, role, name) ->
        MembershipGateScreen(
            pin = pin,
            role = role,
            onReady = {
                dialogs.connectGate = null
                onApplyPinConnect(pin, role, name)
            },
            onCancel = { dialogs.connectGate = null }
        )
    }

    // Layar kelola anggota & permintaan bergabung (owner/member).
    if (dialogs.showManageMembers && workspacePin != null) {
        ManageMembersScreen(
            pin = workspacePin!!,
            isOwner = (workspaceRole == Constants.Roles.OWNER),
            onDismiss = { dialogs.showManageMembers = false }
        )
    }

    // Konfirmasi ganti workspace (PIN berbeda): tampil di SEMUA layar.
    dialogs.pendingPinConnect?.let { (pin, role, name) ->
        PinSwitchDialog(
            onConfirm = {
                dialogs.pendingPinConnect = null
                viewModel.clearLocalData()
                dialogs.connectGate = Triple(pin, role, name)
            },
            onDismiss = { dialogs.pendingPinConnect = null }
        )
    }

    // Dialog update tampil di SEMUA layar (termasuk layar login/PIN),
    // jadi yang belum selesai onboarding tetap dapat notif rilis baru.
    dialogs.updateInfo?.let { release ->
        UpdateAvailableDialog(
            release = release,
            isDownloading = dialogs.isDownloadingUpdate,
            onAction = {
                scope.launch {
                    // Aksi selalu tersedia di SEMUA build. Debug → unduh &
                    // pasang langsung (permission REQUEST_INSTALL_PACKAGES).
                    // Release → buka halaman release GitHub di browser (ganti
                    // APK terpasang lebih aman lewat Play Store, tapi tau
                    // dulu ke halaman rilis agar tetap ada tombol aksi).
                    if (BuildConfig.DEBUG) {
                        dialogs.isDownloadingUpdate = true
                        try {
                            val url = release.apkUrl
                            if (url == null) throw IllegalStateException("APK tidak tersedia di release")
                            val dest = File(context.cacheDir, "downloads/nyachat-${release.versionName}.apk")
                            GitHubUpdateChecker.downloadApk(url, dest)
                            installApk(context, dest)
                        } catch (e: Exception) {
                            dialogs.updateMessage = context.getString(R.string.update_download_failed)
                        } finally {
                            dialogs.isDownloadingUpdate = false
                            dialogs.updateInfo = null
                        }
                    } else {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(release.releaseUrl)
                        )
                        context.startActivity(intent)
                        dialogs.updateInfo = null
                    }
                }
            },
            onDismiss = { dialogs.updateInfo = null }
        )
    }

    dialogs.updateMessage?.let { msg ->
        UpdateMessageDialog(
            message = msg,
            onDismiss = { dialogs.updateMessage = null }
        )
    }

    // ---- Dialog Export CSV / Backup / Restore Drive ----
    // B3: modal bisa dibatalkan — kalau Drive menggantung, user tidak
    // terkunci; tombol Batal membatalkan operasi aktif di controller.
    val backupBusy by driveController.busy.collectAsStateWithLifecycle()
    val restoreBackups by driveController.backups.collectAsStateWithLifecycle()
    val restoreTarget by driveController.restoreTarget.collectAsStateWithLifecycle()
    val pendingCrossFamilyRestore by driveController.crossFamilyRestore.collectAsStateWithLifecycle()
    val backupPassphrasePrompt by driveController.passphrasePrompt.collectAsStateWithLifecycle()

    if (backupBusy) {
        BackupProgressDialog(
            onCancel = { driveController.cancelActiveOperation() }
        )
    }

    restoreBackups?.let { files ->
        RestorePickerDialog(
            files = files,
            onPick = { driveController.confirmRestore(it) },
            onDismiss = { driveController.dismissBackups() }
        )
    }

    restoreTarget?.let { f ->
        RestoreConfirmDialog(
            file = f,
            onConfirm = { driveController.confirmRestore(f) },
            onDismiss = { driveController.dismissRestoreTarget() }
        )
    }

    // Backup milik workspace lain → konfirmasi eksplisit sebelum
    // menimpa data lokal & menyinkronkannya ke workspace ini (P1).
    pendingCrossFamilyRestore?.let {
        CrossFamilyRestoreDialog(
            onConfirm = { driveController.proceedCrossFamilyRestore() },
            onDismiss = { driveController.cancelCrossFamilyRestore() }
        )
    }

    // Prompt passphrase (Sprint-2): muncul saat membuat backup
    // terenkripsi atau membuka backup terenkripsi saat restore.
    backupPassphrasePrompt?.let { prompt ->
        PassphraseDialog(
            prompt = prompt,
            onSubmit = { driveController.submitPassphrase(it) },
            onCancel = { driveController.cancelPassphrase() }
        )
    }

    // Snackbar overlay (audit P1.1): tampil di semua layar. Sejak 2026-08-10
    // dipindah ke ATAS layar — sebelumnya di BottomCenter + imePadding justru
    // muncul TEPAT di atas keyboard, menutupi kolom pengetikan & mengganggu
    // ketik cepat (keluhan user). Sekarang: atas, compact (pill), dan Material3
    // SnackbarHost mendukung swipe-dismiss bawaan (geser kiri/kanan/atas).
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .padding(top = 8.dp, start = 20.dp, end = 20.dp),
        snackbar = { data ->
            // Compact pill — batasi lebar (bukan full-width) supaya terasa
            // minimalis & konsisten dengan bahasa floating-card aplikasi.
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.widthIn(max = 480.dp)
            )
        }
    )
}
