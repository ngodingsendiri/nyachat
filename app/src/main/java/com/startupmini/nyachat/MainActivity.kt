@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.startupmini.nyachat

import com.startupmini.nyachat.Constants

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.local.AvatarStore
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.local.SecureStorage
import com.startupmini.nyachat.ui.MainAppDialogs
import com.startupmini.nyachat.ui.MainDialogController
import com.startupmini.nyachat.ui.MainOverlays
import com.startupmini.nyachat.ui.MainViewModel
import com.startupmini.nyachat.ui.SyncLifecycleGlue
import com.startupmini.nyachat.ui.TintedSnackbarVisuals
import com.startupmini.nyachat.ui.buildChatCallbacks
import com.startupmini.nyachat.ui.buildRekapCallbacks
import com.startupmini.nyachat.ui.screens.ChatScreen
import com.startupmini.nyachat.ui.screens.GlowingBackground
import com.startupmini.nyachat.ui.screens.MainNavigationBar
import com.startupmini.nyachat.ui.screens.MainTopBar
import com.startupmini.nyachat.ui.screens.RekapScreen
import com.startupmini.nyachat.ui.screens.RekapScreenState
import com.startupmini.nyachat.ui.screens.StartupLoadingScreen
import com.startupmini.nyachat.ui.screens.StartupPhase
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import com.startupmini.nyachat.ui.theme.ExpenseRed
import com.startupmini.nyachat.ui.theme.IncomeGreen
import com.startupmini.nyachat.ui.theme.Motion
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // REDUCED MOTION (audit aksesibilitas 2026-08-12): hormati pengaturan
        // sistem "Hapus animasi" (ANIMATOR_DURATION_SCALE=0) — semua tween snap,
        // spring dipersingkat via Motion.springOrSnap. Di-baca sekali di sini
        // (nilai tidak berubah saat runtime tanpa restart Activity).
        Motion.applySystemSetting(this)

        // Crashlytics: auto-inisialisasi dari google-services. Tambahkan konteks
        // kecil supaya triase crash lebih mudah (build type + versi).
        runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        }

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current

            // C2: Migrasi dari EncryptedSharedPreferences (deprecated) →
            // SharedPreferences biasa (non-secret) + SecureStorage (Keystore, secret).
            // Library security-crypto dihapus; data lama tidak bisa dibaca tanpa library.
            // User perlu memasukkan ulang PIN & API key sekali saja.
            val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
            val secureStorage = SecureStorage

            /** M5: passphrase acak 32 karakter base64 untuk auto-backup terenkripsi.
             *  Dibangkitkan sekali per instalasi & disimpan di Keystore. */
            fun newAutoBackupPassphrase(): String {
                val bytes = ByteArray(24)
                java.security.SecureRandom().nextBytes(bytes)
                return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }

            // State UI dari prefs baru (non-secret)
            var isDarkMode by remember { mutableStateOf(appPrefs.getBoolean(Constants.Prefs.IS_DARK_MODE, false)) }

            CoupleFinanceTheme(darkTheme = isDarkMode) {
                val messages by viewModel.messages.collectAsStateWithLifecycle()
                val transactions by viewModel.transactions.collectAsStateWithLifecycle()
                val activeSender by viewModel.activeSender.collectAsStateWithLifecycle()
                val isAiThinking by viewModel.isAiThinking.collectAsStateWithLifecycle()
                val totalIncome by viewModel.totalIncome.collectAsStateWithLifecycle()
                val totalExpense by viewModel.totalExpense.collectAsStateWithLifecycle()
                val auditReport by viewModel.auditReport.collectAsStateWithLifecycle()
                val isAuditLoading by viewModel.isAuditLoading.collectAsStateWithLifecycle()
                val monthlyReport by viewModel.monthlyReport.collectAsStateWithLifecycle()
                val isMonthlyLoading by viewModel.isMonthlyLoading.collectAsStateWithLifecycle()
                // Audit response (2026-08-12): flag error laporan — dialog menampilkan
                // pesan error + tombol Coba Lagi.
                val isAuditError by viewModel.isAuditError.collectAsStateWithLifecycle()
                val isMonthlyError by viewModel.isMonthlyError.collectAsStateWithLifecycle()
                val weeklyInsights by viewModel.weeklyInsights.collectAsStateWithLifecycle()
                val quickSuggestions by viewModel.quickSuggestions.collectAsStateWithLifecycle()
                val syncStatus by com.startupmini.nyachat.data.remote.FirestoreSyncManager.syncStatus.collectAsStateWithLifecycle()
                // 3.8: waktu terakhir sinkron berhasil → label "Tersinkron · HH:mm" di Rekap.
                val lastSyncedAt by com.startupmini.nyachat.data.remote.FirestoreSyncManager.lastSyncedAt.collectAsStateWithLifecycle()
                // r1.2.3 (P1): daftar member + foto avatar anggota lain (cache disk)
                // — untuk header chat, topbar, dan halaman kelola anggota.
                val members by com.startupmini.nyachat.data.remote.MembershipManager.members.collectAsStateWithLifecycle()
                val memberAvatarPaths by com.startupmini.nyachat.data.remote.MembershipManager.memberAvatarPaths.collectAsStateWithLifecycle()

                // M8: indeks transaksi per pesan (Map) supaya tap badge finansial tidak
                // melakukan scan linear O(n) per komposisi — dibangun ulang hanya saat
                // daftar transaksi berubah.
                val txBySourceCloudId = remember(transactions) {
                    transactions.mapNotNull { it.sourceMessageCloudId?.let { c -> c to it } }.toMap()
                }
                val txByChatMessageId = remember(transactions) {
                    transactions.mapNotNull { it.chatMessageId?.let { id -> id to it } }.toMap()
                }

                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                // TASK-1.3 lanjutan: seluruh state dialog/overlay dipindah ke
                // MainDialogController supaya MainActivity fokus wiring, bukan
                // deklarasi belasan remember state.
                val dialogs = remember { MainDialogController() }

                // BUG-2: draf chat DI-HOIST ke sini dengan rememberSaveable —
                // AnimatedContent menghancurkan state ChatScreen saat pindah tab
                // (Chat ⇄ Rekap), sehingga draf yang diketik tidak hilang. Juga
                // bertahan saat rotasi/config change.
                var chatDraft by rememberSaveable { mutableStateOf("") }

                // Audit UI/UX Rekap: filter bulan/kategori/tab DI-HOIST ke sini
                // dengan rememberSaveable (pola chatDraft) — AnimatedContent
                // menghancurkan state RekapScreen saat pindah tab, sehingga
                // filter yang sedang dipilih tidak hilang. pendingDelete (dialog
                // hapus) sengaja tidak disimpan oleh Saver.
                val rekapState = rememberSaveable(saver = RekapScreenState.Saver) {
                    RekapScreenState()
                }


                // Non-secret dari appPrefs
                var workspaceRole by remember { mutableStateOf(appPrefs.getString(Constants.Prefs.WORKSPACE_ROLE, Constants.Defaults.ROLE)) }
                var userName by remember { mutableStateOf(appPrefs.getString(Constants.Prefs.USER_NAME, null)) }
                // Profil & Akun (r1.2.1): sumber avatar (null=auto→google bila ada,
                // google/custom), path avatar ter-resolve, email akun Google
                // (snapshot — FirebaseAuth bisa null setelah logout).
                var avatarSource by remember { mutableStateOf(appPrefs.getString(Constants.Prefs.AVATAR_SOURCE, null)) }
                var avatarPath by remember { mutableStateOf<String?>(null) }
                var userEmail by remember { mutableStateOf(appPrefs.getString(Constants.Prefs.USER_EMAIL, null)) }
                // Secret dari SecureStorage — dibaca ASYNC (P2-14): dekripsi Keystore
                // jangan diblokir di komposisi. Sementara menunggu, secretsLoaded
                // false → UI menampilkan layar loading singkat (hindari kedip layar PIN).
                var secretsLoaded by remember { mutableStateOf(false) }
                var workspacePin by remember { mutableStateOf<String?>(null) }
                var geminiKey by remember { mutableStateOf<String?>(null) }
                var openRouterKey by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    workspacePin = secureStorage.getSecretAsync(context, Constants.Prefs.WORKSPACE_PIN)
                    geminiKey = secureStorage.getSecretAsync(context, Constants.Prefs.GEMINI_API_KEY)
                    openRouterKey = secureStorage.getSecretAsync(context, Constants.Prefs.OPENROUTER_API_KEY)
                    secretsLoaded = true
                }

                var firebaseReady by remember { mutableStateOf(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) }
                val scope = rememberCoroutineScope()

                // 3.7: izin notifikasi (Android 13+) diminta SEKALI setelah masuk
                // app; kalau ditolak, user tetap bisa menyalakannya via Settings
                // sistem (toggle di Settings app hanya kontrol tampilan notifikasi).
                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(workspacePin, firebaseReady) {
                    if (workspacePin != null && firebaseReady) {
                        // Pastikan token FCM perangkat tersinkron (termasuk user
                        // lama setelah upgrade — onNewToken tidak akan menembak).
                        com.startupmini.nyachat.data.remote.ChatMessageFirebaseService.ensureTokenSynced(context)
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            !appPrefs.getBoolean(Constants.Prefs.NOTIF_PERMISSION_ASKED, false)
                        ) {
                            appPrefs.edit().putBoolean(Constants.Prefs.NOTIF_PERMISSION_ASKED, true).apply()
                            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // ---- Snackbar (audit P1.1): feedback ringan (hasil export, info
                // backup, "Tercatat + Urungkan") tanpa memblokir layar. Host
                // dipasang overlay di Box konten, di atas NavigationBar.
                val snackbarHostState = remember { SnackbarHostState() }
                // Lint LocalContextGetResourceValueCall (compose-bom 2026.06):
                // jangan query resource via LocalContext di dalam LaunchedEffect/coroutine
                // — resolve di composable scope via stringResource, lalu .format() untuk
                // template berargumen.
                val undoLabel = stringResource(R.string.action_undo)
                val txRecordedTemplate = stringResource(R.string.tx_recorded)
                val exportCsvSuccessLabel = stringResource(R.string.export_csv_success)
                val exportCsvFailedLabel = stringResource(R.string.export_csv_failed)
                val showSnack: (String, String?, (() -> Unit)?) -> Unit = { message, actionLabel, onAction ->
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = actionLabel,
                            duration = if (onAction != null) SnackbarDuration.Long else SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
                    }
                }

                // P1 (audit response 2026-08-12): event "Sinkron tersambung kembali"
                // di-emit FirestoreSyncManager tapi TIDAK PERNAH ditampilkan (dead
                // response) — koleksi di sini supaya user tahu koneksi sudah pulih.
                // Aman dikoleksi sejak awal: event hanya muncul saat sync aktif
                // (workspace terhubung), dan stop() menghentikan listener saat logout.
                LaunchedEffect(Unit) {
                    com.startupmini.nyachat.data.remote.FirestoreSyncManager.recoveryEvents
                        .collect { msg -> snackbarHostState.showSnackbar(msg) }
                }

                // P3 (audit response 2026-08-12): undo untuk hapus pesan/transaksi —
                // sejajar dengan undo create. Payload dibawa lewat event supaya dua
                // hapus berurutan tidak menimpa (snackbar bisa antre).
                val txDeletedLabel = stringResource(R.string.chat_tx_deleted)
                val msgDeletedLabel = stringResource(R.string.chat_message_deleted)
                LaunchedEffect(Unit) {
                    viewModel.deleteUndoEvents.collect { payload ->
                        val label = if (payload.transactions.isNotEmpty()) txDeletedLabel else msgDeletedLabel
                        val result = snackbarHostState.showSnackbar(
                            message = label,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(payload)
                    }
                }

                // ---- Profil & Akun (r1.2.1) ----
                // r1.2.3 (P1): sinkronkan avatar Diri Sendiri ke Firestore (Blob JPEG
                // kecil) supaya anggota lain melihatnya di header chat / topbar.
                // Hanya di-upload saat path berubah (dibandingkan prefs) — tidak
                // boros bandwidth di setiap buka app. Reset (null) menghapus foto
                // cloud → anggota lain kembali ke inisial berwarna.
                val syncAvatarIfChanged = {
                    val pin = workspacePin
                    val last = appPrefs.getString(Constants.Prefs.LAST_UPLOADED_AVATAR, null)
                    val current = avatarPath
                    if (pin != null && current != last) {                            scope.launch {
                                val bytes = withContext(Dispatchers.IO) {
                                    current?.let { path ->
                                        AvatarStore.compressAvatarForCloud(context, Uri.fromFile(java.io.File(path)))
                                    }
                                }
                                // Pref baru ditandai HANYA jika upload sukses — kalau
                                // gagal (offline), current != last tetap true dan
                                // dicoba ulang saat resolveAvatar berikutnya.
                                val ok = com.startupmini.nyachat.data.remote.MembershipManager
                                    .uploadMyAvatar(pin, bytes)
                                if (ok) {
                                    appPrefs.edit()
                                        .putString(Constants.Prefs.LAST_UPLOADED_AVATAR, current ?: "__none__")
                                        .apply()
                                }
                            }
                    }
                }

                // Resolve path avatar berdasarkan sumber. Foto Google hanya di-CACHE
                // lokal (google_<uid>.jpg) sebagai avatar aplikasi — akun Google
                // tidak pernah diubah. custom → custom.jpg; google/auto → cache
                // Google bila tersedia (diunduh sekali lalu dipakai offline).
                val resolveAvatar = {
                    val auth = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    val uid = auth?.uid
                    val savedEmail = appPrefs.getString(Constants.Prefs.USER_EMAIL, null)
                    val email = auth?.email ?: savedEmail
                    if (email != null && email != savedEmail) {
                        appPrefs.edit().putString(Constants.Prefs.USER_EMAIL, email).apply()
                    }
                    userEmail = email
                    if (avatarSource == Constants.AvatarSources.CUSTOM) {
                        avatarPath = AvatarStore.getCustomAvatarPath(context)
                    } else {
                        val cached = uid?.let { AvatarStore.getCachedGooglePhoto(context, it) }
                        if (cached != null) {
                            avatarPath = cached
                        } else if (auth?.photoUrl != null && uid != null) {
                            val url = auth.photoUrl.toString()
                            scope.launch {
                                val p = withContext(Dispatchers.IO) {
                                    AvatarStore.cacheGooglePhoto(context, url, uid)
                                }
                                // Jangan menimpa foto custom yang dipilih user saat unduhan selesai.
                                if (p != null && avatarSource != Constants.AvatarSources.CUSTOM) {
                                    avatarPath = p
                                    // r1.2.3 (P1): foto Google yang baru ter-cache di-upload
                                    // sekali agar anggota lain bisa melihatnya.
                                    syncAvatarIfChanged()
                                }
                            }
                        } else {
                            avatarPath = null
                            syncAvatarIfChanged()
                        }
                    }
                }
                // Re-resolve saat workspace connect, login, atau sumber avatar berubah.
                LaunchedEffect(firebaseReady, workspacePin, avatarSource) {
                    if (workspacePin != null && firebaseReady) resolveAvatar()
                }

                // r1.2.3 (P1): map nama-tampilan → path foto avatar untuk header
                // chat & topbar. Kunci = nama & label member (sender pesan bisa
                // salah satu), plus nama user lokal sendiri bila punya foto.
                val senderAvatarPaths = remember(members, memberAvatarPaths, userName, avatarPath) {
                    com.startupmini.nyachat.data.remote.MembershipManager.buildAvatarNameMap(
                        members = members,
                        memberAvatarPaths = memberAvatarPaths,
                        myName = userName,
                        myAvatarPath = avatarPath
                    )
                }

                val avatarSaveFailedLabel = stringResource(R.string.avatar_save_failed)

                val handleAvatarSourceChanged: (String?) -> Unit = { source ->
                    avatarSource = source
                    if (source == null) {
                        appPrefs.edit().remove(Constants.Prefs.AVATAR_SOURCE).apply()
                    } else {
                        appPrefs.edit().putString(Constants.Prefs.AVATAR_SOURCE, source).apply()
                    }
                    resolveAvatar()
                    syncAvatarIfChanged()
                }
                val handleCustomAvatarPicked: (Uri) -> Unit = { uri ->
                    scope.launch {
                        val path = AvatarStore.saveCustomAvatar(context, uri)
                        if (path != null) {
                            avatarPath = path
                            avatarSource = Constants.AvatarSources.CUSTOM
                            appPrefs.edit()
                                .putString(Constants.Prefs.AVATAR_SOURCE, Constants.AvatarSources.CUSTOM)
                                .apply()
                            syncAvatarIfChanged()
                        } else {
                            showSnack(avatarSaveFailedLabel, null, null)
                        }
                    }
                }
                // Audit keanggotaan: sinkronkan nama pilihan user ke member doc
                // Firestore (identitas koheren lintas perangkat). Pref NAME_SYNCED
                // di-set setelah sukses supaya SyncLifecycle tidak menulis ulang di
                // tiap buka app — tapi connect/rename berikutnya tetap menyinkronkan.
                val syncMyName = { name: String ->
                    workspacePin?.let { pin ->
                        scope.launch {
                            if (com.startupmini.nyachat.data.remote.MembershipManager.updateMyIdentity(pin, name)) {
                                appPrefs.edit().putBoolean(Constants.Prefs.NAME_SYNCED, true).apply()
                            }
                        }
                    }
                }

                val handleRenameUser: (String) -> Unit = { newName ->
                    if (newName.isNotBlank()) {
                        userName = newName
                        appPrefs.edit().putString(Constants.Prefs.USER_NAME, newName).apply()
                        // Nama tersimpan user TIDAK ditimpa lagi oleh nama Google
                        // (Google hanya default saat onboarding) — setSender memakai
                        // nilai prefs yang sudah diedit ini.
                        viewModel.setSender(newName)
                        syncMyName(newName)
                    }
                }

                // Feedback "Tercatat" + Urungkan (audit P1.2): setiap transaksi baru
                // (dari chat maupun input manual) memunculkan Snackbar berisi ringkasan
                // nominal + kategori; aksi Urungkan menghapus transaksi tersebut.
                val recordedCurrency = remember {
                    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
                        maximumFractionDigits = 0
                    }
                }
                LaunchedEffect(Unit) {
                    viewModel.transactionRecorded.collect { recorded ->
                        val tx = recorded.transaction
                        val isIncome = tx.type == Constants.TransactionTypes.INCOME
                        val prefix = if (isIncome) "+" else "-"
                        val summary = "$prefix ${recordedCurrency.format(tx.amount)} (${tx.category})"
                        val message = txRecordedTemplate.format(summary)
                        // Audit 2026-08-12: notifikasi transaksi dibedakan warnanya —
                        // pemasukan hijau (IncomeGreen), pengeluaran merah (ExpenseRed),
                        // konsisten dengan badge finansial & Rekap. Notifikasi lain
                        // (backup, export, dll) tetap netral (inverseSurface).
                        val result = snackbarHostState.showSnackbar(
                            TintedSnackbarVisuals(
                                message = message,
                                actionLabel = undoLabel,
                                duration = SnackbarDuration.Long,
                                containerTint = if (isIncome) IncomeGreen else ExpenseRed
                            )
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            // Audit response (2026-08-12): user sudah tahu konsekuensinya
                            // (dia yang menekan Urungkan) — jangan tawarkan undo lagi.
                            viewModel.deleteTransaction(tx, emitUndo = false)
                        }
                    }
                }

                // ---- Export CSV & Backup Google Drive ----
                // P4-4: logika backup/restore Drive diekstrak ke DriveBackupController
                // (data/backup) — MainActivity hanya meng-wire dependency & menampilkan
                // state. Dependency di-assign ulang tiap komposisi supaya controller
                // selalu membaca nilai state terkini (workspacePin, dsb.).
                var backupEncrypted by remember { mutableStateOf(appPrefs.getBoolean(Constants.Prefs.BACKUP_ENCRYPTED, false)) }
                // 3.7: toggle notifikasi chat real-time (default ON).
                var chatNotificationsEnabled by remember {
                    mutableStateOf(appPrefs.getBoolean(Constants.Prefs.CHAT_NOTIFICATIONS_ENABLED, true))
                }
                // Waktu backup Drive terakhir (item 9) — state lokal supaya baris
                // "Backup terakhir" di Pengaturan langsung ter-update tanpa reopen.
                var lastBackupMillis by remember { mutableLongStateOf(appPrefs.getLong(Constants.Prefs.LAST_AUTO_BACKUP, 0L)) }
                // Status enkripsi FILE backup terakhir yang berhasil dibuat — beda
                // dari toggle [backupEncrypted] (setting). Dipakai label status di
                // Settings supaya mencerminkan isi file, bukan setting saat ini.
                // Migrasi user lama: pref baru tak ada → fallback ke toggle saat ini
                // (best-effort, lebih akurat daripada false; backup berikutnya
                // menimpa dengan nilai per-file yang sebenarnya).
                var lastBackupEncrypted by remember {
                    mutableStateOf(
                        appPrefs.getBoolean(
                            Constants.Prefs.LAST_BACKUP_ENCRYPTED,
                            appPrefs.getBoolean(Constants.Prefs.BACKUP_ENCRYPTED, false)
                        )
                    )
                }
                val driveController = remember { DriveBackupController(scope, context) }
                driveController.getWorkspacePin = { workspacePin }
                driveController.buildBackupJson = { viewModel.buildBackupJson(workspacePin) }
                driveController.parseRestore = { json, passphrase -> viewModel.parseRestore(json, passphrase) }
                driveController.restoreParsedBackup = { viewModel.restoreParsedBackup(it) }
                driveController.getEncryptionEnabled = { backupEncrypted }
                // M5: auto-passphrase backup terenkripsi — dibangkitkan sekali &
                // disimpan di SecureStorage (Keystore). Auto-backup 24 jam tetap
                // berjalan walau enkripsi aktif, dan restore di device yang sama
                // otomatis memakai passphrase ini (tanpa dialog).
driveController.getAutoPassphrase = {
                    // silentBackup berjalan di dispatcher IO — keystore ops async.
                    var auto = secureStorage.getSecretAsync(context, Constants.Prefs.BACKUP_AUTO_PASSPHRASE)
                    if (auto.isNullOrBlank()) {
                        auto = newAutoBackupPassphrase()
                        secureStorage.putSecretAsync(context, Constants.Prefs.BACKUP_AUTO_PASSPHRASE, auto)
                    }
                    auto
                }
                driveController.onSuccessfulBackup = { encrypted ->
                    val now = System.currentTimeMillis()
                    appPrefs.edit()
                        .putLong(Constants.Prefs.LAST_AUTO_BACKUP, now)
                        .putBoolean(Constants.Prefs.LAST_BACKUP_ENCRYPTED, encrypted)
                        .apply()
                    lastBackupMillis = now
                    lastBackupEncrypted = encrypted
                }
                val backupMessage by driveController.message.collectAsStateWithLifecycle()
                val restoreBackups by driveController.backups.collectAsStateWithLifecycle()
                val restoreTarget by driveController.restoreTarget.collectAsStateWithLifecycle()
                val pendingCrossFamilyRestore by driveController.crossFamilyRestore.collectAsStateWithLifecycle()
                val driveConsentIntent by driveController.consentIntent.collectAsStateWithLifecycle()
                val backupPassphrasePrompt by driveController.passphrasePrompt.collectAsStateWithLifecycle()

                // Pesan info backup/restore ringan → Snackbar (audit P1.1); dialog
                // hanya untuk alur yang butuh keputusan (pilih file, konfirmasi,
                // passphrase). CSV export & salin PIN ikut lewat jalur yang sama.
                LaunchedEffect(backupMessage) {
                    val msg = backupMessage
                    if (msg != null) {
                        // dismiss SETELAH snackbar selesai: kalau flow di-reset dulu,
                        // kunci LaunchedEffect berubah dan korutin ini dibatalkan —
                        // snackbar ikut terhapus seketika.
                        snackbarHostState.showSnackbar(msg)
                        driveController.dismissMessage()
                    }
                }

                // Temuan #3 live test: passphrase restore salah → snackbar SPESIFIK
                // durasi Long supaya jelas terlihat (sebelumnya dialog hanya menutup
                // tanpa feedback yang teramati). Saluran terpisah dari [backupMessage]
                // agar tidak tertutup modal progres & durasinya tidak ikut Short.
                val backupPassphraseError by driveController.passphraseError.collectAsStateWithLifecycle()
                LaunchedEffect(backupPassphraseError) {
                    val msg = backupPassphraseError
                    if (msg != null) {
                        snackbarHostState.showSnackbar(
                            message = msg,
                            duration = SnackbarDuration.Long
                        )
                        driveController.dismissPassphraseError()
                    }
                }

                // P1-1 (audit keanggotaan): peran bisa berubah di device lain (owner
                // di-demote/promote). Ikuti perubahan role dari snapshot members —
                // update prefs, state UI, dan manager sync tanpa restart app.
                // remember(firebaseReady): uid baru tersedia SETELAH login — key ini
                // memastikan myUid dihitung ulang saat user masuk (jangan terkunci null
                // dari komposisi pertama di layar login).
                val myUid = remember(firebaseReady) {
                    com.startupmini.nyachat.data.remote.MembershipManager.currentUid()
                }
                val myRole = remember(members) { members.firstOrNull { it.uid == myUid }?.role }
                LaunchedEffect(myRole, workspaceRole) {
                    val newRole = myRole
                    if (newRole != null && workspaceRole != null && newRole != workspaceRole) {
                        workspaceRole = newRole
                        appPrefs.edit().putString(Constants.Prefs.WORKSPACE_ROLE, newRole).apply()
                        com.startupmini.nyachat.data.remote.MembershipManager.updateRole(newRole)
                        com.startupmini.nyachat.data.remote.FirestoreSyncManager.setRole(newRole)
                    }
                }

                // Bersihkan sesi & kembali ke layar login setelah logout.
                val performLogoutCleanup = {
                    viewModel.stopCloudSync()
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    firebaseReady = false
                    appPrefs.edit().clear().apply()
                    secureStorage.clearAll(context)
                    workspaceRole = Constants.Defaults.ROLE
                    userName = null
                    avatarSource = null
                    avatarPath = null
                    userEmail = null
                    workspacePin = null
                    geminiKey = null
                    openRouterKey = null
                    dialogs.showSettingsSheet = false
                    dialogs.showProfileAccount = false
                    // BUG-2 lanjutan (reviewer): draf yang di-hoist tidak boleh bocor
                    // ke workspace berikutnya — dibersihkan saat logout.
                    chatDraft = ""
                    // Audit UI/UX Rekap (reviewer): filter Rekap juga di-reset —
                    // jangan mewarisi bulan/kategori/tab dari workspace lama.
                    rekapState.selectedMonth = null
                    rekapState.selectedCategory = null
                    rekapState.selectedFilterTab = 0
                }

                // Audit keanggotaan: member di-kick/ditolak ≠ logout penuh — kembali
                // ke layar PIN tapi PERTAHANKAN sesi Google & API key BYOK (user tidak
                // perlu login ulang untuk membuat/bergabung workspace lain).
                // Audit workspace (reviewer): beri tahu user DENGAN SNACKBAR kenapa
                // dia kembali ke layar PIN — sebelumnya kembali diam-diam tanpa
                // penjelasan (dari kickedEvents foreground maupun resume A3).
                val kickedMessage = stringResource(R.string.membership_kicked_message)
                val performKickedCleanup = {
                    viewModel.stopCloudSync()
                    workspaceRole = Constants.Defaults.ROLE
                    workspacePin = null
                    dialogs.showSettingsSheet = false
                    dialogs.showProfileAccount = false
                    // BUG-2 lanjutan: draf tidak boleh bocor ke workspace berikutnya.
                    chatDraft = ""
                    // Audit UI/UX Rekap (reviewer): filter Rekap di-reset juga saat
                    // di-kick — workspace baru harus mulai dari "Semua".
                    rekapState.selectedMonth = null
                    rekapState.selectedCategory = null
                    rekapState.selectedFilterTab = 0
                    scope.launch {
                        secureStorage.deleteSecretAsync(context, Constants.Prefs.WORKSPACE_PIN)
                    }
                    appPrefs.edit()
                        .remove(Constants.Prefs.WORKSPACE_ROLE)
                        .remove(Constants.Prefs.LAST_UPLOADED_AVATAR)
                        .apply()
                    showSnack(kickedMessage, null, null)
                }

                // Audit workspace (2026-08-12): kick saat app TERBUKA — owner
                // menghapus anggota di device lain, listener members kena
                // PERMISSION_DENIED → langsung kembali ke layar PIN (sebelumnya
                // hanya terdeteksi saat resume, lihat SyncLifecycle A3).
                // Diletakkan SETELAH definisi performKickedCleanup (referensi val
                // lokal harus dideklarasikan lebih dulu).
                val kickedEvents by com.startupmini.nyachat.data.remote.MembershipManager.kickedEvents.collectAsStateWithLifecycle()
                LaunchedEffect(kickedEvents) {
                    if (kickedEvents > 0) performKickedCleanup()
                }

                // TASK-1.3.3: seluruh lifecycle glue (sync, API key, update check,
                // auto-backup, pause/resume listener, re-check membership) hidup
                // di SyncLifecycleGlue — MainActivity hanya wiring dependency.
                SyncLifecycleGlue(
                    viewModel = viewModel,
                    workspacePin = workspacePin,
                    workspaceRole = workspaceRole,
                    userName = userName,
                    firebaseReady = firebaseReady,
                    isGateActive = dialogs.connectGate != null,
                    geminiKey = geminiKey,
                    openRouterKey = openRouterKey,
                    appPrefs = appPrefs,
                    driveController = driveController,
                    scope = scope,
                    onLogoutCleanup = { performLogoutCleanup() },
                    onKickedCleanup = { performKickedCleanup() },
                    onUpdateAvailable = { dialogs.updateInfo = it }
                )

                // Launcher konsen OAuth Drive (muncul sekali; setelah disetujui
                // aksi diulang otomatis via DriveBackupController.onConsentResult).
                // Kalau user menekan Batal (bukan OK), aksi tidak diulang supaya
                // tidak muncul dialog berulang-ulang.
                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    driveController.onConsentResult(result.resultCode == android.app.Activity.RESULT_OK)
                }
                LaunchedEffect(driveConsentIntent) {
                    driveConsentIntent?.let {
                        driveController.consumeConsentIntent()
                        consentLauncher.launch(it)
                    }
                }

                // Launcher simpan CSV via Storage Access Framework (pilih folder, biasanya Download)
                val exportCsvLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/csv")
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            val csv = viewModel.exportRecapCsv()
                            val ok = runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { out ->
                                    out.write(csv.toByteArray(Charsets.UTF_8))
                                }
                            }.isSuccess
                            showSnack(
                                if (ok) exportCsvSuccessLabel else exportCsvFailedLabel,
                                null,
                                null
                            )
                        }
                    }
                }

                // Catatan (P4-4): seluruh alur token OAuth, upload, daftar, unduh, prune,
                // restore & deteksi lintas-workspace kini hidup di DriveBackupController.

                // Hubungkan workspace: simpan pref & masuk ke chat.
                val applyPinConnect: (String, String, String) -> Unit = { pin, role, name ->
                    // Audit UI/UX Rekap (reviewer): PIN berbeda = workspace lain —
                    // reset filter Rekap supaya tidak mewarisi bulan/kategori/tab
                    // workspace sebelumnya (konsisten dengan isolasi chatDraft).
                    if (workspacePin != null && workspacePin != pin) {
                        rekapState.selectedMonth = null
                        rekapState.selectedCategory = null
                        rekapState.selectedFilterTab = 0
                    }
                    firebaseReady = true
                    secureStorage.putSecret(context, Constants.Prefs.WORKSPACE_PIN, pin)
                    appPrefs.edit()
                        .putString(Constants.Prefs.WORKSPACE_ROLE, role)
                        .putString(Constants.Prefs.USER_NAME, name)
                        .apply()
                    workspacePin = pin
                    workspaceRole = role
                    userName = name
                    // BUG-2 lanjutan (reviewer): ganti PIN/log-in workspace baru juga
                    // membersihkan draf — isolasi antar-workspace.
                    chatDraft = ""
                    viewModel.setSender(name)
                    // Audit keanggotaan: nama yang dipilih user langsung disinkronkan ke
                    // member doc Firestore (doc member joiner dibuat owner dengan nama
                    // Google — nama pilihan user harus jadi sumber kebenaran).
                    syncMyName(name)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    GlowingBackground()

                    // TASK-1.3.2: callback kelompok layar diekstrak ke interface
                    // ChatCallbacks/RekapCallbacks (build*Callbacks di MainCallbacks.kt).
                    // MainActivity tetap memegang state UI (editTarget, dialog, reset
                    // chat) dan hanya menyerahkan setter-nya ke factory.
                    val chatCallbacks = buildChatCallbacks(
                        viewModel = viewModel,
                        txBySourceCloudId = txBySourceCloudId,
                        txByChatMessageId = txByChatMessageId,
                        onEditTarget = { dialogs.editTarget = it },
                        onSetResetChatOnDialogClose = { dialogs.resetChatOnDialogClose = it },
                        onSetShowAddDialog = { dialogs.showAddDialog = it },
                        showSnack = showSnack,
                        chatTransactionNotFoundMessage = stringResource(R.string.chat_transaction_not_found)
                    )
                    val rekapCallbacks = buildRekapCallbacks(
                        viewModel = viewModel,
                        onEditTarget = { dialogs.editTarget = it },
                        onSetResetChatOnDialogClose = { dialogs.resetChatOnDialogClose = it },
                        onSetShowAddDialog = { dialogs.showAddDialog = it }
                    )

                    // Edge-to-edge (wajib di targetSdk 36): Column induk menata konten
                    // vs NavigationBar secara vertikal. Box konten memakai weight(1f)
                    // sehingga berhenti tepat di atas navbar — tanpa offset hardcoded,
                    // dan insets navbar bawah ditangani NavigationBar sendiri (default
                    // M3). Urutan komposisi menjaga focus order F1: TopAppBar → konten
                    // → NavigationBar.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    // Audit motion startup (2026-08-12): fase pembukaan app di-rapikan
                    // jadi satu alur mengalir — Loading (secret Keystore) → Pin/login →
                    // Main. Sebelumnya if/else langsung menukar layar (hard cut) sehingga
                    // pembukaan terasa "melompat-lompat". Kini AnimatedContent
                    // meng-crossfade antarfase dengan zoom halus (FastOutSlowIn, motion
                    // language yang sama dengan tab).
                    val startupPhase = when {
                        !secretsLoaded -> StartupPhase.Loading
                        workspacePin == null || userName == null || !firebaseReady -> StartupPhase.Pin
                        else -> StartupPhase.Main
                    }
                    AnimatedContent(
                        targetState = startupPhase,
                        transitionSpec = {
                            // Semua fase full-screen: fade lembut + zoom 0.97→1 (subtle,
                            // bukan overshoot). Exit lebih cepat (base) agar terasa
                            // responsif tapi tetap satu karakter.
                            (fadeIn(animationSpec = Motion.base()) +
                                androidx.compose.animation.scaleIn(
                                    initialScale = 0.97f,
                                    animationSpec = Motion.base()
                                )) togetherWith
                                fadeOut(animationSpec = Motion.quick())
                        },
                        label = "startupPhase"
                    ) { phase ->
                        when (phase) {
                            StartupPhase.Loading -> {
                                // Loading singkat sambil secret (PIN/API key) didekripsi async.
                                StartupLoadingScreen()
                            }

                            StartupPhase.Pin -> {
                        // F2 (audit focus order): saat gate keanggotaan aktif (connectGate),
                        // background layar PIN ditandai invisibleToUser() supaya TalkBack &
                        // fokus keyboard tidak menjangkau elemen di balik gate.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (dialogs.connectGate != null) {
                                        Modifier.semantics { invisibleToUser() }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            com.startupmini.nyachat.ui.screens.PinConnectScreen(
                                onPinConnected = { pin, role, name ->
                                val previous = workspacePin
                                if (previous != null && previous != pin) {
                                    // PIN berbeda → isolasi workspace: konfirmasi dulu
                                    // sebelum menghapus data lokal workspace lama.
                                    dialogs.pendingPinConnect = Triple(pin, role, name)
                                } else {
                                    // Melewati gate keanggotaan dulu sebelum masuk.
                                    dialogs.connectGate = Triple(pin, role, name)
                                }
                            }
                        )
                        }
                            }

                            StartupPhase.Main -> {
                        // F1 (audit focus order): Scaffold M3 mengkomposisikan fokus
                        // TopBar → BottomBar → Konten (lihat urutan subcompose di
                        // Scaffold.kt), sehingga Tab melompat ke navbar SEBELUM konten.
                        // Column manual → urutan fokus TopAppBar → konten → NavigationBar.
                        Column(modifier = Modifier.fillMaxSize()) {
                            MainTopBar(
                                messages = messages,
                                userName = userName,
                                memberAvatarPaths = senderAvatarPaths,
                                onManageMembers = { dialogs.showManageMembers = true },
                                onSettings = { dialogs.showSettingsSheet = true }
                            )

                            // Konten layar (tab aktif) — langsung di bawah topbar.
                            // (F1: NavigationBar dipindah ke BAWAH Column — setelah konten —
                            // supaya urutan fokus = TopAppBar → konten → NavigationBar.)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    // Setara dengan contentWindowInsets horizontal Scaffold
                                    // (aman saat landscape/display cutout; 0 di portrait).
                                    .windowInsetsPadding(
                                        WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                                    )
                            ) {
                                AnimatedContent(
                                    targetState = selectedTab,
                                    transitionSpec = {
                                        val forward = targetState > initialState
                                        val enter = slideInHorizontally(
                                            initialOffsetX = { if (forward) it / 5 else -it / 5 },
                                            animationSpec = Motion.nav()
                                        ) + fadeIn(animationSpec = Motion.nav())
                                        // Audit motion (2026-08-12): exit diselaraskan
                                        // ke nav() juga — sebelumnya fadeOut base (250ms)
                                        // lebih cepat dari enter nav (300ms), selisih 50ms
                                        // yang melanggar satu hierarki durasi.
                                        val exit = slideOutHorizontally(
                                            targetOffsetX = { if (forward) -it / 5 else it / 5 },
                                            animationSpec = Motion.nav()
                                        ) + fadeOut(animationSpec = Motion.nav())
                                        enter togetherWith exit
                                    },
                                    label = "tabContent"
                                ) { tab ->
                                    when (tab) {
                                        0 -> ChatScreen(
                                            quickSuggestions = quickSuggestions,
                                            // BUG-2: draf di-hoist ke MainActivity.
                                            draftText = chatDraft,
                                            onDraftChange = { chatDraft = it },
                                            resetChatInputTrigger = dialogs.chatResetTrigger,
                                            messages = messages,
                                            activeSender = activeSender,
                                            isAiThinking = isAiThinking,
                                            workspacePin = workspacePin,
                                            senderAvatarPaths = senderAvatarPaths,
                                            onSendMessage = chatCallbacks::onSendMessage,
                                            onEditMessage = chatCallbacks::onEditMessage,
                                            onAskAiClicked = chatCallbacks::onAskAiClicked,
                                            onDeleteMessage = chatCallbacks::onDeleteMessage,
                                            onOpenTransaction = chatCallbacks::onOpenTransaction
                                        )

                                        1 -> RekapScreen(
                                            state = rekapState,
                                            transactions = transactions,
                                            totalIncome = totalIncome,
                                            totalExpense = totalExpense,
                                            isAuditLoading = isAuditLoading,
                                            onGenerateAudit = rekapCallbacks::onGenerateAudit,
                                            isMonthlyLoading = isMonthlyLoading,
                                            insights = weeklyInsights,
                                            onGenerateMonthly = rekapCallbacks::onGenerateMonthly,
                                            onAddTransactionClicked = rekapCallbacks::onAddTransactionClicked,
                                            onDeleteTransaction = rekapCallbacks::onDeleteTransaction,
                                            onEditTransaction = rekapCallbacks::onEditTransaction,
                                            syncStatus = syncStatus,
                                            lastSyncedAtMillis = lastSyncedAt
                                        )
                                    }
                                }
                            }

                            // TASK-1.3 lanjutan: dialog lapisan konten (transaksi,
                            // settings, API key, PIN, clear data, logout, AI report)
                            // dipindah ke MainAppDialogs.kt.
                            MainAppDialogs(
                                viewModel = viewModel,
                                context = context,
                                appPrefs = appPrefs,
                                secureStorage = secureStorage,
                                scope = scope,
                                dialogs = dialogs,
                                isDarkMode = isDarkMode,
                                backupEncrypted = backupEncrypted,
                                lastBackupMillis = lastBackupMillis,
                                lastBackupEncrypted = lastBackupEncrypted,
                                userName = userName,
                                workspaceRole = workspaceRole,
                                workspacePin = workspacePin,
                                geminiKey = geminiKey,
                                openRouterKey = openRouterKey,
                                auditReport = auditReport,
                                monthlyReport = monthlyReport,
                                auditError = isAuditError,
                                monthlyError = isMonthlyError,
                                onRetryAudit = { viewModel.generateAiAuditReport() },
                                onRetryMonthly = { viewModel.generateMonthlyAnalysis() },
                                driveController = driveController,
                                exportCsvLauncher = exportCsvLauncher,
                                showSnack = showSnack,
                                onToggleDarkMode = {
                                    isDarkMode = !isDarkMode
                                    appPrefs.edit().putBoolean(Constants.Prefs.IS_DARK_MODE, isDarkMode).apply()
                                },
                                chatNotificationsEnabled = chatNotificationsEnabled,
                                onToggleChatNotifications = {
                                    chatNotificationsEnabled = !chatNotificationsEnabled
                                    appPrefs.edit()
                                        .putBoolean(Constants.Prefs.CHAT_NOTIFICATIONS_ENABLED, chatNotificationsEnabled)
                                        .apply()
                                },
                                onToggleBackupEncryption = {
                                    backupEncrypted = !backupEncrypted
                                    appPrefs.edit().putBoolean(Constants.Prefs.BACKUP_ENCRYPTED, backupEncrypted).apply()
                                },
                                onPrivacyPolicy = {
                                    // Audit menu Pengaturan (2026-08-12): kebijakan
                                    // privasi dibuka di browser (pola sama dengan
                                    // halaman rilis update).
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            Uri.parse(Constants.Links.PRIVACY_POLICY)
                                        )
                                    )
                                },
                                onGeminiKeySaved = { geminiKey = it },
                                onOpenRouterKeySaved = { openRouterKey = it },
                                userEmail = userEmail,
                                avatarPath = avatarPath,
                                hasGooglePhoto = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.photoUrl != null,
                                avatarSource = avatarSource,
                                onAvatarSourceChanged = handleAvatarSourceChanged,
                                onCustomAvatarPicked = handleCustomAvatarPicked,
                                onRenameUser = handleRenameUser,
                                onPerformLogoutCleanup = { performLogoutCleanup() }
                            )
                        }
                            }
                        }
                    }

                    // TASK-1.3 lanjutan: overlay global (gate keanggotaan, kelola
                    // anggota, ganti workspace, update, backup/restore Drive, snackbar)
                    // dipindah ke MainOverlays.kt.
                    MainOverlays(
                        viewModel = viewModel,
                        context = context,
                        scope = scope,
                        dialogs = dialogs,
                        driveController = driveController,
                        snackbarHostState = snackbarHostState,
                        workspacePin = workspacePin,
                        workspaceRole = workspaceRole,
                        onApplyPinConnect = applyPinConnect
                    )
                        }

                // NavigationBar — anak terakhir Column induk. Hanya tampil saat
                // berada di alur app utama (sudah login & punya workspace). Di layar
                // login/PIN/gate keanggotaan navbar disembunyikan supaya onboarding
                // tetap fokus & tidak terlihat "menu chat/rekap bocor" sebelum masuk.
                val isInMainApp = secretsLoaded &&
                    workspacePin != null && userName != null && firebaseReady && dialogs.connectGate == null
                val imeVisible = WindowInsets.isImeVisible
                AnimatedVisibility(
                    visible = isInMainApp && !imeVisible,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.base()) + fadeIn(animationSpec = Motion.base()),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.base()) + fadeOut(animationSpec = Motion.base())
                ) {
                    MainNavigationBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                }
                    }
                }
            }
        }
    }
}

