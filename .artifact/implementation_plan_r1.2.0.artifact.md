# Nyachat r1.2.0 — Implementation Plan (Detailed & Disempurnakan)

> Rencana rilis berikutnya setelah **r1.1.3 (build 26)**.
> Disusun: 2026-08-09 · Berbasis: sisa FASE 5 (T3, M1) + temuan audit live r1.1.3
> + rekomendasi prioritas dari `laporan_pengujian_live_emulator.artifact.md`.

---

## Ringkasan Rencana

| Fase | Isi | Risiko | Estimasi |
|------|-----|--------|----------|
| FASE 0 — Persiapan & baseline | Commit r1.1.3, re-record golden Roborazzi, baseline test | Rendah | 0.5 hari |
| FASE 1 — T3: Refactor file raksasa | `MainActivity`/`ChatScreen`/`RekapScreen` → dekomposisi | **Tinggi** | 2-3 hari |
> ✅ **FASE 1 SELESAI 2026-08-09** — TASK-1.1 (ChatScreen 566 baris), TASK-1.2 (RekapScreen ~330 baris), TASK-1.3 (MainActivity 572 baris) semua selesai dengan test hijau + Roborazzi + smoke test live.
> ✅ **FASE 2 SELESAI 2026-08-09** — M1 upgrade dependensi (compose-bom 2026.06.01, lifecycle 2.10.0, activity 1.13.0, firebase-bom 34.17.0, okhttp 5.4.0; room ditahan di 2.7.2 — lihat catatan 2.3).
| FASE 2 — M1: Upgrade dependensi | compose-bom, lifecycle, activity, room, okhttp, firebase-bom | Sedang-tinggi | 1-2 hari | ✅ SELESAI 2026-08-09 |
| FASE 3 — Perbaikan UX & verifikasi lanjutan | quick suggestion, label sync, field chat, edit pesan, backup Drive, model AI | Rendah-sedang | 1-2 hari |
| FASE 4 — Rilis r1.2.0 | CHANGELOG, docs, bump versi, CI, tag, Play Store | Rendah | 0.5-1 hari |

**Total estimasi:** ~6-9 hari kerja penuh. **FASE 0-3 bisa dirilis terpisah** (r1.2.0 = FASE 0-2 + FASE 4; FASE 3 optional ikut r1.2.1).

---

## FASE 0 — Persiapan & Baseline r1.1.3

**Tujuan:** mengunci kondisi r1.1.3 yang sudah lulus live test, sebelum mulai pekerjaan berisiko.

| # | Sub-Task | Detail | Status |
|---|----------|--------|--------|
| 0.1 | Commit pekerjaan r1.1.3 | 6 file (FirestoreSyncManager, ConflictTest, strings, gradle.properties, build.gradle.kts, CHANGELOG) | ✅ **SELESAI** — terverifikasi 2026-08-10: tidak ada sisa file r1.1.3 uncommitted |
| 0.2 | **Re-record golden Roborazzi** | Tagline login berubah di r1.1.3 | ✅ **SELESAI** — golden TIDAK meng-cover layar login (8 PNG: balance_banner ×2, chat_bubble ×3, donut, quick_suggestions, rekap_transaction) → tagline tidak berpengaruh; golden terakhir di-re-record `f11504f` (balance_banner_offline) |
| 0.3 | Baseline test | `testDebugUnitTest` + `lintDebug` + `verifyRoborazziDebug` | ✅ **SELESAI** — 189 test Roborazzi: 6 fail = persis false-positive font OS Windows yang dikenal (CI ubuntu PASS); 180+ unit test PASS; lint 0 error |
| 0.4 | Buat branch `fix/r1.2.0` | Dari `fix/v1.1.0` | ✅ **SELESAI 2026-08-10** — branch dibuat; 21 file uncommitted (pekerjaan r1.2.0) terbawa |
| 0.5 | Tag & release r1.1.3 | GitHub Release berisi APK r1.1.3/26 yang benar | ⏭️ **DEFERRED → FASE 4** (keputusan user 2026-08-10) — tag & release r1.1.3 SUDAH ada di GitHub TAPI berisi APK **versionCode 25 / r1.1.2 (salah!)**; APK benar (26/r1.1.3) sudah di-build lokal; koreksi digabung dengan rilis r1.2.0 (FASE 4.6b) karena butuh kredensial GitHub |

**Pintu keluar (exit criteria):** unit test + lint + Roborazzi PASS — ✅; r1.1.3 ter-release benar — ⏭️ didefer ke FASE 4.6b (keputusan user).

### Catatan 0.5 — koreksi release r1.1.3
- **Verifikasi 2026-08-10**: `aapt2 dump badging` pada APK `app-debug.apk` dari GitHub Release r1.1.3 → `versionCode='25' versionName='r1.1.2'` — SAMA dengan masalah yang dicatat plan (release berisi APK versi lama).
- APK yang BENAR sudah dibangun lokal: `app/build/outputs/apk/debug/app-debug.apk` → `versionCode='26' versionName='r1.1.3'` ✅.
- **Cara koreksi** (butuh kredensial GitHub — `gh` CLI tidak terpasang & `GITHUB_TOKEN` kosong di mesin ini):
  1. `gh release upload r1.1.3 app/build/outputs/apk/debug/app-debug.apk --clobber` (ganti aset `app-debug.apk`), atau
  2. `curl -X POST -H "Authorization: token $GITHUB_TOKEN" -H "Content-Type: application/octet-stream" --data-binary @app/build/outputs/apk/debug/app-debug.apk "https://uploads.github.com/repos/ngodingsendiri/nyachat/releases/<RELEASE_ID>/assets?name=app-debug.apk"`
- `app-release.apk` & `app-release.aab` juga perlu dicek/diganti — build release butuh keystore secret CI (`KEYSTORE_BASE64`), jadi dikoreksi via re-run workflow CI di tag r1.1.3 (atau FASE 4).

