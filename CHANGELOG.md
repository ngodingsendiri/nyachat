# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - r1.2.0 (FASE 3 - audit UX)

### Fixed (BUG-06 & BUG-08 - audit live)
- **BUG-06**: Indikator sinkronisasi tidak lagi menakut-nakuti saat offline.
  Klasifikasi kegagalan sync dipetakan dengan benar: koneksi putus di lapisan
  bawah (UNAVAILABLE, IOException termasuk nested cause, "Failed to resolve",
  timeout) → **"Mode offline"** yang netral; error nyata (PERMISSION_DENIED,
  kuota, dll.) tetap ditandai **"Belum sinkron"**. Label `sync_status_error`
  diubah dari "Gagal sinkron" dan warna dot ERROR disamakan dengan OFFLINE
  (abu-abu) — data lokal tetap aman dan akan tersinkron saat koneksi pulih.
  Logika diekstrak ke fungsi murni `classifySyncFailure` + unit test (11 kasus:
  kode Firestore, teks, rantai cause).
- **BUG-08**: Field chat tidak lagi menyimpan karakter sisa setelah dialog
  tambah/edit transaksi ditutup — `inputText` di-reset via trigger
  `resetChatInputTrigger` (LaunchedEffect di ChatScreen) saat dialog ditutup.
  Reset digate khusus dialog yang dibuka dari tab Chat (tap badge finansial);
  draf chat tidak hilang saat user kembali dari tab Rekap.

## [r1.1.3] - 2026-08-08

### Fixed (audit live 2026-08-08)
- **CRITICAL (crash sync)**: `Could not deserialize object. Failed to convert a
  value of type com.google.firebase.Timestamp to long (found in field
  'serverUpdatedAt')` — app FATAL EXCEPTION setiap kali snapshot listener
  Firestore menerima dokumen (terverifikasi live di emulator: crash pada pesan
  pertama yang tersinkron). Penyebab: DTO `CloudMessage`/`CloudTransaction`
  mendeklarasikan `serverUpdatedAt: Long?` padahal cloud menyimpannya sebagai
  `com.google.firebase.Timestamp` (dari `FieldValue.serverTimestamp()`), sehingga
  `toObject()` selalu gagal sebelum `.copy()` dieksekusi. Diperbaiki dengan
  mengganti tipe DTO ke `Timestamp?` + konversi `toMillis()` saat simpan ke Room.
  Hardening: `toObject()` dipindahkan ke dalam `try/catch` agar skema tak dikenal
  dari data lama/backup tidak lagi mematikan proses.
- **L6 (penyempurnaan)**: fallback saran cepat tanpa key AI kini berbasis riwayat
  transaksi (personal tetap terjaga) — bukan statis kaku. `isAiAvailable()`
  memakai satu pemanggilan `getApiKey()`; konstanta `DEFAULT_SUGGESTIONS`
  dipusatkan di `GeminiService` (MainViewModel merujuk ke sana, hapus duplikasi).

### Changed (audit live)
- **Tagline login** dikembalikan ke **"Nyatat keuangan cukup dengan Chat"**
  (pembalikan BUG-07) — nama aplikasi Nyachat berasal dari kata *Nyatat* + *Chat*.
- Bump `gradle.properties` ke **r1.1.3 (versionCode 26)** — menyelaraskan sumber
  dengan CHANGELOG/README/tag GitHub r1.1.3 (sebelumnya sumber masih r1.1.2/25
  sehingga APK rilis r1.1.3 berisi versionName r1.1.2 yang menyesatkan).

## [r1.1.2] - 2026-08-08

### Fixed
- **CI rilis**: build tag r1.1.0/r1.1.1 gagal di step *Snapshot UI (Roborazzi
  verify)* karena golden PNG di-rekam di Windows (font OS-specific) sedangkan
  CI jalan di ubuntu → render berbeda walau kode tidak berubah. Golden file
  diregenerasi di CI runner (ubuntu + Temurin JDK 21) lewat workflow
  sementara, lalu di-commit — pola sama seperti komit `c3a01a6` sebelumnya.

### Changed
- **L11**: versi & versionCode dipindah ke `gradle.properties`
  (`appVersion` / `appVersionCode`) — satu sumber kebenaran; CI & lokal bisa
  override via `-PappVersion=... -PappVersionCode=...` tanpa edit file. Step
  "Read version" di CI membaca `gradle.properties` (bukan grep regex rapuh).
- Bump versi ke **r1.1.2** (versionCode **25**).

### Added (FASE 5 - audit)
- **L1**: `MIGRATION_7_8` (yang menghapus duplikat cloudId secara permanen)
  kini mem-backup baris yang dihapus ke tabel staging
  `financial_transactions_duplicates_backup` (idempotent, `IF NOT EXISTS`).
