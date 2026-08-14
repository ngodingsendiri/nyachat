package com.startupmini.nyachat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.GitHubRelease
import com.startupmini.nyachat.data.remote.MyWorkspace

/**
 * TASK-1.3 lanjutan — state holder SEMUA dialog/overlay layar utama.
 *
 * Sebelumnya ~16 `var ... by remember` tersebar di MainActivity sehingga
 * composable utama sulit dibaca. Dipindahkan ke satu class yang hidup selama
 * komposisi (diingat via `remember`), mengikuti pola `DriveBackupController`.
 * Tidak ada behavior change — hanya lokasi state berubah.
 */
class MainDialogController {
    var showAddDialog by mutableStateOf(false)
    // BUG-08: dinaikkan tiap dialog transaksi manual ditutup — ChatScreen
    // mereset input-nya supaya karakter sisa tidak menempel di field chat.
    var chatResetTrigger by mutableIntStateOf(0)
    // Gate BUG-08: reset field chat HANYA saat dialog dibuka dari tab Chat
    // (tap badge finansial). Dialog dari tab Rekap tidak boleh menghapus
    // draf chat user yang diketik sebelum pindah tab.
    var resetChatOnDialogClose by mutableStateOf(false)
    var showSettingsSheet by mutableStateOf(false)
    // Profil & Akun (r1.2.1): halaman profil dibuka dari kartu profil Settings.
    var showProfileAccount by mutableStateOf(false)
    var showManageMembers by mutableStateOf(false)
    var showGeminiKeyDialog by mutableStateOf(false)
    var showLogoutDialog by mutableStateOf(false)
    var pendingPinConnect by mutableStateOf<Triple<String, String, String>?>(null)
    var connectGate by mutableStateOf<Triple<String, String, String>?>(null)
    var showOpenRouterKeyDialog by mutableStateOf(false)
    var showConfirmClearDialog by mutableStateOf(false)
    // r1.4.0 (keluar dari workspace): dialog konfirmasi lepas dari workspace.
    var showLeaveWorkspaceDialog by mutableStateOf(false)
    // r1.4.0 (auto-connect): daftar pilihan workspace untuk akun lama >1.
    var workspaceChoices by mutableStateOf<List<MyWorkspace>>(emptyList())
    var showPinDialog by mutableStateOf(false)
    var editTarget by mutableStateOf<FinancialTransaction?>(null)

    // State dialog update (tampil di SEMUA layar).
    var updateInfo by mutableStateOf<GitHubRelease?>(null)
    var isDownloadingUpdate by mutableStateOf(false)
    var updateMessage by mutableStateOf<String?>(null)
}
