# 🗺️ Peta Struktur — Nyachat

Peta pohon proyek **Nyachat** untuk navigasi cepat. Untuk panduan developer
lihat [DEVELOPER.md](./DEVELOPER.md); untuk pengguna umum lihat
[README](../README.md).

---

## 🌳 Pohon Proyek

```
nyachat/
├── 📦 ROOT PROYEK (Android Studio, Gradle Kotlin DSL)
│   ├── settings.gradle.kts / build.gradle.kts / gradle.properties   ← versi app (L11, satu sumber kebenaran)
│   ├── gradle/libs.versions.toml                                   ← version catalog
│   ├── local.properties / debug.keystore / roborazzi.properties
│   ├── firebase.json / firestore.rules / .firebaserc               ← Firebase (Firestore + Rules)
│   ├── package.json + eslint.config.mjs                            ← tooling lint untuk firestore.rules
│   ├── CHANGELOG.md · README.md · PRIVACY_POLICY.md
│   ├── .github/workflows/
│   │   ├── build-apk.yml          ← CI: build APK + unit test (Roborazzi golden)
│   │   └── deploy-functions.yml   ← CI: deploy Cloud Functions
│   ├── docs/
│   │   ├── DEVELOPER.md · STRUCTURE.md (ini) · PLAY_STORE_CHECKLIST.md · backup-encryption.md
│   ├── functions/                 ← Cloud Functions (Node 22, FCM notifikasi chat)
│   │   └── index.js
│   └── app/                       ← 📱 MODUL ANDROID (applicationId com.startupmini.nyachat)
│
├── app/src/main/java/com/startupmini/nyachat/
│   │
│   ├── MainActivity.kt            ← Titik masuk: edge-to-edge, reduced-motion, isDarkMode (pref),
│   │                                MainViewModel, startup phase, MainOverlays/Dialogs, tema
│   ├── Constants.kt               ← Konstanta global (pref keys, URL, dll.)
│   │
│   ├── 📊 data/  (lapisan data — Room + Firebase + AI + backup)
│   │   ├── local/                 ← Room DB & penyimpanan lokal
│   │   │   ├── AppDatabase.kt · ChatMessage.kt · ChatMessageDao.kt
│   │   │   ├── FinancialTransaction.kt · TransactionDao.kt
│   │   │   ├── PendingOp.kt · PendingOpDao.kt      ← antrian operasi offline
│   │   │   ├── SecureStorage.kt                    ← Keystore (PIN, API key)
│   │   │   └── AvatarStore.kt
│   │   ├── remote/                ← Firebase & layanan eksternal
│   │   │   ├── FirestoreSyncManager.kt · ChatMessageFirebaseService.kt · MembershipManager.kt
│   │   │   ├── GeminiService.kt · OfflineTransactionParser.kt (mesin heuristik,
│   │   │   │                        dipecah 2026-08-14 — GeminiService 1.635→1.111 baris)
│   │   │   ├── OpenRouterService.kt · RelayAiService.kt · FinanceAiService.kt
│   │   │   ├── BitmapCache.kt · ImageFileUtil.kt · NetworkMonitor.kt · GitHubUpdateChecker.kt
│   │   ├── backup/                ← Backup/restore terenkripsi (Google Drive + CSV)
│   │   │   ├── DriveBackupManager.kt · DriveBackupController.kt · BackupCrypto.kt
│   │   │   └── BackupResult.kt · DataExporter.kt
│   │   ├── analytics/             ← Rekap & insight
│   │   │   └── MonthlyAnalytics.kt · WeeklyInsights.kt · FinancialInsights.kt
│   │   └── repository/
│   │       └── FinanceRepository.kt                ← Satu gerbang data untuk ViewModel
│   │
│   ├── 🎨 ui/
│   │   ├── MainViewModel.kt       ← State terpusat (messages, transactions, sync, dll.)
│   │   ├── MainCallbacks.kt · MainDialogController.kt · MainAppDialogs.kt · MainOverlays.kt
│   │   │                           ← Wiring dialog/sheet/overlay dari MainActivity
│   │   ├── SyncLifecycle.kt · AiThinkingCounter.kt
│   │   ├── theme/                 ← Motion language, warna, tipografi
│   │   │   ├── Theme.kt (dark/light + SemanticColors provider)
│   │   │   ├── Color.kt · SemanticColors.kt · Motion.kt · Type.kt
│   │   ├── util/
│   │   │   └── AvatarImage.kt · DateLabels.kt · CurrencyFormat.kt ← formatter Rupiah (1 sumber)
│   │   └── screens/               ← 🖥️ SEMUA LAYAR & KOMPONEN UI (24 file)
│   │       ├── ChatScreen.kt      ← layar utama chat (bubble, viewer, input, rekap entry)
│   │       ├── ChatBubbles.kt     ← bubble pesan + gestur (tap=viewer, tahan=menu, swipe=balas)
│   │       ├── ChatInput.kt       ← composer + sheet lampiran
│   │       ├── ImageViewerDialog.kt ← viewer foto full-screen (pinch-zoom/pan/double-tap)
│   │       ├── SettingsSheet.kt   ← Pengaturan (tema, PIN, API key, backup, dll.)
│   │       ├── ProfileAccountSheet.kt · ManageMembersScreen.kt · MembershipGateScreen.kt
│   │       ├── PinConnectScreen.kt · PinAttemptLimiter.kt · MembershipGateLogic.kt
│   │       ├── RekapScreen.kt · RekapCharts.kt · RekapList.kt · RekapScreenState.kt
│   │       ├── AddTransactionDialog.kt · AiReportDialog.kt · AiReportCard.kt
│   │       ├── BackupDialogs.kt · MainDialogs.kt · StartupScreens.kt
│   │       ├── MainTopBar.kt · MainNavigationBar.kt · GlowingBackground.kt
│   │
│   ├── res/                       ← Sumber daya
│   │   ├── values/   (strings.xml 375 baris · themes.xml · colors.xml · font_certs.xml · keep.xml)
│   │   ├── values-night/ (colors.xml) · values-v31/ (themes.xml — splash 12+)
│   │   ├── drawable/  (logo, splash) · xml/ (file_paths.xml) · mipmap-*/
│   │
│   └── AndroidManifest.xml
│
├── app/src/test/                  ← 🧪 46 file test (Robolectric + Roborazzi + Compose UI)
├── app/src/androidTest/           ← 📱 AppSmokeTest (smoke di emulator NYATA — CI job device-smoke)
│   ├── ConstantsTest              ← kontrak nilai Constants.Fields (anti-regresi rename)
│   ├── data/analytics/  FinancialInsightsTest · MonthlyAnalyticsTest · WeeklyInsightsTest
│   ├── data/backup/     BackupCryptoTest · DataExporterTest · DriveBackupControllerTest · PendingOpSerializationTest
│   ├── data/local/      AppDatabaseMigrationTest
│   ├── data/remote/     BitmapCacheTest · FirestoreSyncManager* (3) · GeminiService* · RelayAiServiceTest
│   │                     · GitHubUpdateCheckerTest · ImageFileUtilSamplingTest
│   │                     · ImageFileUtilAttachmentCleanupTest (pembersihan lampiran rekursif)
│   │                     · MembershipKickLogicTest · MembershipLeaveLogicTest (keluar/anti-yatim)
│   │                     · WorkspaceOwnershipTest (aturan 1 akun = 1 workspace)
│   │                     · CloudMessageMappingTest · AiTuningAuditTest
│   │                     · MembershipAutoConnectTest (keputusan auto-connect r1.4.0)
│   │                     · MembershipAvatarSourceTest (sumber avatar: bytes/photoUrl)
│   │                     · MultiTransactionExtractionTest (multi-transaksi r1.4.0)
│   │                     · TransactionExtractionStressTest (uji ketangguhan variasi chat)
│   │                     · AiAccuracyTest (parsing respons AI: array/format lama/nominal)
│   ├── data/repository/ FinanceRepositoryTest · FinanceRepositoryBadgeTest
│   │                     · MoneyExactnessTest (invariant uang: Double eksak utk rupiah integer)
│   ├── androidTest/      AppSmokeTest (smoke di emulator NYATA — CI job device-smoke)
│   ├── ui/              AiThinkingCounterTest · MainViewModelTest (undo hapus · clear data · AI report · jalur error)
│   ├── ui/screens/      AppSnapshotTest (golden Roborazzi) · BackupDialogsTest
│   │                     · ChatBubbleGestureTest · ChatScreenGestureTest · ImageViewerDialogTest
│   │                     · AiProcessingIndicatorTest (titik memproses di bubble) · RekapScreenStateTest
│   │                     · MembershipGateLogicTest · PinAttemptLimiterTest · AmountFormatterTest
│   ├── ui/theme/        MotionReducedMotionTest
│   └── ui/util/         DateLabelsTest · AvatarImageFallbackTest (foto → inisial)
│
└── 📐 Teknologi utama
    ├── Kotlin + Jetpack Compose (Material 3, BOM) · Room (KSP) · Coroutines
    ├── Firebase: Firestore · Auth (Google Sign-In + Credential Manager) · FCM · Functions · Crashlytics
    ├── AI: Gemini API + OpenRouter (multi-provider) + relay sendiri
    └── Test: JUnit · Robolectric · Roborazzi (golden) · Compose UI test
```