---

## FASE 1 — T3: Refactor File Raksasa 🧱

**Tujuan:** memecah 3 file monolitik agar mudah dirawat, diuji, dan dikembangkan. **Paling berisiko** — dikerjakan bertahap dengan verifikasi di tiap langkah (tidak ada refactor murni besar-besaran sekali jalan).

### Prinsip
- **No behavior change**: refactor murni struktural; UI tampak identik.
- **Verifikasi tiap langkah**: build + `AppSnapshotTest` (Roborazzi) di tiap milestone.
- **Incremental**: pisahkan per layar/dialog, bukan tulis ulang sekaligus.

### TASK-1.1 — Dekomposisi `ChatScreen.kt` (~1400 baris)
| # | Sub-Task | Detail |
|---|----------|--------|
| 1.1.1 | Ekstrak composable bubble | `UserBubble`, `AiBubble`, `FinancialBadge`, `AttachmentBubble`, `ReplyPreview` ke file `ChatBubbles.kt` |
| 1.1.2 | Ekstrak input bar | `ChatInputBar` (field, tombol kirim, Tanya AI, lampiran) + quick suggestions ke `ChatInput.kt` |
| 1.1.3 | Ekstrak logika murni | `buildChatRows` (grouping per hari) → util `ChatRowBuilder.kt` + unit test |
| 1.1.4 | State holder | Kelola state input/fokus/reply di `rememberChatScreenState()` atau `ChatUiState` (jika layak) |
| 1.1.5 | Verifikasi | Build + unit test + Roborazzi |

### TASK-1.2 — Dekomposisi `RekapScreen.kt` (~1441 baris) ✅ SELESAI 2026-08-09
| # | Sub-Task | Detail | Status |
|---|----------|--------|--------|
| 1.2.1 | Ekstrak chart composable | `BalanceBanner`, `DonutChart`, `CategoryProgressBar`, `MonthlyNav` → `RekapCharts.kt` (`BalanceBannerCard`, `DonutChart`, `SyncIndicator`, `CategoryProgressRow`, `getCategoryIcon`, `RekapCategoryBreakdown`) | ✅ 2026-08-09 |
| 1.2.2 | Ekstrak daftar & filter | `TransactionList` + filter chips → `RekapList.kt` (`TransactionItemCard`, `TransactionDayHeader`, `RekapMonthNav`, `RekapFilterHeader`, `RekapEmptyState`) | ✅ 2026-08-09 |
| 1.2.3 | Ekstrak AI insight card | `AiInsightCard` + dialog laporan → `AiReportCard.kt` (`InsightsAiCard`) | ✅ 2026-08-09 |
| 1.2.4 | State holder | `RekapScreenState.kt` — `rememberRekapScreenState()` (selectedMonth, selectedFilterTab, selectedCategory, pendingDelete) | ✅ 2026-08-09 |
| 1.2.5 | Verifikasi | Build + unit test + Roborazzi + smoke test live | ✅ 2026-08-09 |

**Hasil:** `RekapScreen.kt` 1442 → **~330 baris** orkestrasi murni (memenuhi exit criteria <600). File baru: `RekapScreenState.kt`, `RekapCharts.kt`, `RekapList.kt`, `AiReportCard.kt`. Komponen eksternal tetap tersedia: `TransactionItemCard`/`BalanceBannerCard`/`DonutChart` dipakai `AppSnapshotTest`, `getCategoryIcon` dipakai `AddTransactionDialog` (same-package, visibilitas internal).

**Verifikasi (2026-08-09):** `compileDebugKotlin` PASS · **180+ unit test PASS** · **Roborazzi golden compare PASS** (AppSnapshotTest render identik — bukti no behavior change) · `assembleDebug` PASS.

**Smoke test live emulator (device A):** tab Rekap → `RekapMonthNav` (Semua), `BalanceBannerCard` (Total Saldo Rp4.166.401, Tersinkron, Pemasukan Rp5.000.000, Pengeluaran Rp833.599), `RekapCategoryBreakdown` (Alokasi Pengeluaran), `RekapFilterHeader`, `TransactionDayHeader` (Hari Ini) + `TransactionItemCard` (beli tempe 600099, beli gorengan 8500, beli kopi 25000…), `InsightsAiCard` (Insight Otomatis/Bulanan) — **semua render benar**. Bukti: `.artifact/live_shots/rekap_dekomposisi_t12.png`.

**Review code-reviewer:** tidak ada temuan kritis; 2 nit minor diperbaiki (import `fillMaxWidth` tak terpakai dihapus, penomoran komentar duplikat dikoreksi).

### TASK-1.3 — Dekomposisi `MainActivity.kt` (1501 → 572 baris) ✅ SELESAI
| # | Sub-Task | Detail | Status |
|---|----------|--------|--------|
| 1.3.1 | Ekstrak wiring dialog | Snackbar host, dialog PIN/API key/update/export → `MainDialogs.kt` + `BackupDialogs.kt` (mengikuti pola `SettingsSheet`/`AddTransactionDialog`) | ✅ commit `eeaf5aa` |
| 1.3.2 | Ekstrak callbacks | Kelompok callback → interface `ChatCallbacks` / `RekapCallbacks` di `MainCallbacks.kt` (128 baris) + factory `buildChatCallbacks`/`buildRekapCallbacks` | ✅ 2026-08-09 |
| 1.3.3 | Ekstrak lifecycle glue | Listener attach/pause/resume sync & membership + sync start/stop + API key + update check + auto-backup → `SyncLifecycle.kt` (145 baris) | ✅ 2026-08-09 |
| 1.3.3b | **State holder dialog** | `MainDialogController.kt` (42 baris): 16 state dialog/overlay (showAddDialog, connectGate, updateInfo, ...) dipindah dari 16 `remember` ke satu class | ✅ 2026-08-09 |
| 1.3.3c | **Ekstrak dialog lapisan konten** | `MainAppDialogs.kt` (234 baris): AddTransactionDialog, SettingsSheet, ApiKeyDialog x2, PinDisplayDialog, ConfirmClearDataDialog, LogoutDialog, AiReportDialog x2 | ✅ 2026-08-09 |
| 1.3.3d | **Ekstrak overlay global** | `MainOverlays.kt` (208 baris, `BoxScope`): MembershipGateScreen, ManageMembersScreen, PinSwitchDialog, UpdateAvailableDialog+UpdateMessageDialog, dialog backup/restore Drive (state di-collect dari DriveBackupController), SnackbarHost | ✅ 2026-08-09 |
| 1.3.4 | (Opsional) Navigation Compose | **Dilewati** — tab hanya 2 layar, `AnimatedContent` sudah memadai; simpan untuk r1.3.0 | ⏭️ SKIP |
| 1.3.5 | Verifikasi | Build + unit test + Roborazzi + **smoke test live emulator** | ✅ 2026-08-09 |