- **L5**: kdoc `ImageFileUtil.encodeBase64` menyebut batas aman ≤ 5 MB & saran
  streaming untuk file besar.
- **L6**: `GeminiService.isAiAvailable()`; `generateFrequentTransactionSuggestions`
  langsung mengembalikan fallback statis tanpa mencoba AI saat tidak ada key
  (hemat waktu/kuota BYOK).
- **L9**: dokumen `docs/backup-encryption.md` — format amplop AES-256-GCM +
  PBKDF2 600k, alur passphrase manual & auto, restore lintas workspace.
- **T2**: README bagian risiko `debug.keystore` diperluas — mitigasi Firebase
  App Check, Play App Signing, & edukasi download resmi.

## [r1.1.0] - 2026-08-08

> Fitur FASE 1-4 di bawah semuanya sudah dirilis — r1.1.2/r1.1.3 adalah build
> CI pertama yang sukses (r1.1.0/r1.1.1 gagal di step snapshot Roborazzi).

### Fixed
- **BUG-01**: Snackbar 'Urungkan' action now works correctly — was opening 'Kelola Anggota' due to SnackbarHost overlapping TopAppBar when keyboard visible. Fixed by increasing top padding from 8dp to 72dp to clear TopAppBar + status bar.
- **K1**: Parse nominal ribuan bertitik bertingkat (1.500.000, 15.000.000, etc.) — was returning null due to multiple dots in thousand separators. Fixed by normalizing all dots as thousand separators before parsing.
- **BUG-02**: Tab navigation (Chat ↔ Rekap) now works when keyboard is visible — was blocked because keyboard covered bottom nav. Fixed by hiding keyboard on tab click and adding ImeAction.Done + keyboardActions to input fields.
- **K2**: Parsing transportasi (bensin, taxi, ojek, grab, gojek, tol, parkir, "isi") kini tercatat sebagai Pengeluaran kategori Transportasi — was missed entirely because `isExpenseTrigger` tidak punya keyword transportasi. Plus mapping kategori untuk nasi/market/belanja/taxi.
- **BUG-07**: Typo tagline login "Nyatat" → "Mencatat"; versi app disinkronkan ke r1.1.0 (versionCode 24) di layar login & Settings.
- **BUG-04**: Field nama di onboarding kini punya tombol clear cepat (trailing icon) — user tidak perlu menghapus nama prefilled dari Google manual karakter per karakter.

### Fixed
- **BUG-08**: Sinkronisasi Firestore "Gagal sinkron" — penyebabnya `get(...).exists` pada subcollection `members` dari dalam aturan selalu mengembalikan `null` (rules menolak langsun semua baca/tulis walau member valid). Diperbaiki dengan mengganti ke fungsi `exists()` (atomik, tanpa perlu baca resource) sehingga `isMember`/`isOwner` evaluasi benar. Terverifikasi live: atur → "Tersinkron".

### Added
- **TASK-2.1**: Cross-device chat↔transaksi lookup via `sourceMessageCloudId` (tap badge finansial dari pesan tersinkron di perangkat lain kini membuka transaksi yang benar); `DataExporter` mempertahankan relasi lintas-perangkat pada JSON (pending-op retry & backup).
- **TASK-2.2**: Perkuat Firestore Security Rules — validasi skema `messages`/`transactions` (`cloudId` = docId, `amount` angka positif & di bawah 1e12, tipe valid), join-request anti-duplikat per UID; tambah `firebase.json`.
- **TASK-2.3**: BUG-03 — layar konfirmasi PIN kini benar-benar scrollable (`fillMaxSize` + `verticalScroll`, tetap ter-center saat konten pendek).

### Changed
- **TASK-2.1**: Unit test `sourceMessageCloudId` round-trip & merge lintas perangkat (+4).

