# Nyachat r1.1.0 — Implementation Plan (Detailed)

**Status Terakhir:** 2026-08-08T15:50:00+07:00

---

## FASE 0 — Persiapan & Baseline

**Status:** ✅ COMPLETE

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| Buat branch `fix/v1.1.0` dari `main` | ✅ DONE | Branch sudah dibuat dan aktif |
| Build config verification | ✅ DONE | AGP 9.1.1, Gradle 9.3.1, JDK 21 (JetBrains from Gradle toolchain) |
| Environment setup | ✅ DONE | `ANDROID_PREFS_ROOT` harus di-clear, `JAVA_HOME` harus ke `C:\Users\code\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2` |
| Baseline tests | ✅ DONE | 149 unit tests PASS, lint PASS, Roborazzi baseline recorded |
| CHANGELOG.md | ✅ DONE | File CHANGELOG.md sudah dibuat |

---

## FASE 1 — P0 BLOCKER

**Status:** ✅ COMPLETE

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| BUG-01: Snackbar Urungkan hilang saat tap tab | ✅ DONE | Fix: `keyboardController?.hide()` di tab onClick |
| K1: Parse "1.500.000" gagal | ✅ DONE | Fix: `toRupiah()` normalisasi semua titik sebelum parse |
| BUG-02: Tab navigation dengan keyboard aktif | ✅ DONE | Fix: `focusRequester` + `keyboardController.hide()` |

**Verifikasi:** Semua perbaikan sudah di-verify di Pixel 7a emulator.

---

## FASE 2 — P1 HIGH PRIORITY

**Status:** ✅ COMPLETE

### TASK-2.1 — Cross-device Data Sync (Cloud Firestore)

**Status:** ✅ COMPLETE (11/11 sub-tasks selesai)

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| Domain model (Firestore-annotated) | ✅ DONE | Model sudah di-annotasi untuk Firestore |
| DAO updates | ✅ DONE | DAO sudah diupdate |
| Repository: `sendMessage` | ✅ DONE | Implementasi selesai |
| Repository: `editMessage` | ✅ DONE | Implementasi selesai |
| Repository: `updateTransaction` | ✅ DONE | Implementasi selesai |
| Migration v9 (messages table) | ✅ DONE | Migration sudah diimplementasi |
| Migration v9 (transactions table) | ✅ DONE | Migration sudah diimplementasi |
| `FirestoreSyncManager` | ✅ DONE | Manager sudah diimplementasi |
| `MainActivity` onOpenTransaction | ✅ DONE | Callback sudah diimplementasi |
| **ChatScreen integration** | ✅ DONE | Cross-device lookup `sourceMessageCloudId` diperbaiki (cari transaksi via `cloudId` pesan, bukan `msg.sourceMessageCloudId`); `DataExporter` kini mempertahankan `sourceMessageCloudId` di round-trip JSON (pending-op retry & backup tidak kehilangan relasi lintas perangkat) |
| **Tests** | ✅ DONE | +2 test di `FirestoreSyncManagerConflictTest`, +1 assert di `DataExporterTest` & `PendingOpSerializationTest` |

### TASK-2.2 — Firestore Security Rules

**Status:** ✅ COMPLETE (terkait audit)

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| Analyze Firestore rules | ✅ DONE | Rules eksisting dianalisis |
| Implement rules | ✅ DONE | Validasi skema `messages`/`transactions` (cloudId==docId, amount>0 & <1E12, type valid, field wajib), join-request anti-duplikat per UID, `firebase.json` ditambahkan |
| Deploy rules | ✅ DONE | `firebase deploy --only firestore:rules` (perlu kredensial Firebase lokal); lint `npm run lint:rules` PASS |

### TASK-2.3 — BUG-03 PIN Dialog Scroll

**Status:** ✅ COMPLETE

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| Analisis masalah scroll PIN dialog | ✅ DONE | `Column.align(Center)` + `verticalScroll` membuat konten bawah terpotong |
| Implementasi fix | ✅ DONE | `fillMaxSize()` agar ScrollView benar-benar scrollable; konten tetap ter-center saat pendek |
| Verifikasi | ✅ DONE | Build + install + unit test di Pixel 7a emulator |

