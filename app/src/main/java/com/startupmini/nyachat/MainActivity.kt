@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.startupmini.nyachat

import com.startupmini.nyachat.Constants

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.local.SecureStorage
import com.startupmini.nyachat.data.remote.GitHubRelease
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import com.startupmini.nyachat.ui.MainViewModel
import com.startupmini.nyachat.ui.screens.AddTransactionDialog
import com.startupmini.nyachat.ui.screens.AiReportDialog
import com.startupmini.nyachat.ui.screens.ApiKeyDialog
import com.startupmini.nyachat.ui.screens.BackupProgressDialog
import com.startupmini.nyachat.ui.screens.ChatScreen
import com.startupmini.nyachat.ui.screens.ConfirmClearDataDialog
import com.startupmini.nyachat.ui.screens.CrossFamilyRestoreDialog
import com.startupmini.nyachat.ui.screens.GlowingBackground
import com.startupmini.nyachat.ui.screens.LogoutDialog
import com.startupmini.nyachat.ui.screens.MainNavigationBar
import com.startupmini.nyachat.ui.screens.MainTopBar
import com.startupmini.nyachat.ui.screens.PassphraseDialog
import com.startupmini.nyachat.ui.screens.PinDisplayDialog
import com.startupmini.nyachat.ui.screens.PinSwitchDialog
import com.startupmini.nyachat.ui.screens.RekapScreen
import com.startupmini.nyachat.ui.screens.RestoreConfirmDialog
import com.startupmini.nyachat.ui.screens.RestorePickerDialog
import com.startupmini.nyachat.ui.screens.SettingsSheet
import com.startupmini.nyachat.ui.screens.UpdateAvailableDialog
import com.startupmini.nyachat.ui.screens.UpdateMessageDialog
import com.startupmini.nyachat.ui.screens.installApk
import com.startupmini.nyachat.ui.screens.timestampForFile
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                val weeklyInsights by viewModel.weeklyInsights.collectAsStateWithLifecycle()
                val quickSuggestions by viewModel.quickSuggestions.collectAsStateWithLifecycle()
                val syncStatus by com.startupmini.nyachat.data.remote.FirestoreSyncManager.syncStatus.collectAsStateWithLifecycle()

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
                var showAddDialog by remember { mutableStateOf(false) }
                // BUG-08: dinaikkan tiap dialog transaksi manual ditutup — ChatScreen
                // mereset input-nya supaya karakter sisa tidak menempel di field chat.
                var chatResetTrigger by remember { mutableIntStateOf(0) }
                // Gate BUG-08: reset field chat HANYA saat dialog dibuka dari tab Chat
                // (tap badge finansial). Dialog dari tab Rekap tidak boleh menghapus
                // draf chat user yang diketik sebelum pindah tab.
                var resetChatOnDialogClose by remember { mutableStateOf(false) }
                var showSettingsSheet by remember { mutableStateOf(false) }
                var showManageMembers by remember { mutableStateOf(false) }
                var showGeminiKeyDialog by remember { mutableStateOf(false) }
                var showLogoutDialog by remember { mutableStateOf(false) }
                var pendingPinConnect by remember { mutableStateOf<Triple<String, String, String>?>(null) }
                var connectGate by remember { mutableStateOf<Triple<String, String, String>?>(null) }
                var showOpenRouterKeyDialog by remember { mutableStateOf(false) }
                var showConfirmClearDialog by remember { mutableStateOf(false) }
                var showPinDialog by remember { mutableStateOf(false) }
                var editTarget by remember { mutableStateOf<FinancialTransaction?>(null) }

                // Non-secret dari appPrefs
                var workspaceRole by remember { mutableStateOf(appPrefs.getString(Constants.Prefs.WORKSPACE_ROLE, Constants.Defaults.ROLE)) }
                var userName by remember { mutableStateOf(appPrefs.getString(Constants.Prefs.USER_NAME, null)) }
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
                var updateInfo by remember { mutableStateOf<GitHubRelease?>(null) }
                var isDownloadingUpdate by remember { mutableStateOf(false) }
                var updateMessage by remember { mutableStateOf<String?>(null) }

                // ---- Snackbar (audit P1.1): feedback ringan (hasil export, info
                // backup, "Tercatat + Urungkan") tanpa memblokir layar. Host
                // dipasang overlay di Box konten, di atas NavigationBar.
                val snackbarHostState = remember { SnackbarHostState() }
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

                // Feedback "Tercatat" + Urungkan (audit P1.2): setiap transaksi baru
                // (dari chat maupun input manual) memunculkan Snackbar berisi ringkasan
                // nominal + kategori; aksi Urungkan menghapus transaksi tersebut.
                val recordedCurrency = remember {
                    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
                        maximumFractionDigits = 0
                    }
                }
                LaunchedEffect(Unit) {
                    val undoLabel = context.getString(R.string.action_undo)
                    viewModel.transactionRecorded.collect { recorded ->
                        val tx = recorded.transaction
                        val prefix = if (tx.type == Constants.TransactionTypes.INCOME) "+" else "-"
                        val summary = "$prefix ${recordedCurrency.format(tx.amount)} (${tx.category})"
                        val message = context.getString(R.string.tx_recorded, summary)
                        val result = snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.deleteTransaction(tx)
                        }
                    }
                }

                // ---- Export CSV & Backup Google Drive ----
                // P4-4: logika backup/restore Drive diekstrak ke DriveBackupController
                // (data/backup) — MainActivity hanya meng-wire dependency & menampilkan
                // state. Dependency di-assign ulang tiap komposisi supaya controller
                // selalu membaca nilai state terkini (workspacePin, dsb.).
                var backupEncrypted by remember { mutableStateOf(appPrefs.getBoolean(Constants.Prefs.BACKUP_ENCRYPTED, false)) }
                // Waktu backup Drive terakhir (item 9) — state lokal supaya baris
                // "Backup terakhir" di Pengaturan langsung ter-update tanpa reopen.
                var lastBackupMillis by remember { mutableLongStateOf(appPrefs.getLong(Constants.Prefs.LAST_AUTO_BACKUP, 0L)) }
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
                driveController.onSuccessfulBackup = {
                    val now = System.currentTimeMillis()
                    appPrefs.edit().putLong(Constants.Prefs.LAST_AUTO_BACKUP, now).apply()
                    lastBackupMillis = now
                }
                val backupBusy by driveController.busy.collectAsStateWithLifecycle()
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

                // Bersihkan sesi & kembali ke layar login setelah logout.
                val performLogoutCleanup = {
                    viewModel.stopCloudSync()
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    firebaseReady = false
                    appPrefs.edit().clear().apply()
                    secureStorage.clearAll(context)
                    workspaceRole = Constants.Defaults.ROLE
                    userName = null
                    workspacePin = null
                    geminiKey = null
                    openRouterKey = null
                    showSettingsSheet = false
                }

                LaunchedEffect(workspacePin, userName) {
                    val pin = workspacePin
                    if (pin != null) {
                        viewModel.startCloudSync(pin, workspaceRole ?: Constants.Roles.MEMBER)
                        com.startupmini.nyachat.data.remote.MembershipManager.start(
                            pin, workspaceRole ?: Constants.Roles.MEMBER
                        )
                    } else {
                        viewModel.stopCloudSync()
                    }
                    userName?.let { viewModel.setSender(it) }
                }
                LaunchedEffect(geminiKey) {
                    com.startupmini.nyachat.data.remote.GeminiService.userApiKey = geminiKey
                }
                LaunchedEffect(openRouterKey) {
                    com.startupmini.nyachat.data.remote.OpenRouterService.userApiKey = openRouterKey
                }
                LaunchedEffect(Unit) {
                    // Cek update otomatis (throttle 1 jam biar gak nembak GitHub API tiap buka app).
                    // Timestamp cuma di-set kalau ceknya SUKSES — kalau gagal (offline/rate-limit),
                    // cooldown tidak terpakai dan dicoba lagi saat app dibuka berikutnya.
                    val lastCheck = appPrefs.getLong(Constants.Prefs.LAST_UPDATE_CHECK, 0L)
                    if (System.currentTimeMillis() - lastCheck > 60 * 60 * 1000L) {
                        val release = GitHubUpdateChecker.checkLatest()
                        if (release != null) {
                            appPrefs.edit().putLong(Constants.Prefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                            if (GitHubUpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                                updateInfo = release
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
                com.startupmini.nyachat.data.remote.FirestoreSyncManager.resumeListeners()
                com.startupmini.nyachat.data.remote.MembershipManager.resumeListeners()
                onPauseOrDispose {
                    com.startupmini.nyachat.data.remote.FirestoreSyncManager.pauseListeners()
                    com.startupmini.nyachat.data.remote.MembershipManager.pauseListeners()
                }
            }

            // A3: Re-check keanggotaan saat app resume (ON_RESUME).
            // Kalau sudah login & punya workspace tapi BUKAN sedang di gate,
            // cek ulang status keanggotaan — handle kasus owner menolak/setujui
            // di device lain saat app di background.
            LifecycleResumeEffect(workspacePin, firebaseReady, connectGate) {
                val pin = workspacePin
                if (pin != null && firebaseReady && connectGate == null) {
                    scope.launch {
                        val status = com.startupmini.nyachat.data.remote.MembershipManager.checkMembership(pin)
                        when (status) {
                            com.startupmini.nyachat.data.remote.MembershipStatus.FAMILY_NOT_FOUND,
                            com.startupmini.nyachat.data.remote.MembershipStatus.NOT_REQUESTED -> {
                                // Workspace dihapus atau user dikick/ditolak di device lain.
                                // Reset state lokal & kembali ke layar PIN.
                                performLogoutCleanup()
                            }
                            com.startupmini.nyachat.data.remote.MembershipStatus.FAILED -> {
                                // Error jaringan — biarkan state apa adanya, coba lagi nanti.
                            }
                            else -> { /* MEMBER atau PENDING — OK, lanjut */ }
                        }
                    }
                }
                onPauseOrDispose { }
            }                // Launcher konsen OAuth Drive (muncul sekali; setelah disetujui
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
                                context.getString(
                                    if (ok) R.string.export_csv_success else R.string.export_csv_failed
                                ),
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
                    firebaseReady = true
                    secureStorage.putSecret(context, Constants.Prefs.WORKSPACE_PIN, pin)
                    appPrefs.edit()
                        .putString(Constants.Prefs.WORKSPACE_ROLE, role)
                        .putString(Constants.Prefs.USER_NAME, name)
                        .apply()
                    workspacePin = pin
                    workspaceRole = role
                    userName = name
                    viewModel.setSender(name)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    GlowingBackground()

                    // Edge-to-edge (wajib di targetSdk 36): Column induk menata konten
                    // vs NavigationBar secara vertikal. Box konten memakai weight(1f)
                    // sehingga berhenti tepat di atas navbar — tanpa offset hardcoded,
                    // dan insets navbar bawah ditangani NavigationBar sendiri (default
                    // M3). Urutan komposisi menjaga focus order F1: TopAppBar → konten
                    // → NavigationBar.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    if (!secretsLoaded) {
                        // Loading singkat sambil secret (PIN/API key) didekripsi async.
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (workspacePin == null || userName == null || !firebaseReady) {
                        // F2 (audit focus order): saat gate keanggotaan aktif (connectGate),
                        // background layar PIN ditandai invisibleToUser() supaya TalkBack &
                        // fokus keyboard tidak menjangkau elemen di balik gate.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (connectGate != null) {
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
                                    pendingPinConnect = Triple(pin, role, name)
                                } else {
                                    // Melewati gate keanggotaan dulu sebelum masuk.
                                    connectGate = Triple(pin, role, name)
                                }
                            }
                        )
                        }
                    } else {
                        // F1 (audit focus order): Scaffold M3 mengkomposisikan fokus
                        // TopBar → BottomBar → Konten (lihat urutan subcompose di
                        // Scaffold.kt), sehingga Tab melompat ke navbar SEBELUM konten.
                        // Column manual → urutan fokus TopAppBar → konten → NavigationBar.
                        Column(modifier = Modifier.fillMaxSize()) {
                            MainTopBar(
                                messages = messages,
                                userName = userName,
                                onManageMembers = { showManageMembers = true },
                                onSettings = { showSettingsSheet = true }
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
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
                                        val exit = slideOutHorizontally(
                                            targetOffsetX = { if (forward) -it / 5 else it / 5 },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        ) + fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing))
                                        enter togetherWith exit
                                    },
                                    label = "tabContent"
                                ) { tab ->
                                    when (tab) {
                                        0 -> ChatScreen(
                                            quickSuggestions = quickSuggestions,
                                            resetChatInputTrigger = chatResetTrigger,
                                            messages = messages,
                                            activeSender = activeSender,
                                            isAiThinking = isAiThinking,
                                            workspacePin = workspacePin,
                                            onSendMessage = { text, imagePath, filePath, fileName, replyToSender, replyToText ->
                                                viewModel.sendMessage(
                                                    text, imagePath, filePath, fileName, replyToSender, replyToText
                                                )
                                            },
                                            onEditMessage = { id, newText -> viewModel.editMessage(id, newText) },
                                            onAskAiClicked = { viewModel.askAiInChat(it) },
                                            onDeleteMessage = { viewModel.deleteChatMessage(it) },
                                            onOpenTransaction = { msg ->
                                                // tap badge finansial (item 5): cari transaksi terkait lalu
                                                // buka dialog edit. Cross-device: di perangkat lain, id lokal
                                                // Room berbeda sehingga fallback chatMessageId gagal. Transaksi
                                                // menyimpan sourceMessageCloudId = cloudId pesan asal → cari
                                                // transaksi dengan sourceMessageCloudId == cloudId pesan kamu.
                                                // M8: lookup via Map indeks O(1), bukan scan linear.
                                                val tx = msg.cloudId?.let { msgCloudId ->
                                                    txBySourceCloudId[msgCloudId]
                                                } ?: txByChatMessageId[msg.id]
                                                if (tx != null) {
                                                    editTarget = tx
                                                    // BUG-08: dialog dibuka dari tab Chat → reset input saat ditutup.
                                                    resetChatOnDialogClose = true
                                                    showAddDialog = true
                                                } else {
                                                    showSnack(context.getString(R.string.chat_transaction_not_found), null, null)
                                                }
                                            }
                                        )

                                        1 -> RekapScreen(
                                            transactions = transactions,
                                            totalIncome = totalIncome,
                                            totalExpense = totalExpense,
                                            isAuditLoading = isAuditLoading,
                                            onGenerateAudit = { viewModel.generateAiAuditReport() },
                                            isMonthlyLoading = isMonthlyLoading,
                                            insights = weeklyInsights,
                                            onGenerateMonthly = { viewModel.generateMonthlyAnalysis() },
                                            onAddTransactionClicked = {
                                                editTarget = null
                                                // BUG-08: dialog dari tab Rekap — jangan reset draf chat.
                                                resetChatOnDialogClose = false
                                                showAddDialog = true
                                            },
                                            onDeleteTransaction = { viewModel.deleteTransaction(it) },
                                            onEditTransaction = {
                                                editTarget = it
                                                // BUG-08: dialog dari tab Rekap — jangan reset draf chat.
                                                resetChatOnDialogClose = false
                                                showAddDialog = true
                                            },
                                            syncStatus = syncStatus
                                        )
                                    }
                                }
                            }

                            // Dialogs
                            if (showAddDialog) {
                                AddTransactionDialog(
                                    transaction = editTarget,
                                    initialLoggedBy = userName,
                                    onDismiss = {
                                        showAddDialog = false
                                        editTarget = null
                                        if (resetChatOnDialogClose) chatResetTrigger++
                                    },
                                    onConfirm = { tx ->
                                        if (editTarget != null) {
                                            viewModel.updateTransaction(tx)
                                        } else {
                                            viewModel.addManualTransaction(
                                                tx.type, tx.category, tx.amount, tx.description, tx.loggedBy
                                            )
                                        }
                                        showAddDialog = false
                                        editTarget = null
                                        if (resetChatOnDialogClose) chatResetTrigger++
                                    }
                                )
                            }

                            // Settings Bottom Sheet — di-ekstrak ke SettingsSheet.kt (P2-13)
                            if (showSettingsSheet) {
                                SettingsSheet(
                                    isDarkMode = isDarkMode,
                                    userName = userName,
                                    workspaceRole = workspaceRole,
                                    workspacePin = workspacePin,
                                    backupBusy = backupBusy,
                                    isBackupEncrypted = backupEncrypted,
                                    lastBackupMillis = lastBackupMillis,
                                    onDismiss = { showSettingsSheet = false },
                                    onToggleDarkMode = {
                                        isDarkMode = !isDarkMode
                                        appPrefs.edit().putBoolean(Constants.Prefs.IS_DARK_MODE, isDarkMode).apply()
                                    },
                                    onToggleBackupEncryption = {
                                        backupEncrypted = !backupEncrypted
                                        appPrefs.edit().putBoolean(Constants.Prefs.BACKUP_ENCRYPTED, backupEncrypted).apply()
                                    },
                                    onCheckUpdate = {
                                        showSettingsSheet = false
                                        scope.launch {
                                            val release = GitHubUpdateChecker.checkLatest()
                                            if (release != null && GitHubUpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                                                updateInfo = release
                                            } else {
                                                showSnack(context.getString(R.string.update_no_update), null, null)
                                            }
                                        }
                                    },
                                    onGeminiKey = {
                                        showSettingsSheet = false
                                        showGeminiKeyDialog = true
                                    },
                                    onOpenRouterKey = {
                                        showSettingsSheet = false
                                        showOpenRouterKeyDialog = true
                                    },
                                    onPin = {
                                        showSettingsSheet = false
                                        showPinDialog = true
                                    },
                                    onExportCsv = {
                                        showSettingsSheet = false
                                        exportCsvLauncher.launch("Nyachat-rekap-${timestampForFile()}.csv")
                                    },
                                    onBackup = {
                                        showSettingsSheet = false
                                        driveController.startBackup()
                                    },
                                    onRestore = {
                                        showSettingsSheet = false
                                        driveController.startRestore()
                                    },
                                    onClearData = {
                                        showSettingsSheet = false
                                        showConfirmClearDialog = true
                                    },
                                    onLogout = {
                                        showSettingsSheet = false
                                        showLogoutDialog = true
                                    }
                                )
                            }

                            if (showGeminiKeyDialog) {
                                ApiKeyDialog(
                                    title = stringResource(R.string.menu_gemini_key),
                                    hint = stringResource(R.string.gemini_key_hint),
                                    initialKey = geminiKey ?: "",
                                    onDismiss = { showGeminiKeyDialog = false },
                                    onSave = { newKey ->
                                        secureStorage.putSecret(context, Constants.Prefs.GEMINI_API_KEY, newKey)
                                        geminiKey = newKey
                                        showGeminiKeyDialog = false
                                    }
                                )
                            }

                            if (showOpenRouterKeyDialog) {
                                ApiKeyDialog(
                                    title = stringResource(R.string.menu_openrouter_key),
                                    hint = stringResource(R.string.openrouter_key_hint),
                                    initialKey = openRouterKey ?: "",
                                    onDismiss = { showOpenRouterKeyDialog = false },
                                    onSave = { newKey ->
                                        secureStorage.putSecret(context, Constants.Prefs.OPENROUTER_API_KEY, newKey)
                                        openRouterKey = newKey
                                        showOpenRouterKeyDialog = false
                                    }
                                )
                            }

                            if (showPinDialog) {
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
                                        showPinDialog = false
                                    },
                                    onDismiss = { showPinDialog = false }
                                )
                            }

                            if (showConfirmClearDialog) {
                                ConfirmClearDataDialog(
                                    onConfirm = {
                                        viewModel.clearAllData()
                                        showConfirmClearDialog = false
                                    },
                                    onDismiss = { showConfirmClearDialog = false }
                                )
                            }

                            if (showLogoutDialog) {
                                LogoutDialog(
                                    onKeepData = {
                                        showLogoutDialog = false
                                        performLogoutCleanup()
                                    },
                                    onDeleteData = {
                                        showLogoutDialog = false
                                        viewModel.logoutAndDeleteAllData {
                                            performLogoutCleanup()
                                        }
                                    },
                                    onDismiss = { showLogoutDialog = false }
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
                    }

                    // Gate keanggotaan: setelah PIN dimasukkan, sebelum data terbuka.
                    // Owner menyiapkan workspace; anggota kirim permintaan & menunggu
                    // persetujuan pemilik. Layar penuh menimpa semua konten lain.
                    connectGate?.let { (pin, role, name) ->
                        com.startupmini.nyachat.ui.screens.MembershipGateScreen(
                            pin = pin,
                            role = role,
                            onReady = {
                                connectGate = null
                                applyPinConnect(pin, role, name)
                            },
                            onCancel = { connectGate = null }
                        )
                    }

                    // Layar kelola anggota & permintaan bergabung (owner/member).
                    if (showManageMembers && workspacePin != null) {
                        com.startupmini.nyachat.ui.screens.ManageMembersScreen(
                            pin = workspacePin!!,
                            isOwner = (workspaceRole == Constants.Roles.OWNER),
                            onDismiss = { showManageMembers = false }
                        )
                    }

                    // Konfirmasi ganti workspace (PIN berbeda): tampil di SEMUA layar.
                    pendingPinConnect?.let { (pin, role, name) ->
                        PinSwitchDialog(
                            onConfirm = {
                                pendingPinConnect = null
                                viewModel.clearLocalData()
                                connectGate = Triple(pin, role, name)
                            },
                            onDismiss = { pendingPinConnect = null }
                        )
                    }

                    // Dialog update tampil di SEMUA layar (termasuk layar login/PIN),
                    // jadi yang belum selesai onboarding tetap dapat notif rilis baru.
                    updateInfo?.let { release ->
                        UpdateAvailableDialog(
                            release = release,
                            isDownloading = isDownloadingUpdate,
                            onAction = {
                                scope.launch {
                                    // Aksi selalu tersedia di SEMUA build. Debug → unduh &
                                    // pasang langsung (permission REQUEST_INSTALL_PACKAGES).
                                    // Release → buka halaman release GitHub di browser (ganti
                                    // APK terpasang lebih aman lewat Play Store, tapi tau
                                    // dulu ke halaman rilis agar tetap ada tombol aksi).
                                    if (BuildConfig.DEBUG) {
                                        isDownloadingUpdate = true
                                        try {
                                            val url = release.apkUrl
                                            if (url == null) throw IllegalStateException("APK tidak tersedia di release")
                                            val dest = File(context.cacheDir, "downloads/nyachat-${release.versionName}.apk")
                                            GitHubUpdateChecker.downloadApk(url, dest)
                                            installApk(context, dest)
                                        } catch (e: Exception) {
                                            updateMessage = context.getString(R.string.update_download_failed)
                                        } finally {
                                            isDownloadingUpdate = false
                                            updateInfo = null
                                        }
                                    } else {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(release.releaseUrl)
                                        )
                                        context.startActivity(intent)
                                        updateInfo = null
                                    }
                                }
                            },
                            onDismiss = { updateInfo = null }
                        )
                    }

                    updateMessage?.let { msg ->
                        UpdateMessageDialog(
                            message = msg,
                            onDismiss = { updateMessage = null }
                        )
                    }

                    // ---- Dialog Export CSV / Backup / Restore Drive ----
                    // B3: modal bisa dibatalkan — kalau Drive menggantung, user tidak
                    // terkunci; tombol Batal membatalkan operasi aktif di controller.
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

                    // Snackbar overlay (audit P1.1): tampil di semua layar. Offset
                    // adaptif: windowInsetsPadding(ime) mengangkat host di atas
                    // keyboard saat IME terbuka (navbar otomatis tersembunyi & konten
                    // melebar ke bawah); tanpa IME cukup 16dp di atas NavigationBar —
                    // menggantikan angka 96dp hardcoded.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                            .padding(bottom = 16.dp)
                    )
                        }

                // NavigationBar — anak terakhir Column induk. Hanya tampil saat
                // berada di alur app utama (sudah login & punya workspace). Di layar
                // login/PIN/gate keanggotaan navbar disembunyikan supaya onboarding
                // tetap fokus & tidak terlihat "menu chat/rekap bocor" sebelum masuk.
                val isInMainApp = secretsLoaded &&
                    workspacePin != null && userName != null && firebaseReady && connectGate == null
                val imeVisible = WindowInsets.isImeVisible
                AnimatedVisibility(
                    visible = isInMainApp && !imeVisible,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) + fadeOut(animationSpec = tween(220))
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