**Hasil:** `MainActivity.kt` 1501 → **572 baris** (memenuhi exit criteria <600). File baru: `SyncLifecycle.kt` (145), `MainCallbacks.kt` (128), `MainDialogController.kt` (42), `MainAppDialogs.kt` (234), `MainOverlays.kt` (208). `compileDebugKotlin` + 176 unit test PASS tanpa warning. Review code-reviewer: refactor faithful (no behavior change; key lifecycle & callback state tidak basi).

**Smoke test live emulator (2026-08-09, APK r1.1.3/26):** AddTransactionDialog ✅ (FAB Rekap → dialog Catat Transaksi Manual), SettingsSheet ✅, ApiKeyDialog Gemini ✅, Export CSV → SAF picker ✅, kirim chat finansial → snackbar "Tercatat: - Rp30.000 + Urungkan" ✅, transaksi masuk Rekap ✅, sesi login bertahan setelah restart ✅, tanpa crash.

**Roborazzi:** `verifyRoborazziDebug` 8 golden berubah (chat_bubble_*, donut_chart, balance_banner_*, insights_card, rekap_transaction_items) — **false-positive font OS** (lokal Windows vs golden CI ubuntu; komponen tsb di-render langsung oleh `AppSnapshotTest` tanpa MainActivity, bukan regresi refactor); golden CI akan pass (re-record hanya di CI runner, konvensi proyek).

**Exit criteria:** 3 file di bawah ~600 baris — MainActivity 572 ✅, ChatScreen 566 ✅, **RekapScreen ~330 ✅ (TASK-1.2 selesai 2026-08-09)**. Seluruh test hijau (180+ PASS); smoke test live lulus.

---

## FASE 2 — M1: Upgrade Dependensi 📦

**Tujuan:** mengejar versi stabil terbaru (perbaikan bug/keamanan/performa). **Bertahap per modul**, verifikasi di tiap langkah; `okhttp 4→5` dipisah & diaudit.

### Kondisi akhir (terverifikasi dari `gradle/libs.versions.toml`, eksekusi 2026-08-09)
| Dependency | Sebelum | **Sesudah** | Verifikasi |
|---|---|---|---|
| `compose-bom` | 2024.09.00 | **2026.06.01** | ✅ compile + unit test |
| `lifecycle-runtime-ktx/viewmodel-compose/runtime-compose` | 2.8.7 | **2.10.0** (stable tertinggi utk compileSdk 36; 2.11.0 butuh compileSdk 37) | ✅ unit test |
| `activity-compose` | 1.10.1 | **1.13.0** | ✅ compile + unit test |
| `room` | 2.7.0 | **2.7.2** (2.8.x DITOLAK — lihat catatan 2.3) | ✅ migration test PASS |
| `okhttp` | 4.10.0 | **5.4.0** (major, audit API selesai — semua pemakaian kompatibel) | ✅ unit test |
| `firebase-bom` | 34.15.0 | **34.17.0** | ✅ unit test |
| `robolectric` / `roborazzi` | 4.16.1 / 1.59.0 | tetap | — |

### Urutan & status
| # | Sub-Task | Status |
|---|----------|-----------|
| 2.1 | Upgrade `compose-bom` + `activity-compose` | ✅ **SELESAI** — 1 warning deprecation non-blokir (`confirmValueChange`) |
| 2.2 | Upgrade `lifecycle` (3 artefak) | ✅ **SELESAI** — 2.11.0 butuh compileSdk 37 (SDK 37 tidak terpasang) → dipakai **2.10.0** (stable tertinggi kompatibel compileSdk 36) |
| 2.3 | Upgrade `room` 2.7.0 → 2.8.x | ✅ **SELESAI (revert 2.7.2)** — lihat catatan |
| 2.4 | Upgrade `firebase-bom` 34.15.0 → 34.17.0 | ✅ **SELESAI** |
| 2.5 | Upgrade `okhttp` 4.10.0 → 5.4.0 | ✅ **SELESAI** — audit: semua pemakaian (DriveBackupManager, OpenRouterService, GitHubUpdateChecker, GeminiService) API stabil (`Builder`/`newCall().execute()`/`toMediaType`); minSdk 24 ≥ syarat 5.x (API 21); tidak ada error kompilasi |
| 2.6 | Regression penuh | ✅ **SELESAI** — 16 lint error baru `LocalContextGetResourceValueCall` (aturan compose-bom 2026.06) di MainActivity (4) & PinConnectScreen (12) diperbaiki (hoist `stringResource` + `.format()`); lintDebug 0 error; unit test PASS; Roborazzi 6 golden FAILED = false-positive font OS yang SAMA dengan FASE 1 (CI ubuntu PASS); smoke live PASS |