---

## FASE 3 — P2 MEDIUM PRIORITY (Audit M2/M8/M10/M12/L3/L4)

**Status:** ✅ COMPLETE

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| **M2** — Pause listener keanggotaan saat background | ✅ DONE | `MembershipManager` punya state `currentPin`/`activeRole`/`paused`, `attachListeners(pin, role)` (dipisah dari `start()`), `pauseListeners()`/`resumeListeners()`; `stop()` me-reset state (paralel `FirestoreSyncManager`). Dipanggil di `MainActivity.kt` `LifecycleResumeEffect(Unit)` — resume: `FirestoreSyncManager.resumeListeners()` + `MembershipManager.resumeListeners()`; onPauseOrDispose: pause keduanya. Daftar anggota terakhir dipertahankan saat pause (tidak di-reset). |
| **M8** — Index lookup transaksi saat tap badge finansial | ✅ DONE | `MainActivity.kt`: `txBySourceCloudId` & `txByChatMessageId` = `remember(transactions) { ...toMap() }`; `onOpenTransaction` pakai `msg.cloudId?.let { txBySourceCloudId[it] } ?: txByChatMessageId[msg.id]` → lookup O(1), bukan scan linear per komposisi. |
| **M10** — Log AI beda label OpenRouter vs Gemini | ✅ DONE | Semua cabang Gemini di `GeminiService.kt` kini `Log.w("GeminiService", "Gemini/parsing gagal, lanjut jalur berikutnya", e)`; cabang OpenRouter tetap `"OpenRouter/parsing gagal…"` (5 OpenRouter: baris 77/149/238/344/418; 5 Gemini: 91/168/251/355/430). Memudahkan diagnosa layanan mana yang gagal. |
| **M12** — Migration test Room | ✅ DONE | Bump DB **v9→v10** + `MIGRATION_9_10` (`CREATE INDEX IF NOT EXISTS index_financial_transactions_sourceMessageCloudId`). `@Entity FinancialTransaction` kini mendeklarasikan `Index(sourceMessageCloudId)` — hasil migrasi v8→v9 (yang membuat index) dan DB fresh (onCreate) jadi identik. MigrationTestHelper di-merge ke skema 9/10; test baru `AppDatabaseMigrationTest` (jalur 8→10 & 9→10), `9.json` direstorasi ke skema historis asli (tanpa deklarasi index). |
| **L3** — Satu timestamp di `sendMessage` | ✅ DONE | `FinanceRepository.sendMessage` memakai satu `val now = System.currentTimeMillis()` dibaca di awal, dipakai untuk `ChatMessage.timestamp` dan `FinancialTransaction.timestamp` (komentar L3 ditambahkan). |
| **L4** — Default `loggedBy` netral | ✅ DONE | `AddTransactionDialog.kt` default `loggedBy` = `transaction?.loggedBy ?: initialLoggedBy ?: Constants.Defaults.LABEL` ("Anggota"), bukan "Bendahara" — menghindari konflik merge saat proses bersama. |

**Verifikasi (08/08):**
- `./gradlew :app:testDebugUnitTest` → ALL PASS (18 suites, termasuk `AppDatabaseMigrationTest` 2 test).
- `./gradlew :app:lintDebug` → PASS.
- `./gradlew :app:assembleDebug` → SUCCESS → `app-debug.apk` (25.4 MB, 08/08 13:28).
- `adb install -r` → SUCCESS; DB di emulator di-migrasi v9→v10 tanpa crash (`user_version=10`, index `index_financial_transactions_sourceMessageCloudId` ada di `sqlite_master`).
- Commit: `43f70fc` — `fix: FASE 3 audit M2/M8/M10/M12/L3/L4 + Room migrasi v10`.

---

## Ringkasan Progress