---

## 📊 Statistik

| Aspek | Nilai |
|---|---|
| File Kotlin produksi | 71 |
| Baris kode (produksi + test) | ±27.900 |
| File test | 47 (46 unit + 1 androidTest) |
| Jumlah test (unit, Robolectric/Compose) | 486 |
| `minSdk` / `targetSdk` | 24 / 36 |
| `applicationId` | `com.startupmini.nyachat` |
| Bahasa UI | Indonesia (strings.xml terpusat) |

---

## 🔄 Alur Data Singkat

```
MainActivity
  ├─ MainViewModel (state terpusat)
  │    └─ FinanceRepository (satu gerbang)
  │         ├─ Room lokal (offline-first, PendingOp utk antrian offline)
  │         ├─ Firestore sync (FirestoreSyncManager — start/stop + sync ops)
  │         └─ AI (FinanceAiService → GeminiService BYOK; kaskade OpenRouter/relay)
  │
  └─ DriveBackupController (backup/restore Drive terenkripsi)
       ├─ JSON dari MainViewModel.buildBackupJson (DataExporter) — BUKAN lewat
       │  repository: backup adalah jalur terpisah yang hanya membaca state
       │  ViewModel + menulis hasil restore lewat repository.restoreBackup()
       └─ BackupCrypto (amplop AES-256-GCM) → DriveBackupManager (upload/download)
```