### Fixed (FASE 3 — P2, audit M2/M8/M10/M12/L3/L4)
- **M2**: Listener keanggotaan workspace kini dijeda saat app di background & dipasang ulang saat resume (`MembershipManager.pauseListeners()/resumeListeners()`, sinkron dengan `LifecycleResumeEffect` di `MainActivity`) — hemat kuota/baterai & mencegah komposisi ulang daftar anggota di background.
- **M8**: Lookup transaksi saat tap badge finansial kini O(1) via map indeks `txBySourceCloudId`/`txByChatMessageId` (di-rebuild hanya saat daftar transaksi berubah) — bukan scan linear per komposisi.
- **M10**: Log error parsing AI kini memakai label yang benar per penyedia — cabang Gemini memberi label "Gemini/parsing gagal…", OpenRouter tetap "OpenRouter/…" (sebelumnya semua cabang berlabel OpenRouter, menyulitkan diagnosa).
- **M12**: Tambah migration test Room (`AppDatabaseMigrationTest`) yang memvalidasi skema historis v8→v10 (kolom & index `sourceMessageCloudId`, data lama terjaga) dan jalur v9→v10.
- **L3**: `FinanceRepository.sendMessage` kini memakai satu timestamp `now` untuk pesan & transaksi — konsisten, bukan dua panggilan `System.currentTimeMillis()`.
- **L4**: Dialog tambah/edit transaksi kini default `loggedBy` netral = "Anggota" (bukan "Bendahara") — sesuai sumber konflik merge.

### Changed (FASE 3)
- **M12**: DB Room naik **v9→v10** + `MIGRATION_9_10` (idempotent, `CREATE INDEX IF NOT EXISTS`) menambahkan index `financial_transactions(sourceMessageCloudId)` — menyamakan DB fresh (onCreate) dengan DB hasil migrasi v8→v9 yang sebelumnya inkonsisten; skema `9.json` direstorasi ke kondisi historis asli tanpa index.

### Fixed (FASE 4 — Audit Sisa M4/M5/M6/M7/M9/M11/L2/L7/L8/L12)
- **M4**: Resolusi konflik sync (last-writer-wins) kini pakai `FieldValue.serverTimestamp()` (`serverUpdatedAt`) — immune terhadap selisih jam antar-perangkat. Tie-break deterministik: jika kedua sisi punya `serverUpdatedAt`, bandingkan waktu server; fallback ke waktu lokal (editedAt/timestamp). DB Room **v10→v11** + `MIGRATION_10_11` (kolom `serverUpdatedAt` di `chat_messages` & `financial_transactions`).
- **M5**: Auto-backup harian sekarang jalan walau enkripsi aktif — pakai passphrase otomatis dari Android Keystore (`BACKUP_AUTO_PASSPHRASE`) bukan dilewati. Restore otomatis coba passphrase Keystore dulu sebelum prompt manual.
- **M6**: Rate-limit join request di `firestore.rules`: create butuh `requestedAt` dalam jendela 25 jam; update non-owner cooldown 5 menit, pakai `resource.data` (bukan `get()`) biar lepas quirk BUG-08.
- **M7**: Badge transaksi di chat kini menampilkan asal deteksi — "AI" (Gemini/OpenRouter) atau "heuristik" (fallback offline) lewat kolom `detectedBy` di `ChatMessage` (DB v11). Transparansi: user tahu nilai diproses AI atau mesin aturan lokal.
- **M9**: Lampiran (foto nota/dokumen) di-namespace per workspace `filesDir/attachments/<PIN>/` — ganti workspace hanya hapus folder workspace lama, foto workspace lain aman. `clearLocalData(pin)` scoped delete; `clearAllData/logout` tetap full wipe.
- **M11**: Parameter `recentContext` sekarang dipakai di prompt AI — helper `contextBlock` ambil 6 pesan terakhir (filter blank, max 120 char) → disisipkan ke `buildParsePrompt` & `buildReceiptPrompt`.
- **L2**: Heuristik extract nominal tolak angka 1 digit tanpa satuan (mis. "makan 2 kucing" → kuantitas, bukan Rp 2.000) — kurangi false-positive.
- **L7**: Salin PIN pakai `ClipData` dengan label "Nyachat PIN" / "Nyachat" — ClipboardManager menampilkan label jelas, bukan teks polos.
- **L8**: `pruneOldBackups` agregasi error per-file — gagal hapus file lama → `BackupResult.Failure` detail, bukan diam-diam gagal.
- **L12**: Dokumentasi: `MembershipManager.stop()` idempoten, double-call saat startup harmless.

### Changed (FASE 4)
- **M12**: DB Room naik **v10→v11** + `MIGRATION_10_11` (kolom `serverUpdatedAt` di `chat_messages` & `financial_transactions`, kolom `detectedBy` di `chat_messages`); skema `11.json` diekspor.

---

## [r1.0.3] - 2026-08-06

### Added
- Backup & restore Google Drive (v1.2.9)
- Export rekap CSV (v1.2.9)
- Workspace bersama + kelola anggota + persetujuan owner (FASE 4, v1.4.0)
- Sinkronisasi realtime Firestore (FASE 4, v1.4.0)

### Fixed
- Google Sign-In di release build
- Redesign layar login (logo + nama + tagline)
- Sembunyikan navigasi saat login
- Dialog update tersedia di semua build