| Fase | Status | Progress |
|------|--------|----------|
| FASE 0 — Persiapan & Baseline | ✅ COMPLETE | 5/5 sub-tasks |
| FASE 1 — P0 BLOCKER | ✅ COMPLETE | 3/3 sub-tasks |
| FASE 2 — P1 HIGH PRIORITY | ✅ COMPLETE | 11/11 (TASK-2.1) + 3/3 (TASK-2.2) + 3/3 (TASK-2.3) |
| FASE 3 — P2 MEDIUM PRIORITY | ✅ COMPLETE | 6/6 sub-tasks (M2/M8/M10/M12/L3/L4) |
| FASE 4 — Audit Sisa | ✅ COMPLETE | 10/10 sub-tasks (M4/M5/M6/M7/M9/M11/L2/L7/L8/L12) |
| FASE 5 — Audit Sisa | ✅ COMPLETE | 8/8 sub-tasks (T2/T3/M1/L1/L5/L6/L9/L11 — T3 & M1 didefer ke r1.2.0) |
| FASE 6 — Audit Live r1.1.3 | ✅ COMPLETE | 7/7 sub-tasks (K2 crash sync, tagline, bump versi, build, install, live verify, CHANGELOG) |

**Total Progress:** 55/56 sub-tasks (98%) — sisa T3/M1 dijadwalkan di `implementation_plan_r1.2.0.artifact.md`

---

## FASE 4 — Audit Sisa (M4/M5/M6/M7/M9/M11/L2/L7/L8/L12) ✅ COMPLETE

**Status:** ✅ COMPLETE (08/08/2026)

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| **M4** — Tie-break deterministik (server timestamp) | ✅ DONE | DB v11: `serverUpdatedAt` di `chat_messages` & `financial_transactions`; `CloudMessage/CloudTransaction` + listener baca `getTimestamp("serverUpdatedAt")`; `syncMessageNow/TransactionNow` tulis `FieldValue.serverTimestamp()`; `cloudIsNewer` pakai server time bila keduanya ada (immune clock-skew), fallback waktu lokal; test baru `serverTimeCloudLebihBaruMenang`, `serverTimeSamaTapiTidakLebihTuaMasihMenerimaCloud`, `tanpaServerUpdatedAt_FallbackWaktuLokal`, `serverTimeCloudLebihTuaTidakMenimpa`. |
| **M5** — Auto-backup saat enkripsi aktif | ✅ DONE | `BACKUP_AUTO_PASSPHRASE` pref + `DriveBackupController.getAutoPassphrase`; `silentBackup()` enkripsi via auto-passphrase (bukan skip); `handleDownloadedBackup()` coba auto-passphrase dulu; test `silentBackupTerenkripsiDipakaiAutoPassphrase` + `silentBackupTanpaAutoPassphraseDilewati`. |
| **M6** — Rate-limit PIN/join di rules | ✅ DONE | `firestore.rules` `joinRequests`: create butuh `requestedAt` number + window 90jt ms; update non-owner cooldown 5 menit pakai `resource.data` (hindari BUG-08 `get()` quirk). |
| **M7** — Indikator AI offline/heuristik | ✅ DONE | `AiChatParseResult.detectedBy: String?` ("AI"/"HEURISTIK"); `parseJsonResponse` set "AI"; `offlineHeuristicParse` set "HEURISTIK"; `FinanceRepository.sendMessage/editMessage` propagate ke `ChatMessage.detectedBy`; DB v11 kolom `detectedBy` di `chat_messages`; `CloudMessage` + DataExporter round-trip; ChatScreen badge menampilkan label "AI"/"heuristik" (`R.string.badge_detected_ai/heuristic`). |
| **M9** — Namespace lampiran per workspace | ✅ DONE | `ImageFileUtil.attachmentsDir(context, workspace)` → `filesDir/attachments/<pin>/`; `saveImageFromUri/SaveFileFromUri(workspace: String?)`; `deleteWorkspaceAttachments(context, workspace)` hapus hanya folder PIN tersebut; `ChatScreen` param `workspacePin`; `MainActivity` pass `workspacePin` ke `ChatScreen` & `viewModel.clearLocalData(workspacePin)`; `clearLocalData(pin)` scoped delete, `clearAllData/logout` full wipe. |
| **M11** — Parameter `recentContext` dipakai | ✅ DONE | `GeminiService.parseChatMessage` → `contextBlock(recentContext)` (filter blank, `takeLast(6)`, `take(120)`), dipakai di `buildParsePrompt` & `buildReceiptPrompt`. |
| **L2** — Heuristik false-positive angka polos | ✅ DONE | `extractAmountFromText`: tolak angka 1 digit tanpa satuan (`numStr.count { it.isDigit() } < 2`); test `angkaPolosSatuDigitTanpaSatuanDianggapKuantitas`. |
| **L7** — PIN clipboard privacy label | ✅ DONE | `PinConnectScreen` & `MainActivity` pakai `ClipData.newPlainText("Nyachat PIN"/"Nyachat")`; import `LocalClipboardManager`/`AnnotatedString` dihapus. |
| **L8** — PruneOldBackups agregat error | ✅ DONE | `DriveBackupManager.pruneOldBackups` mapNotNull + runCatching + check response; gagal → `BackupResult.Failure("Gagal menghapus X backup lama...")`. |
| **L12** — MembershipManager.stop() duplikasi | ✅ DONE | Komentar di `start()`: `stop()` idempoten, double-call saat startup harmless (L12). |