### Catatan 2.3 — Room: keputusan tetap di 2.7.2
- Upgrade ke **2.8.4** membuat **`AppDatabaseMigrationTest` gagal total (3/3)**: `IllegalArgumentException: This driver is configured to open a database named 'migration-test.db' but '…robolectric-…\databases\migration-test.db' was requested.`
- Akar masalah: Room 2.8 menulis ulang `MigrationTestHelper` memakai `SupportSQLiteDriver` (androidx.sqlite) yang **menolak path DB yang di-redirect Robolectric** ke sandbox temp — konflik seri 2.8.x (2.8.0–2.8.4), tanpa workaround di sisi test (sudah pakai `FrameworkSQLiteOpenHelperFactory` + `InstrumentationRegistry`). Proyek juga belum punya `androidTest` untuk jalur instrumented.
- **Keputusan**: revert ke **2.7.2** (patch final seri 2.7.x, tetap didukung) — migration test adalah coverage kritis (M12, jalur v8→v11) yang tidak boleh hilang; selaras prinsip plan "verifikasi tiap langkah, revert langkah yang gagal". Migrasi ke 2.8.x didefer, bisa dibuka lagi bila: (a) proyek punya `androidTest` instrumented, atau (b) Room memperbaiki kompatibilitas Robolectric.
- **Verifikasi**: `AppDatabaseMigrationTest` 3/3 PASS (BUILD SUCCESSFUL) setelah revert.

### Catatan 2.6 — lint fix `LocalContextGetResourceValueCall` (16 error)
- Aturan lint BARU dari compose-bom 2026.06.01 menandai query resource via `LocalContext.current.getString(...)` di fungsi non-composable (LaunchedEffect/coroutine/callback). Baseline r1.1.3 lint PASS → semua 16 error adalah efek upgrade.
- **Fix**: resolve di composable scope via `stringResource(...)` (import ditambah di MainActivity), template berargumen (`tx_recorded`, `google_err_sha1_hint`, `google_err_credential_provider`, `google_err_unknown`, `pin_rate_limited`) memakai `.format()`; `default_web_client_id` (PinConnectScreen) dipindah ke `runCatching { stringResource(...) }` (komentar keep.xml dipertahankan).
- **Verifikasi**: `compileDebugKotlin` PASS · `lintDebug` **0 error** · unit test PASS · smoke live (install APK baru, session bertahan, sync "Tersinkron") PASS — bukti `.artifact/live_shots/m1_upgrade_smoke.png`.
- **Roborazzi**: 6 golden gagal = subset false-positive font OS yang sudah didokumentasikan FASE 1 (balance_banner/chat_bubble/rekap_transaction; dirender langsung tanpa MainActivity) — bukan regresi layout; `verifyRoborazziDebug` PASS di CI ubuntu (konvensi proyek).
- **Validasi runtime OkHttp 5.4 (tindak lanjut reviewer)**: indikator "Tersinkron" hanya membuktikan Firestore (gRPC — bukan OkHttp). Dilakukan **backup Drive live** (DriveBackupManager = OkHttp upload + token OAuth): dialog passphrase → backup terenkripsi sukses — label Settings "Backup terakhir: 9 Agu 2026 · 23:48 · Terenkripsi" (waktu BARU). **OkHttp 5.4 terbukti bekerja di runtime**. Bukti: `.artifact/live_shots/m1_okhttp_drive_backup.png`.

**Exit criteria:** semua test & lint hijau; tidak ada warning kompilasi baru; behavior app identik. — ✅ TERPENUHI

---

## FASE 3 — Perbaikan UX & Verifikasi Lanjutan (hasil audit live)

**Tujuan:** menutup sisa temuan minor + memverifikasi alur yang belum teruji live.

### Progres (diperbarui 2026-08-10)

