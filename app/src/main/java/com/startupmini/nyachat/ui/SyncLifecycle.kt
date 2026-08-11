@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.startupmini.nyachat.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.remote.FirestoreSyncManager
import com.startupmini.nyachat.data.remote.GeminiService
import com.startupmini.nyachat.data.remote.GitHubRelease
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import com.startupmini.nyachat.data.remote.MembershipManager
import com.startupmini.nyachat.data.remote.MembershipStatus
import com.startupmini.nyachat.data.remote.NetworkMonitor
import com.startupmini.nyachat.data.remote.OpenRouterService
import com.startupmini.nyachat.data.remote.RelayAiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * TASK-1.3.3 — ekstraksi "lifecycle glue" dari MainActivity (no behavior change).
 *
 * Merangkum SEMUA efek sisi yang mengikuti lifecycle komposisi/activity:
 * - Start/stop cloud sync & membership saat PIN workspace berubah.
 * - Menyalurkan API key Gemini/OpenRouter (BYOK) dari SecureStorage ke service.
 * - Cek update otomatis (throttle 1 jam).
 * - Auto-backup Google Drive 24 jam saat app dibuka.
 * - Pause/resume listener realtime Firestore mengikuti lifecycle activity (P2-12/P4-2).
 * - Re-check keanggotaan saat app resume (A3) — handle owner menolak/meng-kick
 *   di device lain saat app di background.
 *
 * Semua dependency (viewModel, prefs, controller, callback) di-pass sebagai
 * parameter supaya MainActivity hanya melakukan wiring, bukan logika.
 */
@Composable
fun SyncLifecycleGlue(
    viewModel: MainViewModel,
    workspacePin: String?,
    workspaceRole: String?,
    userName: String?,
    firebaseReady: Boolean,
    isGateActive: Boolean,
    geminiKey: String?,
    openRouterKey: String?,
    appPrefs: SharedPreferences,
    driveController: DriveBackupController,
    scope: CoroutineScope,
    onLogoutCleanup: () -> Unit,
    onUpdateAvailable: (GitHubRelease) -> Unit
) {
    // BUG-06 lanjutan (P0): deteksi jaringan — tanpa ini, indikator sync tetap
    // "Tersinkron" saat offline murni (snapshot Firestore dari offline cache
    // selalu sukses → markSynced). Monitor hidup selama komposisi glue; status
    // diteruskan ke FirestoreSyncManager.setNetworkAvailable. Izin
    // ACCESS_NETWORK_STATE ditambahkan di AndroidManifest.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val monitor = NetworkMonitor(context) { online ->
            FirestoreSyncManager.setNetworkAvailable(online)
            // Relay AI (FASE 4): saat jaringan jelas mati, jangan coba panggil
            // Cloud Function — langsung heuristik offline (hindari timeout).
            RelayAiService.setNetworkOnline(online)
        }
        monitor.start()
        // Kirim status awal sekali — callback tidak selalu langsung menembak
        // (mis. jaringan stabil) dan status lama bisa saja "menggantung".
        val onlineNow = monitor.isOnlineNow
        FirestoreSyncManager.setNetworkAvailable(onlineNow)
        RelayAiService.setNetworkOnline(onlineNow)
        onDispose {
            monitor.stop()
        }
    }

    // Start/stop cloud sync & membership saat PIN workspace berubah.
    LaunchedEffect(workspacePin, userName) {
        val pin = workspacePin
        if (pin != null) {
            viewModel.startCloudSync(pin, workspaceRole ?: Constants.Roles.MEMBER)
            // r1.2.3 (P1): konteks untuk cache avatar anggota lain ke disk.
            MembershipManager.start(pin, workspaceRole ?: Constants.Roles.MEMBER, context)
        } else {
            viewModel.stopCloudSync()
        }
        userName?.let { viewModel.setSender(it) }
    }

    LaunchedEffect(geminiKey) {
        GeminiService.userApiKey = geminiKey
    }
    LaunchedEffect(openRouterKey) {
        OpenRouterService.userApiKey = openRouterKey
    }

    // Cek update otomatis (throttle 1 jam biar gak nembak GitHub API tiap buka app).
    // Timestamp cuma di-set kalau ceknya SUKSES — kalau gagal (offline/rate-limit),
    // cooldown tidak terpakai dan dicoba lagi saat app dibuka berikutnya.
    LaunchedEffect(Unit) {
        val lastCheck = appPrefs.getLong(Constants.Prefs.LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - lastCheck > 60 * 60 * 1000L) {
            val release = GitHubUpdateChecker.checkLatest()
            if (release != null) {
                appPrefs.edit().putLong(Constants.Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                if (GitHubUpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                    onUpdateAvailable(release)
                }
            }
        }
    }

    // Backup otomatis (menyerupai WhatsApp): sekali setiap 24 jam saat app dibuka,
    // bila sudah login & pernah menyetujui akses Google Drive. Berjalan diam-diam;
    // kalau belum pernah menyetujui konsen Drive, dilewati tanpa dialog. (P4-4:
    // logika pindah ke DriveBackupController.silentBackup — timestamp auto-backup
    // di-set lewat onSuccessfulBackup.)
    LaunchedEffect(workspacePin, firebaseReady) {
        val pin = workspacePin
        if (pin == null || !firebaseReady) return@LaunchedEffect
        val last = appPrefs.getLong(Constants.Prefs.LAST_AUTO_BACKUP, 0L)
        if (System.currentTimeMillis() - last > 24 * 60 * 60 * 1000L) {
            runCatching { driveController.silentBackup() }
        }
    }

    // P2-12: pause/resume listener realtime mengikuti lifecycle activity.
    // Saat app di background, snapshot listener Firestore diputus (hemat
    // baterai & kuota); saat kembali ke foreground dipasang ulang dan
    // menerima snapshot terbaru — data tidak hilang karena Room adalah
    // sumber kebenaran lokal.
    // P4-2: LifecycleResumeEffect menggantikan LifecycleObserver yang
    // deprecated (menghilangkan warning kompilasi). Blok berjalan saat
    // lifecycle RESUMED; onPauseOrDispose dipanggil saat turun ke PAUSED
    // atau composable dibuang.
    LifecycleResumeEffect(Unit) {
        FirestoreSyncManager.resumeListeners()
        MembershipManager.resumeListeners()
        onPauseOrDispose {
            FirestoreSyncManager.pauseListeners()
            MembershipManager.pauseListeners()
        }
    }

    // A3: Re-check keanggotaan saat app resume (ON_RESUME).
    // Kalau sudah login & punya workspace tapi BUKAN sedang di gate,
    // cek ulang status keanggotaan — handle kasus owner menolak/setujui
    // di device lain saat app di background.
    LifecycleResumeEffect(workspacePin, firebaseReady, isGateActive) {
        val pin = workspacePin
        if (pin != null && firebaseReady && !isGateActive) {
            scope.launch {
                val status = MembershipManager.checkMembership(pin)
                when (status) {
                    MembershipStatus.FAMILY_NOT_FOUND,
                    MembershipStatus.NOT_REQUESTED -> {
                        // Workspace dihapus atau user dikick/ditolak di device lain.
                        // Reset state lokal & kembali ke layar PIN.
                        onLogoutCleanup()
                    }
                    MembershipStatus.FAILED -> {
                        // Error jaringan — biarkan state apa adanya, coba lagi nanti.
                    }
                    else -> { /* MEMBER atau PENDING — OK, lanjut */ }
                }
            }
        }
        onPauseOrDispose { }
    }
}