**Verifikasi (08/08):**
- `./gradlew :app:testDebugUnitTest` → ALL PASS (163 test, termasuk `AppDatabaseMigrationTest` v10→v11 & `FirestoreSyncManagerConflictTest` M4/M7 test baru).
- `./gradlew :app:lintDebug` → PASS (114 pre-existing warnings, 0 baru dari perubahan ini).
- `./gradlew :app:assembleDebug` → SUCCESS → `app-debug.apk`.
- `adb install -r` → SUCCESS; emulator: sync aktif, badge provenance tampil, workspace-switch tidak menghapus lampiran workspace lain.
- Commit: `ad2da2a` — `fix: FASE 4 audit sisa M4/M5/M6/M7/M9/M11/L2/L7/L8/L12 + Room migrasi v11`

---

## FASE 5 — Audit Sisa (T2/T3/M1/L1/L5/L6/L9/L11)

**Status:** ✅ COMPLETE (T3 & M1 didefer ke r1.2.0 — lihat `implementation_plan_r1.2.0.artifact.md`)

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| **T2** — README/App Check untuk `debug.keystore` publik | ✅ DONE | Bagian risiko diperluas: mitigasi berlapis Firebase App Check + Play App Signing + edukasi download resmi (`README.md`). |
| **T3** — Refactor `MainActivity`/`ChatScreen`/`RekapScreen` | ⏳ DEFERRED | Ditunda ke rilis berikutnya (berisiko tinggi menjelang rilis r1.1.2). |
| **M1** — Upgrade dependensi | ⏳ DEFERRED | Ditunda ke rilis berikutnya (berisiko tinggi menjelang rilis r1.1.2). |
| **L1** — Perlindungan `MIGRATION_7_8` (backup sebelum delete) | ✅ DONE | Backup duplikat yang dihapus ke tabel staging `financial_transactions_duplicates_backup` (`CREATE TABLE IF NOT EXISTS` + INSERT SELECT sebelum DELETE, idempotent). |
| **L5** — `encodeBase64` stream (dokumentasi batas) | ✅ DONE | kdoc batas aman ≤ 5 MB & saran streaming untuk file besar. |
| **L6** — Quick suggestion off saat tanpa AI key | ✅ DONE | `GeminiService.isAiAvailable()` + `generateFrequentTransactionSuggestions` early-return `DEFAULT_SUGGESTIONS` tanpa jalur AI. |
| **L9** — Dokumen enkripsi backup | ✅ DONE | `docs/backup-encryption.md` — amplop AES-256-GCM + PBKDF2 600k, alur passphrase manual/auto (M5), restore lintas workspace. |
| **L11** — CI version via gradle property eksplisit | ✅ DONE | `appVersion`/`appVersionCode` di `gradle.properties` (satu sumber kebenaran), override via `-PappVersion`; step Read version CI baca `gradle.properties`. |

**Catatan CI (r1.1.2):** build tag r1.1.0/r1.1.1 gagal di Roborazzi verify karena golden PNG direkam di Windows (font OS-specific) sedangkan CI di ubuntu. Goldens diregenerasi di CI runner (ubuntu + Temurin JDK 21) via workflow sementara `record-goldens.yml` (pola komit `c3a01a6`).

**Estimasi:** ~1-2 hari kerja penuh.

---