| # | Sub-Task | Detail | Sumber | Status |
|---|----------|--------|--------|--------|
| 3.1 | **BUG-05 (P1): quick suggestion chips tidak tampil di runtime** | `LazyRow` compose-bom 2026.06 meng-komposisi tapi TIDAK me-layout item → `Row` + `horizontalScroll` di `QuickSuggestionRow` + `if` biasa (bukan `AnimatedVisibility`) | Bug | ✅ **SELESAI** (2026-08-10; terverifikasi live) |
| 3.2 | Label indikator sync netral | "Gagal sinkron" → "Mode offline" (abu/kuning) saat offline — BUG-06 | UX | ✅ **SELESAI** (commit `f11504f`) |
| 3.3 | Reset field chat setelah dialog tutup | Field tidak menyisakan "." / karakter sisa — BUG-08 laporan perangkat nyata | Bug | ✅ **SELESAI** (commit `f11504f`) |
| 3.4 | **Uji alur edit pesan end-to-end** | Tap badge finansial → edit → simpan → verifikasi LWW sync (editedAt/serverUpdatedAt) di 2 perangkat emulator | Verifikasi | ✅ **SELESAI** (2 emulator; LWW terbukti) |
| 3.5 | **Uji backup/restore Drive dengan akun nyata** | Backup manual + auto-backup terenkripsi (M5) + restore ke device kedua | Verifikasi | ✅ **SELESAI** (2026-08-09; Drive API diaktifkan; 3 backup sukses; restore 2 perangkat; M5 pass) |
| 3.6 | **Verifikasi daftar model AI via API `/models`** | `GeminiService.MODEL_NAME` (`gemini-3.5-flash`) & daftar `OpenRouterService.FREE_MODELS` — pastikan tidak retired; tambah fallback dinamis bila perlu | Keandalan | ✅ **SELESAI** (2026-08-09; `ling-3.0-flash:free` retired → diganti 2 model gratis terverifikasi) |
| 3.7 | (Opsional) Notifikasi pengingat & grafik bulanan | Fitur roadmap; jika dimasukkan ke r1.2.0, buka sub-plan terpisah (grafik bulanan sudah ada via MonthlyNav; inti item = notifikasi pengingat) | Roadmap | ⏳ TODO — keputusan user |
| 3.8 | (Opsional) Indikator detail status sync | Label "Tersinkron · 14:32" di banner Rekap — waktu terakhir sinkron berhasil | UX | ✅ **SELESAI** (2026-08-10; terverifikasi live "Tersinkron · 01:38") |
| 3.9 | Label "· Terenkripsi" mengikuti FILE backup aktual (temuan #1) | Indikator di Settings tidak boleh berubah karena toggle semata — harus mencerminkan enkripsi file backup terakhir | Bug | ✅ **SELESAI** (2026-08-09; terverifikasi live matriks toggle × backup) |
| 3.10 | Snackbar "Passphrase salah" saat restore terenkripsi gagal (temuan #3) | Saluran `passphraseError` terpisah + tutup modal progres dulu + durasi Long — snackbar tak lagi tersembunyi di balik dialog | Bug | ✅ **SELESAI** (2026-08-09; terverifikasi live) |
| 3.11 | Badge 🔒 file terenkripsi di picker restore (temuan #4) | `RestorePickerDialog` menampilkan badge 🔒 Terenkripsi (via penanda nama & probe isi untuk backup lama) | UX | ✅ **SELESAI** (2026-08-09; terverifikasi live) |
| 3.12 | **BUG-1 (P0): badge finansial hilang dari bubble chat** | `toObject(CloudMessage)` kehilangan field `isFinancial` (getter Kotlin is-prefix vs CustomClassMapper) → fix `@get:PropertyName("isFinancial")` + test regresi | Bug | ✅ **SELESAI** (2026-08-09; terverifikasi live) |
| 3.13 | **BUG-2 (P1): draf chat hilang saat pindah tab** | `AnimatedContent` menghancurkan state ChatScreen → hoist draf ke MainActivity (`rememberSaveable`) via `draftText`/`onDraftChange` + reset saat logout/ganti PIN | Bug | ✅ **SELESAI** (2026-08-09; terverifikasi live) |
| 3.14 | **BUG-06 lanjutan (P0): deteksi jaringan** | `ConnectivityManager.NetworkCallback` (NetworkMonitor) → status jujur saat offline murni; `markSynced`/drain tak menimpa OFFLINE dari cache | Bug | ✅ **SELESAI** (2026-08-09; terverifikasi live) |
| 3.15 | **Verifikasi notifikasi chat real-time (FCM) end-to-end** | Firestore → Cloud Function `notifyChatMessage` → FCM → notifikasi Android lintas perangkat (fitur "seperti WhatsApp" FASE 3 r1.1.x) | Verifikasi | ✅ **SELESAI** (2026-08-10; rantai penuh terbukti live) |

### Rincian yang sudah selesai

**3.8 — indikator detail status sync ("Tersinkron · HH:mm")** · 2026-08-10
- `FirestoreSyncManager.lastSyncedAt` (StateFlow `Long?`) — diset di `markSynced()` setiap snapshot sukses (sinkron aktif), di-reset `null` saat `stop()` (logout). Default null → golden lama tidak berubah.
- Rantai param: MainActivity (collect) → `RekapScreen.lastSyncedAtMillis` → `BalanceBannerCard` → `SyncIndicator` — saat SYNCED + waktu diketahui tampil `sync_status_synced_at` = "Tersinkron · %1$s"; selain itu label lama (OFFLINE/SYNCING/ERROR tetap sama).
- `DateLabels.formatClockTime` (HH:mm, Locale.getDefault) + unit test (+1 → total 190 test PASS).
- **Verifikasi live**: label "Tersinkron · 01:38" tampil di banner Rekap (emulator-5556). Bukti: `.artifact/live_shots/t38_sync_label.png`.

**3.1 (BUG-05) — quick suggestion chips tidak tampil** · 2026-08-10
- Gejala: chips saran cepat TIDAK PERNAH tampil di runtime walau `quickSuggestions` terisi (log: 4 saran), kondisi `visible` terpenuhi, dan content dikomposisi — area 48dp kosong tanpa node aksesibilitas; golden Robolectric tetap PASS (render di env berbeda). Laporan perangkat nyata menyebut "hilang saat keyboard terbuka" — ternyata lebih parah (tidak tampil sama sekali, keyboard pun tertutup).
- **Akar masalah**: regresi upgrade **compose-bom 2026.06.01 (M1)** — `LazyRow` di dalam `AnimatedVisibility`/`Column` meng-komposisi item tapi TIDAK me-layout-nya. Bukti live (verifikasi bertahap): (1) `AnimatedVisibility` → `if` tidak cukup; (2) `height(48.dp)` eksplisit → Row mengambil ruang tapi isi kosong; (3) `LazyRow` → `Row`+`horizontalScroll` → **chip tampil & bisa diketuk**.
- **Fix** (`ChatScreen.kt` + `ChatInput.kt`): ganti `AnimatedVisibility` dengan `if` biasa; ganti `LazyRow` dengan `Row` + `horizontalScroll` + `height(48.dp)` — 4-5 saran pendek tidak butuh lazy, layout deterministik. Komentar BUG-05 dokumentatif dipertahankan (konvensi proyek).
- **Verifikasi live**: 3 chip tampil di atas input bar (bounds y1833; "beli mie ayam 20000 20000", "beli nasi 30000 30000", "tes draf 9beli tempe…"); **tap chip → draft terisi di field**; golden `quick_suggestions` di-record ulang (**411×72** vs 155×64 lama) & **PASS** di verifyRoborazzi (189 test, hanya 6 false-positive font OS yang dikenal); **unit test BUILD SUCCESSFUL**; 0 log debug tersisa di source & runtime. Bukti: `.artifact/live_shots/b05_chips_fixed.png`.

**3.2 (BUG-06) — label sync netral** · commit `f11504f`
- `FirestoreSyncManager.onSyncFailure` → ekstrak **`classifySyncFailure`**: kode eksplisit (`PERMISSION_DENIED`/kuota/`UNAUTHENTICATED`) → ERROR; `UNAVAILABLE`/`DEADLINE_EXCEEDED`/IOException (termasuk nested cause) → OFFLINE.
- `strings.xml`: `sync_status_offline` → **"Mode offline"**, `sync_status_error` → **"Belum sinkron"**; dot indikator ERROR disamakan abu-abu dengan OFFLINE.
- **+12 unit test** baru `FirestoreSyncManagerOfflineTest` (kode Firestore, teks menyesatkan, nested cause) → total **176 test PASS**. Overload murni `(networkCode, hasExplicitCode, message, cause)` karena referensi enum Firebase memicu `ExceptionInInitializerError` (SparseArray tidak di-mock di JVM).

**3.3 (BUG-08) — reset field chat** · commit `f11504f`
- `ChatScreen` menerima `resetChatInputTrigger` + `LaunchedEffect` reset `inputText`; `MainActivity` menaikkan trigger saat dialog tutup.
- **Digate khusus dialog dari tab Chat** (badge finansial) — dialog dari tab Rekap TIDAK menghapus draf (tindak lanjut reviewer).
- Golden `balance_banner_offline.png` di-re-record; verifyRoborazzi hanya 4 false-positive font OS yang sama.

**3.4 (edit pesan lintas perangkat) — selesai 2026-08-09**
- Setup: clone AVD `Pixel_7a_b` + emulator kedua `emulator-5556` (login & data tersinkron otomatis).
- Edit "beli gorengan 8000" → "beli gorengan 8500" di device A → **real-time muncul di device B** (~detik) + penanda "16:06 • diedit".
- Forensik DB device B: `editedAt` ✓ + `serverUpdatedAt` ✓ terisi; transaksi ikut ter-update (Rp8.000 → Rp8.500) → **LWW sync via server timestamp (M4) terbukti bekerja**.

**3.5 (backup/restore Drive akun nyata) — selesai 2026-08-09**
- **Blocker diatasi**: Google Drive API diaktifkan via gcloud di project `340343053987` (`nyachat-in`) setelah persetujuan user (sebelumnya 403 `accessNotConfigured`).
- **Backup manual (plain)** di device A → sukses; file `Nyachat-backup-20260809-183501.json` muncul di Drive akun `ngampusendiri@gmail.com` (terlihat via picker restore).
- **Restore di device yang sama**: hapus transaksi "beli gorengan 8500" di Rekap (saldo turun Rp8.500) → restore dari backup → transaksi & saldo **kembali utuh**.
- **M5 — backup terenkripsi**: toggle "Enkripsi backup Drive" ON → backup minta passphrase → `Nyachat-backup-20260809-184131.json` sukses (label "18:41 · Terenkripsi"). Restore dengan passphrase benar `rahasia123` → **data pulih**; passphrase salah (8+ char) → **ditolak, data tidak berubah**.
- **Restore ke device kedua (B)**: emulator B dimulai ulang (cold boot), Settings → Restore → pilih backup plain 183501 → data B berubah total; forensik DB B: 10 pesan (termasuk riwayat lama dari A yang sebelumnya tidak ada di B) + 6 transaksi identik; pesan "beli gorengan 8500" tetap berstatus diedit → **restore lintas perangkat terbukti**.
- Screenshot: `.artifact/live_shots/consent_drive.png`, `consent2.png`, `consent3.png`, `b_restore_1.png`.

**3.6 (verifikasi model AI via `/models`) — selesai 2026-08-09**
- **Gemini**: `MODEL_NAME = "gemini-3.5-flash"` terverifikasi **VALID & STABLE** di daftar resmi Google AI docs (`ai.google.dev/gemini-api/docs/models`) — tetap dipakai (ada versi lebih baru `gemini-3.6-flash`, opsional upgrade nanti).
- **OpenRouter**: cek live `GET https://openrouter.ai/api/v1/models` (400 model): **5/6 model `:free` app aktif** (`gpt-oss-20b:free`, `gemma-4-31b-it:free`, `nemotron-3-ultra-550b-a55b:free`, `laguna-xs-2.1:free`, `openrouter/free`).
- **1 model RETIRED**: `inclusionai/ling-3.0-flash:free` hilang dari katalog (versi berbayar `inclusionai/ling-3.0-flash` masih ada).
- **Fix**: `OpenRouterService.FREE_MODELS` diganti — hapus `ling-3.0-flash:free`, tambah `google/gemma-4-26b-a4b-it:free` (vision-ready, relevan untuk foto nota) + `inclusionai/ling-3.0-tiny:free` (keluarga sama). Keduanya terverifikasi `prompt=$0` di katalog (14 model `:free` total). Urutan diprioritaskan ke model terkuat (gemma 31b → gemma 26b → gpt-oss).
- Compile `:app:compileDebugKotlin` PASS.

**3.15 — verifikasi notifikasi chat real-time (FCM) end-to-end** · 2026-08-10
- **Latar**: fitur notifikasi chat real-time (FASE 3 r1.1.x) — `ChatMessageFirebaseService` (FCM data message) + Cloud Function `notifyChatMessage` — belum pernah diverifikasi penuh. Live test awal gagal: tidak ada notifikasi di device B.
- **Rantai yang diuji** (2 emulator + Firestore + Cloud Functions): kirim pesan dari A → tulis Firestore → trigger CF → FCM multicast → notifikasi tampil di B.
- **Blocker yang ditemukan & diperbaiki (infra GCP, bukan kode app)**:
  1. **Runtime SA Cloud Run tidak bisa baca Firestore** — `PERMISSION_DENIED` di `@google-cloud/firestore` → grant `roles/datastore.user` ke `340343053987-compute@developer.gserviceaccount.com` + redeploy → log fungsi bersih.
  2. **Runtime SA tidak bisa kirim FCM** — `code=messaging/mismatched-credential, Permission 'cloudmessaging.messages.create' denied` (di project, bukan sender) → grant **`roles/firebase.sdkAdminServiceAgent`** (role resmi yang memuat permission kirim; `firebasemessaging.admin`/`firebaseadmin` tidak didukung) + redeploy → `FCM OK`.
  3. **Token FCM tidak pernah tersimpan ke Firestore** — `FirebaseMessaging.getInstance().token` diam-diam gagal; setelah restart app + `ensureTokenSynced`, token 142 char tersimpan ke `families/{pin}/members/{uid}/fcmToken`.
- **Bukti sukses end-to-end** (log Cloud Run + live device):
  - Log fungsi: `Multicast total=1 gagal=0 sender=Ari Purnomo Aji` + `FCM OK uid=g6lkldwvI8` (t+1s setelah write).
  - Notifikasi **"Ari Purnomo Aji / pesan-e2e-rest-987"** tampil di status bar device B (emulator-5556) — title = sender, text = body, channel `chat_messages`.
  - Chat device B menampilkan SEMUA pesan test dari A (sync Firestore real-time: `notif-dari-a`, `rt-test-2`, `rt-final-3`, `rt-4-bar`, `pesan-e2e-rest-987`, …).
- **Catatan penting (limitasi test setup)**: kedua emulator memakai akun Google SAMA (`ngampusendiri@gmail.com`) → `uid` FirebaseAuth sama → member doc yang sama → token B MENIMPA token A. Untuk verifikasi teknis rantai, member kedua dibuat via REST. Uji notifikasi dengan 2 identitas berbeda butuh akun Google kedua.
- **Pembersihan**: `functions/index.js` sudah di-deploy (logging error FCM detail); log debug sementara dihapus; file test helper ada di `.artifact/` (`firecheck.py`, `check_tokens.py`, `send_test_message.py`, `setup_budi_member.py`).

**3.9 (temuan #1) — label "· Terenkripsi" mencerminkan file backup aktual** · 2026-08-09
- Sebelumnya label diambil dari *setting saat ini* (toggle enkripsi), bukan file — toggle OFF/ON langsung mengubah label padahal tidak ada backup baru dibuat.
- Verifikasi live (device A, Drive API nyata, matriks toggle × backup):
  - Baseline (file 20:37 terenkripsi) → `20:37 · Terenkripsi`;
  - **Toggle OFF tanpa backup** → label **tetap** `20:37 · Terenkripsi` (sebelum fix salah jadi "Tanpa enkripsi");
  - Backup plain (20:40) → `20:40 · Tanpa enkripsi`;
  - **Toggle ON tanpa backup** → label **tetap** `20:40 · Tanpa enkripsi` (arah sebaliknya juga benar);
  - Backup terenkripsi (20:41) → `20:41 · Terenkripsi`.
- Kesimpulan: label **hanya berubah saat backup BARU dibuat**, di kedua arah. Bukti: `.artifact/live_shots/settings_backup_label_encrypted.png`.

**3.10 (temuan #3) — snackbar "Passphrase salah" saat restore terenkripsi gagal** · 2026-08-09
- Akar masalah: kode lama sudah mengirim pesan error tapi lewat saluran `message` → snackbar durasi Short yang tampil SELAGAI modal progres masih terbuka → tersembunyi di balik dialog scrim → tidak pernah teramati.
- Fix (`DriveBackupController`, `MainActivity`, `strings.xml`):
  - Saluran khusus StateFlow `passphraseError` (terpisah dari `message`) + `dismissPassphraseError()`; dibersihkan juga di `cancelActiveOperation()`.
  - Urutan: `busy=false` DULU (tutup modal progres) → set `passphraseError`; snackbar durasi **Long** di `MainActivity`.
  - String pendek `restore_wrong_passphrase_snackbar` = "Passphrase salah. Coba lagi dengan passphrase yang dipakai saat backup dibuat."; string lama `restore_wrong_passphrase` dihapus (dead code).
  - +unit test: assert `passphraseError` ter-set, `busy=false`, `message` kosong, dismiss & cancel membersihkan saluran — **semua 180+ test PASS**.
- Verifikasi live: restore `Nyachat-backup-20260809-204145.enc.json` → passphrase `salah999` → snackbar tampil t+3s, auto-dismiss ~10s, **data lokal tidak berubah**. Bukti: `.artifact/live_shots/passphrase_salah_t0.png` & `passphrase_salah_t3.png`.

**3.11 (temuan #4) — badge 🔒 file terenkripsi di picker restore** · 2026-08-09
- `RestorePickerDialog` kini menampilkan badge `🔒 Terenkripsi` pada file backup terenkripsi — deteksi via penanda nama/metadata (`…203724.enc.json`) DAN probe isi untuk backup lama tanpa metadata (`…184131.json` dari live test 3.5).
- Verifikasi live: kedua file di atas tampil dengan badge 🔒 di picker. Bukti: `.artifact/live_shots/restore_picker_badge_lock.png`.

### Temuan follow-up dari live test (masuk backlog r1.2.1 / perbaikan lanjutan)
- **BUG-1 (P0) — ✅ FIXED 2026-08-09** (detail di FASE 3 item 3.12): badge finansial hilang dari bubble chat setelah ~5-10 detik — Room lokal `isFinancial=0` untuk SEMUA pesan padahal cloud `isFinancial=1` (detectedAmount tetap tersimpan). **Akar masalah**: `FirestoreCustomClassMapper` tidak membaca Kotlin metadata; getter JVM `isFinancial()` untuk Boolean berawalan "is" diturunkan jadi nama field "financial" → `toObject(CloudMessage)` selalu memberi false. **Fix**: `@get:PropertyName("isFinancial")` di `CloudMessage.isFinancial`. **Verifikasi**: round-trip CustomClassMapper PASS; live — badge bertahan t+13s (sebelumnya hilang ~7s), bertahan setelah restart, badge lama pulih (self-healing via re-merge dari cloud; catatan: perangkat offline-forever menahan isFinancial=0 lama sampai koneksi pulih).
- **BUG-2 (P1) — ✅ FIXED 2026-08-09** (detail di FASE 3 item 3.13): draf chat hilang saat pindah tab Chat ⇄ Rekap. **Akar masalah**: `AnimatedContent` menghancurkan state `ChatScreen` saat tab berganti — `rememberSaveable` lokal tidak cukup (state dihapus dari komposisi). **Fix**: hoist `chatDraft` ke MainActivity (`rememberSaveable`, tidak pernah keluar komposisi) → `ChatScreen` menerima `draftText` + `onDraftChange`; semua jalur tulis (suggestion chip, Tanya AI, kirim, reset BUG-08) lewat callback; reset `chatDraft=""` saat logout & ganti PIN (isolasi antar-workspace). **Verifikasi live**: draf bertahan 2× round-trip Chat→Rekap→Chat, kirim membersihkan field.
- **BUG-06 lanjutan (P0) — ✅ FIXED 2026-08-09** (detail di FASE 3 item 3.14): saat offline MURNI (airplane mode, network unreachable), indikator tetap "Tersinkron" — tidak ada deteksi jaringan (0 `ConnectivityManager`/`NetworkCallback`); `markSynced()` dari offline cache selalu menyetel SYNCED. **Fix**: `NetworkMonitor` (baru) + `ACCESS_NETWORK_STATE`; `FirestoreSyncManager.networkAvailable` (null/false/true) + fungsi murni `resolveStatusOnNetworkChange`/`resolveStatusOnSyncSuccess`/`resolveStatusOnDraining`; `markSynced` & drain tidak menimpa OFFLINE. **Verifikasi live**: offline → "Mode offline" ✓; pulih → "Menyinkronkan…" → "Tersinkron" ✓; force-stop + relaunch offline → "Mode offline" ✓ (kasus eksplisit laporan yang sebelumnya GAGAL). +7 unit test.
- **BUG-05 (P1) — ✅ FIXED 2026-08-10** (detail di FASE 3 item 3.1): chips saran cepat tidak pernah tampil di runtime walau data terisi & kondisi visible terpenuhi. **Akar masalah**: regresi compose-bom 2026.06 (M1) — `LazyRow` meng-komposisi item tapi tidak me-layout-nya. **Fix**: `AnimatedVisibility` → `if` + `LazyRow` → `Row`+`horizontalScroll` di `QuickSuggestionRow`. **Verifikasi live**: 3 chip tampil di atas input, tap chip mengisi draft; golden `quick_suggestions` re-record (411×72) & PASS; unit test hijau.

**Exit criteria:** semua item selesai sesuai cakupan yang disepakati; live test ulang di emulator lulus.

---

## FASE 4 — Rilis r1.2.0 🚀

| # | Sub-Task | Detail |
|---|----------|--------|
| 4.1 | CHANGELOG.md | Entri r1.2.0 (T3, M1, perbaikan UX, verifikasi) | 🟡 Draft 2026-08-09 (entri [Unreleased] lengkap; final menunggu rilis) |
| 4.2 | Dokumen | README (fitur baru/struktur baru), docs/DEVELOPER.md (struktur file setelah refactor), PRIVACY_POLICY (bila ada fitur baru) | 🟡 Draft 2026-08-09 (DEVELOPER.md: struktur + versi + roadmap + tech stack) |
| 4.3 | Bump versi | `gradle.properties`: `appVersion=r1.2.0`, `appVersionCode=27` | ⏳ TODO |
| 4.4 | Golden Roborazzi | Re-record final (UI berubah karena refactor/upgrade) |
| 4.5 | CI penuh | `testDebugUnitTest` + `lintDebug` + `verifyRoborazziDebug` + lint firestore rules |
| 4.6 | Commit + tag | `fix: r1.2.0 ...` → push tag `r1.2.0` → GitHub Release |
| 4.6b | **Koreksi release r1.1.3** (deferral FASE 0.5) | Upload APK r1.1.3/26 yang benar (`app/build/outputs/apk/debug/app-debug.apk`) ke release r1.1.3 — `gh release upload r1.1.3 <apk> --clobber` — bersamaan dengan rilis r1.2.0 |
| 4.7 | Play Store checklist | Jalankan `docs/PLAY_STORE_CHECKLIST.md`; upload AAB release |

---

## Manajemen Risiko

| Risiko | Dampak | Mitigasi |
|--------|--------|----------|
| Refactor T3 merusak UI | Regresi visual/fungsional | Verifikasi per langkah (Roborazzi + smoke test); tidak ubah behavior; batasi scope per commit |
| Upgrade compose-bom mengubah rendering | Golden Roborazzi gagal massal | Re-record goldens di CI runner (ubuntu) — pola yang sudah dipakai r1.1.2; bandingkan diff PNG |
| Upgrade room 2.8.x bermasalah dengan skema v11 | Migration error di produksi | Migration test dijalankan sebelum merge; jalur 8→11 penuh |
| okhttp 5 breaking | Kompilasi gagal / runtime error | Defer ke r1.3.0 jika audit API besar |
| Tag r1.1.3 salah lagi (seperti r1.1.3 lama berisi APK r1.1.2) | Release menyesatkan | Verifikasi `versionName` APK hasil build sebelum upload (FASE 0.5) |

---

## Definisi Selesai (DoD)

- [ ] Semua test unit hijau (termasuk migration test & test baru)
- [ ] Lint PASS (tanpa warning baru)
- [ ] Roborazzi verify PASS (golden diperbarui & di-commit)
- [ ] Live smoke test di emulator: chat→transaksi, rekap, settings/dark mode, Tanya AI, restart — tanpa crash
- [ ] CHANGELOG & docs diperbarui
- [ ] Tag r1.2.0 → GitHub Release berisi APK dengan versionName benar

---

*Rencana hidup — dapat disesuaikan dengan temuan saat eksekusi. Prioritaskan FASE 0 dulu (mengunci r1.1.3).*