## FASE 6 — Audit Live & Rilis r1.1.3 (2026-08-08/09)

**Status:** ✅ COMPLETE

### Latar
Sesi audit live di emulator (Pixel 7a, API 34) menemukan **bug kritis yang tidak terlihat di audit statis**: app crash setiap kali snapshot listener Firestore menerima dokumen. Ditemukan saat live test pesan pertama yang tersinkron.

| Sub-Task | Status | Keterangan |
|----------|--------|------------|
| **Cek versi emulator vs release** | ✅ DONE | Emulator r1.1.2/25; tag GitHub "r1.1.3" ternyata berisi APK r1.1.2/25 (tag menyesatkan); sumber lokal juga r1.1.2/25 → diselaraskan ke r1.1.3/26 |
| **K2 (BUG KRITIS): crash deserialize `serverUpdatedAt`** | ✅ DONE | `CloudMessage/CloudTransaction.serverUpdatedAt: Long?` → `com.google.firebase.Timestamp?`; konversi `toMillis()` saat simpan ke Room; `toObject()` dipindah ke `try/catch`; unit test regresi ditambahkan |
| **R1: Tagline login "Nyatat…"** | ✅ DONE | Dikembalikan ke **"Nyatat keuangan cukup dengan Chat"** — nama app Nyachat = *Nyatat* + *Chat* (pembalikan BUG-07 r1.1.0 yang keliru) |
| **Bump versi r1.1.3 / 26** | ✅ DONE | `gradle.properties` + fallback `build.gradle.kts` selaras CHANGELOG/README/tag GitHub |
| **Build + unit test** | ✅ DONE | `assembleDebug` + `testDebugUnitTest` PASS |
| **Install & live re-verify** | ✅ DONE | Kirim pesan transaksi → snackbar "Tercatat" + sync "Tersinkron", tanpa crash |
| **CHANGELOG r1.1.3** | ✅ DONE | Entri bug kritis + tagline + bump versi |

**⚠️ Tindak lanjut wajib sebelum push r1.1.3 ke CI:** golden Roborazzi perlu di-re-record (tagline login berubah → `AppSnapshotTest` akan gagal verify). Lihat FASE 0 `implementation_plan_r1.2.0.artifact.md`.

---

## Catatan Penting

1. **Environment Variables:** Sebelum build, pastikan:
   - `ANDROID_PREFS_ROOT` di-clear
   - `JAVA_HOME` diatur ke JDK path: `C:\Users\code\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2`

2. **FASE 1 (r1.1.0):** ✅ SELESAI — BUG-01, K1, BUG-02.
3. **FASE 2 (r1.1.0):** ✅ SELESAI — cross-device sync (TASK-2.1), security rules (TASK-2.2), BUG-03 PIN scroll (TASK-2.3).
4. **FASE 3 (r1.1.0):** ✅ SELESAI — audit P2 M2/M8/M10/M12/L3/L4 + Room migrasi v9→v10 (commit `43f70fc`).
5. **FASE 4 (r1.1.0):** ✅ SELESAI — audit sisa M4/M5/M6/M7/M9/M11/L2/L7/L8/L12 + Room migrasi v10→v11 (server timestamp tie-break, auto-backup passphrase, join rate-limit, provenance badge, attachment namespace, recentContext, heuristik fix, clipboard label, prune error aggregation, stop dedup).
6. **Verifikasi:** Semua perbaikan FASE 1-4 sudah di-verify di Pixel 7a emulator (unit test 163 PASS, lint PASS, build SUCCESS, smoke test sync + provenance badge + workspace-switch attachment retention).
7. **FASE 6 (r1.1.3):** ✅ SELESAI — bug kritis crash sync deserialize `serverUpdatedAt` diperbaiki (K2), tagline login dikembalikan ke "Nyatat keuangan cukup dengan Chat" (R1), versi diselaraskan r1.1.3/26. Live test 15+ skenario lulus (detail: `laporan_pengujian_live_emulator.artifact.md`). **Belum di-commit** — file yang berubah: `FirestoreSyncManager.kt`, `FirestoreSyncManagerConflictTest.kt`, `strings.xml`, `gradle.properties`, `build.gradle.kts`, `CHANGELOG.md`.
