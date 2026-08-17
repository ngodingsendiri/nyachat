# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [r1.6.0] - 2026-08-17 (audit bug: resolusi konflik · presisi uang · relay · dokumentasi isOwner)
- **Satu resolusi konflik** (audit): duplikat cloudId yang sama sebelumnya bisa
  dipilih pemenang BERBEDA oleh dua jalur — merge listener (`cloudIsNewer`)
  memprioritaskan `serverUpdatedAt`, dedupe tampilan (`dedupeByCloudId`) hanya
  `editedAt ?: timestamp`. Kini satu definisi `lastWriterCompare`
  (serverUpdatedAt → waktu efektif) dipakai BERSAMA keduanya; kasus tepi edit
  lokal lebih baru yang belum sync tidak lagi bisa dikalahkan cloud lebih tua.
- **Presisi uang di SEMUA batas persist**: `normalizeAmount` dipindah ke
  data.local dan diterapkan juga di parse backup/restore & pending-op
  (`transactionFromJson`) dan merge cloud (`upsertTransaction`) — pecahan
  rupiah dari file backup lama / perangkat lain tidak lagi masuk DB apa adanya
  (sebelumnya hanya jalur parse AI yang men-snap).
- **Relay AI lebih tahan salah klasifikasi**: kegagalan "fungsi tidak terdeploy /
  FirebaseApp belum aktif" dideteksi via kode error FirebaseFunctions
  (`NOT_FOUND`) & jenis exception, bukan substring pesan — error penyedia hulu
  yang kebetulan memuat kata "NOT_FOUND"/"FirebaseApp" tidak lagi mematikan
  relay. Pemulihan saat jaringan pulih sudah ada (tanpa restart app).
- **Dokumentasi rules**: komentar BUG-08 dikoreksi (get()/exists() tidak
  bergantung rules baca target — bukti bootstrap owner) + penjelasan kenapa
  `get()` pada member doc sendiri aman (self-read dijamin).
- **Pengujian**: +3 unit test (snap pecahan di `transactionFromJson`, 2 paritas
  serverUpdatedAt di dedupe), 507 test total hijau, lint 0.

## [r1.5.3] - 2026-08-16 (presence anggota online · update UI in-app · audit keanggotaan)
- **Presence: topbar menampilkan anggota yang SEDANG ONLINE** (r1.6.0 presence):
  avatar di top bar bukan lagi pengirim pesan, melainkan anggota workspace yang
  aktif memakai app saat ini — tiap anggota bertumpuk (maks. 6, sesuai cap pro),
  diri sendiri selalu paling depan. Online dideteksi via heartbeat `lastActiveAt`
  (jam server) tiap 60 detik selama app di foreground; window online 3 menit.
  Subtitle baru "N dari M anggota online" (satu online → tampil nama).
- **Keamanan presence**: field `lastActiveAt` hanya bisa di-update oleh pemilik
  doc dengan nilai jam SERVER (`request.time`) — spoofing jam klien ditolak rules.
- **UI update in-app dirombak (umpan balik tester)**: dialog "Update Tersedia"
  kini menampilkan ikon + judul konsisten, versi saat ini, catatan rilis yang
  dibersihkan (tanpa markup), dan progress unduhan (indikator melingkar + batang
  + persen); dialog "Perlu Update" diberi ikon Info. Teks UI bebas emoji.
- **Audit workspace & keanggotaan**: `rejectJoin` kini atomik (tandai `rejected`
  + hapus dalam satu transaksi — tidak ada lagi request tersangkut bila crash di
  tengah); konstanta peran `owner`/`member` disatukan ke `Constants.Roles`
  (sebelumnya duplikasi literal bisa melenceng). Residual terdokumentasi di kode:
  race `approveJoin`/`leaveWorkspace` (SDK tidak bisa query koleksi dalam
  transaksi — perbaikan sejati butuh counter server-side) & `setPlan` masih
  placeholder menunggu Play Billing.
- **Pengujian**: +8 unit test presence (`PresenceOnlineTest`), +3 snapshot
  Roborazzi topbar (1/2/6 anggota online), 504 test total hijau, lint 0.

## [r1.5.2] - 2026-08-15 (notifikasi keanggotaan · nama workspace · kapasitas anggota per plan)
- **Notifikasi keanggotaan (umpan balik tester)**: pemilik kini mendapat
  notifikasi "X ingin bergabung ke <nama workspace>" saat ada permintaan
  bergabung; pemohon mendapat notifikasi disetujui/ditolak. Dikirim via cloud
  function `handleJoinRequest` (FCM data message) di kanal "Aktivitas workspace",
  di-gate toggle notifikasi chat yang sama (keputusan beta).
- **Nama workspace custom**: nama default "Keuangan Bersama" bisa diganti dari
  "Kelola Anggota" (pemilik) — nama tampil di top bar & header kelola anggota.
  Doc keluarga baru diberi default `name` + `plan`.
- **Kapasitas anggota per plan**: free = 2 anggota, pro = 6 (termasuk pemilik).
  Permintaan bergabung yang melebihi kapasitas tidak bisa disetujui — UI
  mengarahkan ke dialog upgrade (placeholder; verifikasi Play Billing menyusul
  saat produksi). Cloud function menunda notifikasi pemilik bila workspace penuh.
- **UI Kelola Anggota dirapikan (umpan balik tester)**: aksi per anggota dipindah
  ke satu menu overflow (⋮) — sebelumnya tombol teks panjang + ikon edit
  berantakan; section "Permintaan Bergabung" disembunyikan total saat kosong
  (sebelumnya menampilkan baris "Tidak ada permintaan").
- **Keamanan**: update doc keluarga dibatasi rules hanya field `name` & `plan`
  oleh pemilik (`hasOnly(['name','plan'])`).
- **Perbaikan (audit r1.6.0)**: `approveJoin` membaca plan otoritatif dari doc
  keluarga (bukan state yang bisa basi) & ID notifikasi keanggotaan per-pemohon
  (`requesterUid`) agar notifikasi tidak saling menimpa.

## [r1.5.1] - 2026-08-15 (update in-app langsung untuk build release)
- **Update in-app tidak lagi diarahkan ke repo** di build release — tombol
  "Update Sekarang" kini langsung mengunduh APK dari GitHub Release dan
  memunculkan installer (sebelumnya release build hanya membuka halaman rilis
  di browser; tester dikeluhkan "klik update malah ke repo"). `REQUEST_INSTALL_PACKAGES`
  dipindah dari manifest debug ke main manifest agar berlaku di semua build.

## [r1.5.0] - 2026-08-15 (audit menyeluruh: keamanan server, presisi uang, aturan workspace)
- **P1#1 Kunci pemulihan auto-backup**: passphrase acak auto-backup (hanya di
  Keystore) kini ditampilkan SEKALI ke user saat pertama dibangkitkan — HP
  hilang/reinstall tidak lagi mengunci backup Drive selamanya.
- **P1#2 Rate limit AI server**: `aiComplete` dibatasi 30 panggilan/menit/uid +
  batas prompt 6.000 char & gambar 3 MB — kuota AI server tidak bisa dikuras
  klien nakal.
- **P2#5 Privasi log AI**: isi output AI (data finansial) tidak lagi ditulis ke
  Cloud Logging — cukup model + panjang.
- **P2#6 Invariant uang**: `normalizeAmount()` men-snap pecahan di batas
  persist + `MoneyExactnessTest` mem-pin bahwa Double eksak untuk rupiah integer.
- **P2#4 Struktur**: mesin heuristik offline dipindah dari GeminiService.kt
  (1.635 → 1.111 baris) ke `OfflineTransactionParser.kt` — delegasi menjaga
  seluruh referensi lama tetap bekerja (semua test ekstraksi hijau).
- **P2#3 Smoke test perangkat nyata**: `AppSmokeTest` (androidTest) + job CI
  `device-smoke` (emulator API 34) — melengkapi gap Robolectric yang tidak
  mensimulasikan runtime penuh.
- **P3#8 Metrik ekstraksi**: konteks ekstraksi (sumber AI/HEURISTIK, jumlah,
  campuran) dicatat via Crashlytics custom key + log untuk triase crash.
- **Aturan "1 akun = 1 workspace" ditegakkan**: `ensureOwnerWorkspace` kini
  menolak membuat workspace baru bila akun sudah jadi owner di workspace lain
  (sebelumnya hanya cek PIN sendiri — akun tes terbukti menumpuk 15 workspace
  dummy di Firestore). Pesan error jelas di layar PIN + `WorkspaceOwnershipTest`
  (+5 test). Owner tetap wajib mewariskan kepemilikan sebelum keluar (anti
  workspace yatim); member yang diundang bebas keluar via exit membership.
- **Pembersihan database**: 15 workspace dummy + 281 dokumen subcollection
  (messages/transactions/members/joinRequests) dihapus permanen dari Firestore
  — koleksi `families` bersih (0 sisa).

## [r1.4.0] - 2026-08-14 (audit Finance AI menyeluruh · workspace & keanggotaan · polish UI)
- **Audit input mesin AI/offline**: angka polos NON-NOMINAL tidak lagi dianggap
  nominal — TAHUN 19xx/20xx ("bayar spp 2025 sebesar 2jt" tadinya tercatat
  Rp 2.025 dan 2jt HILANG) dan KUANTITAS ≥2 digit ("beli 12 buku seharga 50rb"
  tadinya Rp 12.000 dan 50rb hilang). Guard `isNonMonetaryNumber` dipakai
  konsisten di ekstraksi nominal, pemisahan batas nominal, & perhitungan
  jumlah nominal. +7 regression test (477 total).
- **Badge campuran pelangi**: pesan chat yang berisi PEMASUKAN DAN PENGELUARAN
  sekaligus kini menampilkan badge gradien pelangi (bukan hijau/merah) sebagai
  penanda campuran — ikon AutoAwesome + teks "N transaksi · total".
  Field baru `hasMixedTypes` di ChatMessage (Room v13) + Firestore + backup JSON;
  pesan lama di-backfill dari transaksi tersimpan (COUNT DISTINCT type > 1).
- Transaksi dari pesan campuran tetap dipecah per jenis ke Rekap (pemasukan ke
  pemasukan, pengeluaran ke pengeluaran — tanpa netting); sudah terverifikasi
  E2E di emulator (1 pesan → PEMASUKAN 2jt + PENGELUARAN 45rb + 30rb).

### Fixed (audit campuran pemasukan+pengeluaran 2026-08-14)
- **"uang keluar 3jt" tidak terekam** saat satu pesan berisi pemasukan DAN
  pengeluaran (mis. "uang masuk 5jt uang keluar 3jt" hanya merekap 1
  transaksi). Frasa "uang keluar"/"keluar" (frasa pengeluaran umum) tidak
  ada di trigger heuristik — kini ditambahkan; total pesan campuran tercatat
  lengkap tanpa netting. +4 regression test.
- **AI online yang mengembalikan transaksi tidak lengkap** (hanya 1 dari 2+
  nominal pada pesan campuran) kini dilengkapi heuristik: `mergeAiWithHeuristic`
  menambah transaksi yang belum terwakili (anti-duplikat by tipe+nominal),
  total dijumlah penuh. Sebelumnya backup heuristik hanya aktif saat AI
  bilang "tidak ada transaksi", bukan saat AI kehilangan sebagian. +3 test.

### Changed (clean-code & lint 2026-08-14)
- **Konvensi Compose `modifier` diperbaiki** (7 fungsi): parameter `modifier`
  kini di posisi pertama parameter opsional (lint ModifierParameter) di
  `AvatarImage`, `ChatMessageBubble`, `QuickSuggestionRow`, `ChatInputBar`,
  `StackedAvatars`, `TransactionItemCard` — semua pemanggil memakai named
  argument sehingga aman.
- **Offset bubble swipe pakai lambda overload** (lint UseOfNonLambdaOffset
  Overload): `swipeOffsetX` (Animatable) kini dibaca via `Modifier.offset { }`
  sehingga bubble mengikuti state swipe saat berubah (non-lambda hanya dibaca
  sekali).
- **Typografi placeholder**: "..." → "…" (ellipsis unicode) di
  `chat_input_placeholder` & `chat_ai_thinking`; test a11y ikut disinkronkan.
- **`dataExtractionRules` Android 12+**: backup cloud & transfer device
  dikecualikan eksplisit (`res/xml/data_extraction_rules.xml`) — konsisten
  dengan `allowBackup=false` untuk data keuangan sensitif.
- **Dead code dihapus**: `SecureStorage.clearAll`/`clearAllAsync` tidak punya
  pemanggil sama sekali (logout membersihkan secret per-key via
  `deleteSecretAsync`).

### Changed (audit Finance AI 2026-08-14)
- **Ekstraksi multi-transaksi konsisten** (laporan user: "Gaji lembur 200.000
  Beli rokok 30.000 Makan Malam 45.000" hanya jadi 1 transaksi): root cause di
  `splitTransactionSegments` — hanya memecah pada separator eksplisit
  (koma/;"dan"/"sama"/"atau"), padahal mayoritas chat Indonesia multi-transaksi
  TANPA separator. Kini ada strategi kedua `splitByAmountBoundaries`: tiap
  batas nominal = akhir satu transaksi ("beli bakso 15rb bensin 30rb rokok
  20rb" → 3 transaksi terpisah).
- **Nominal Indonesia lengkap**: `extractAmountFromText` menangani "50.000",
  "50rb", "50k", "1,5jt", "1.500.000", "Rp 5jt" dll; angka jam ("07.30") dan
  kuantitas 1 digit ("2 kopi") tetap ditolak (bukan nominal).
- **Tidak ada netting/gabungan**: total ringkasan = PENJUMLAHAN semua nominal;
  tiap transaksi direkap terpisah (income/expense per segmen).
- **Badge jujur untuk multi-transaksi**: field baru `detectedCount`
  (Room v12 migration + `Constants.Fields.DETECTED_COUNT` + CloudMessage DTO +
  DataExporter) — bubble menampilkan "N transaksi" + total saat sebuah pesan
  memuat beberapa transaksi, bukan total tunggal yang mengecoh.
- **Backup heuristik saat AI salah bilang "tidak ada transaksi"**: bila pesan
  memuat ≥2 nominal dan AI mengembalikan kosong, parser offline dipakai sebagai
  jaring pengaman (`shouldHeuristicBackup`) sehingga transaksi tidak hilang saat
  AI rate-limit/offline/retry.
- **Prompt AI diperkuat**: larangan netting eksplisit + contoh campuran
  pemasukan/pengeluaran + `parseAiAmount` menerima nominal String ("Rp 200.000",
  "200rb") dari respons AI — sebelumnya `optDouble` menolak string → transaksi
  hilang diam-diam.
- **Test**: `MultiTransactionExtractionTest` (+21) kasus wajib user + stress
  variasi chat Indonesia + regresi perilaku lama; `GeminiServiceHeuristicParseTest`
  diperbarui (multi-nominal kini 2 transaksi terpisah, bukan 1). Audit lanjutan
  menemukan & menutup 2 regresi: (1) angka panjang polos (nomor rekening/
  telepon, ≥10 digit) dipecah jadi transaksi palsu miliaran oleh strategi batas
  nominal — `isImplausiblePlainNumber` menolaknya di ekstraksi, pemisahan, &
  penghitungan (+4 test regresi); (2) migrasi Room v11→v12 (`detectedCount`)
  belum punya test — `migrate11To12_addsDetectedCount` ditambahkan.
- **Uji ketangguhan + perbaikan parser** (stress test 2026-08-14):
  `TransactionExtractionStressTest` (+16) variasi ekstrem menemukan 2 kelas
  masalah nyata yang diperbaiki: (1) segmen hasil split separator yang MASIH
  memuat ≥2 nominal ("bensin 30rb jajan 20rb") di-parse utuh → transaksi kedua
  hilang — kini dipecah ulang per batas nominal (`splitTransactionSegments`
  flatMap); (2) kata Indonesia umum yang lolos dari trigger heuristik sehingga
  transaksi nyata hilang — "jualan" (pemasukan usaha), "kopi", "jajan",
  "renovasi", "upgrade" (pengeluaran) ditambahkan ke daftar trigger + kategori.
  Total suite 397 → 439 test / 43 file.
- **Uji akurasi AI & sistem offline** (2026-08-14): `AiAccuracyTest` (+16)
  menutup celah audit — jalur AI `parseJsonResponse` TIDAK punya test langsung
  (semua test lama hanya heuristik offline). Kini diuji: format baru (array
  multi-transaksi), format lama (field tunggal), nominal String Indonesia dari
  AI ("Rp 200.000", "50rb", "1,5jt"), tanggal eksplisit, nominal ≤0 dibuang,
  kategori valid vs diarang, containsTransaction=false, JSON rusak → null,
  code fence markdown, `wrapOpenAiText`, `isAiAvailable` tanpa kunci (offline
  murni), deteksi balasan offline, & backup heuristik. Verifikasi E2E di
  emulator dengan AI offline: pesan multi-transaksi direkap via heuristik
  (badge "5 transaksi · Rp5.655.000" = gaji +5jt, bensin −100rb, jualan online
  +300rb, listrik −250rb, gorengan −5rb — tanpa netting) & tersinkron ke
  Firestore dengan `detectedCount`. Total suite 439 → 455 test / 44 file.


### Added
- **Avatar bubble chat memakai foto profil asli** (permintaan user 2026-08-14):
  URL foto Google anggota disimpan di member doc (`photoUrl`, konstanta baru
  `Constants.Fields.PHOTO_URL`) sejak join/connect/approve — device lain kini
  bisa menampilkan foto asli via fallback `photoUrl` saat `avatarBytes` belum
  pernah di-sync (sebelumnya hanya inisial). Logika sumber avatar diekstrak ke
  fungsi murni `decideAvatarSource` (prioritas: avatarBytes → photoUrl Google →
  skip) + `shouldRefreshPhotoUrl` (backfill member lama saat connect) —
  `MembershipAvatarSourceTest` (+11 test). Rules Firestore mengizinkan
  self-update `photoUrl`; backfill otomatis di snapshot listener members
  (satu update ter-target, konvergen) sehingga member doc lama ikut terisi.
  Fallback inisial tetap dipakai HANYA bila pengguna belum punya foto sama
  sekali (avatarBytes + photoUrl keduanya tidak ada) — `AvatarImageFallbackTest`
  (+4 test: null path → inisial, path rusak → inisial, huruf pertama uppercase,
  warna deterministik per nama).

### Changed
- **Indikator "AI memproses" minimalis** (permintaan user 2026-08-14):
  bubble "AI sedang memproses..." yang terpisah di sisi chat DIGANTI ikon
  spark kecil (✨ AutoAwesome, 13dp, pulse alpha halus) di pojok footer bubble
  pesan milik user yang sedang diproses AI. Indikator hanya di bubble terakhir
  milik user (pesan anggota lain yang tiba belakangan tidak diindikasi),
  menghormati reduced-motion (statis), label aksesibilitas tetap
  "AI sedang memproses..." (+3 Compose UI test `AiProcessingIndicatorTest`).

### Fixed
- **Snackbar menimpa ikon top bar** (audit 2026-08-14): snackbar (mis.
  "Tercatat" transaksi) di-`align(TopCenter)` + 8dp menimpa ikon Kelola
  Anggota/Settings di fase Main. Kini di-offset ke bawah TopAppBar (~72dp)
  saat top bar tampil (`MainOverlays.snackbarBelowTopBar`); di layar PIN
  tetap dekat status bar. Posisi atas dipertahankan (alasan historis:
  BottomCenter+imePadding menutupi kolom ketik).
- **Auto-connect nyangkut setelah logout biasa** (audit 2026-08-14): logout
  biasa mempertahankan PIN di Keystore (desain r1.4.0), tapi saat login ulang
  guard lama (`workspacePin != ws.pin`) melewatkan `connectToWorkspace` karena
  PIN sudah cocok — `userName` tetap null sehingga user stuck di layar PIN
  walau akun terikat 1 workspace. Logika keputusan diekstrak ke fungsi murni
  `MembershipManager.resolveAutoConnect` (0/1/>1 workspace, termasuk resume
  workspace aktif setelah logout) + `MembershipAutoConnectTest` (+11 test).

### Added
- **Auto-connect**: login Google otomatis masuk ke workspace milik akun TANPA
  PIN — model 1 akun = 1 workspace aktif. Query `collectionGroup members by uid`
  (`MembershipManager.discoverMyWorkspaces`) + index `firestore.indexes.json`;
  PIN lokal tetap dipakai sebagai fast path offline-first, lalu divalidasi
  terhadap cloud (PIN basi dari workspace yang sudah di-kick dibersihkan).
- **Keluar dari Workspace** (Settings → Zona Berbahaya): lepaskan diri dari
  workspace — akun tetap login Google, data lokal dihapus, data cloud anggota
  lain aman. Owner SATU-SATUNYA ditolak (wajib promote anggota lain jadi
  owner dulu — guard anti-yatim `canLeaveWorkspace`, +4 unit test).
- **Picker workspace**: akun lama yang terikat >1 workspace (dulu tidak ada
  fitur keluar) memilih workspace saat login.
- `firestore.rules`: siapa pun boleh menghapus doc member-nya sendiri
  (self-leave; guard anti-yatim di sisi app karena rules tidak bisa query).

### Fixed
- **Bug (login ulang akun sama)**: workspace hilang karena logout biasa
  menghapus WORKSPACE_PIN & API key dari Keystore. Kini logout biasa hanya
  mereset identitas akun sesi — PIN & key BYOK dipertahankan, login ulang
  langsung kembali ke workspace (via auto-connect).

### Changed
- `performLogoutCleanup` tidak lagi `clearAll` prefs + Keystore (hanya
  identitas akun).

## [r1.3.0] - 2026-08-14 (audit menyeluruh semua lapisan + test + docs)

> Rilis audit: semua lapisan (data, ui, screens, res, root, test) diaudit &
> disempurnakan — 364 test hijau, lint bersih, peta struktur/navigasi/alur
> data di docs. Versi r1.3.0 / versionCode 28 (naik dari r1.2.0/27).

### Added
- **Unit test `MainViewModel` — state machine UI (2026-08-13)**: 12 test
  Robolectric + Room in-memory (di-inject ke singleton `AppDatabase.INSTANCE`
  via refleksi, tanpa mengubah kode produksi) mengunci perilaku inti:
  - **Undo hapus**: `deleteTransaction` memicu event `DeleteUndo` →
    `undoDelete` memulihkan transaksi dengan `cloudId` SAMA (tanpa duplikat
    cloud); `emitUndo=false` TIDAK mengirim event (anti snackbar ganda);
    hapus pesan finansial mengembalikan pesan + SEMUA transaksi terkait,
    undo memulihkan keduanya dengan relasi dijaga ulang & badge dihitung
    ulang; `addManualTransaction` memicu event snackbar "Tercatat".
  - **Clear data**: `clearAllData` mengosongkan pesan/transaksi + membuang
    op sync lama, menyisakan HANYA op `CLEAR_FAMILY` (desain: cloud ikut
    dibersihkan saat online); `clearLocalData` mengosongkan data lokal +
    antrian pending (anti-replay workspace).
  - **AI report**: generate/dismiss audit & bulanan (loading lifecycle benar);
    **jalur error** — AI service tiruan (`FailingAiService`) yang melempar
    di `auditReport`/`monthlyAnalysis` → `is*Error=true` + teks dari
    `R.string.rekap_*_failed` + dismiss tetap berfungsi.
  - **Testability**: `FinanceAiService` & `MainViewModel` kini `open`;
    repository dibuat lewat factory `MainViewModel.createRepository()` yang
    bisa di-override — selaras dengan niat P3-1 ("dependency injectable")
    yang sebelumnya tidak mungkin karena class `final`.
- **Reduced-motion (audit motion 2026-08-12)**: hormati pengaturan sistem
  "Hapus animasi" (`ANIMATOR_DURATION_SCALE=0`) — `Motion.reducedMotion`
  di-baca sekali dari `MainActivity.onCreate` (`Motion.applySystemSetting`);
  saat aktif, semua tween snap ke 0ms, spring gesture dipersingkat ke settle
  instan via `Motion.springOrSnap` (FAB, geser chips, snap-back swipe bubble),
  stagger chip saran tanpa delay (`Motion.stagger(index)`). Aksesibilitas:
  animasi tidak lagi wajib dipahami — +6 unit test `MotionReducedMotionTest`.
- **Clean-up repo & lint (2026-08-12)**:
  - Hapus 13 laporan/rencana lama di `.artifact/` (sudah selesai dieksekusi,
    isinya terekam di CHANGELOG) + `logo.svg`/`metadata.json` yang tidak
    direferensikan; `.artifact/` masuk `.gitignore` agar artefak kerja AI
    tidak pernah di-commit lagi; referensi `.artifact/live_shots/` di
    CHANGELOG dibersihkan.
  - Lint: tangani `NoCredentialException` (perangkat tanpa akun Google →
    pesan ramah baru `google_err_no_credential`, lint
    `CredentialManagerMisuse` bersih); hapus `android:label` redundan di
    MainActivity (lint `RedundantLabel`); hapus 30 resource unused
    (7 warna template default + 23 string legacy).
  - `lintDebug`: 0 error (sisa hanya informational: saran KTX/typo/versi).
- **Sistem Profil & Akun (2026-08-11)**: kartu profil teratas di Settings
  kini clickable membuka halaman profil (foto, nama, email, status akun).
  Foto profil default dari akun Google, bisa diganti foto custom
  (Galeri/Kamera), di-reset kembali ke Google; pilihan persistent (tidak
  kembali ke Google saat app dibuka ulang). Nama user bisa diubah — tidak
  kosong, langsung berlaku di seluruh aplikasi, persistent, dan tidak lagi
  ditimpa otomatis oleh nama Google (Google hanya nama awal).
- **+9 kategori transaksi (2026-08-11)**: 5 pemasukan & 4 pengeluaran baru —
  pemasukan tidak lagi hanya 'Gaji & Pemasukan'.
- **Sinkronisasi avatar antar perangkat + warna unik per anggota (P0+P1)**:
  `AvatarStore` — mesin kompresi avatar (lokal 256px/85, cloud 128px/72
  ≈3-10KB) + cache avatar per uid+version; `FamilyMember.avatarVersion`,
  `uploadMyAvatar` (Blob ke Firestore, `FieldValue.delete` untuk reset),
  map nama→path foto; I/O di `Dispatchers.IO` (anti-jank) + guard versi
  untuk race snapshot.
- **Background grid halus di area chat (2026-08-11)**: tekstur kotak-kotak
  subtle 32dp (garis 0.5dp, alpha sangat rendah) — area chat tidak polos,
  tapi grid tidak mencolok; tetap di belakang semua elemen. Dark mode
  memakai `surfaceVariant` (hue menyatu dengan palet gelap, bukan White
  murni yang nyaris seterang surface saat di-blend).

### Fixed
- **Komentar `DismissibleSnackbar` menyesatkan (2026-08-13)**: klaim "dismiss
  saat jarak ≥72dp ATAU kecepatan ≥2000px/s" — implementasi hanya memeriksa
  jarak (tidak ada perhitungan kecepatan). Komentar dikoreksi agar jujur +
  catatan audit: kecepatan sengaja TIDAK ditambahkan supaya flick cepat
  berjarak pendek tidak men-dismiss snackbar secara tak sengaja. Perilaku
  tidak berubah (ditemukan saat audit `MainOverlays.kt`).
- **Konstanta `Fields` pesan/transaksi mati di-wire + test keselarasan DTO
  (2026-08-13)**: 17 konstanta `Constants.Fields` (cloudId, sender, type, dll.)
  dideklarasikan tapi TIDAK pernah direferensikan — `FirestoreSyncManager` &
  `DataExporter` menulis nama field sebagai literal mentah, jadi konstanta
  mengkhianati tujuannya (rename tidak merambat → risiko divergence diam-diam
  yang bisa merusak data lintas perangkat). Kini literal diganti
  `Constants.Fields.*` di kedua jalur (write map pesan/transaksi, enqueue &
  parse antrian delete, serialisasi & parse JSON backup/pending op) + 3
  konstanta baru (`detectedBy`, `serverUpdatedAt`, `sourceMessageCloudId`).
  Nilai identik dengan literal lama → format cloud/backup TIDAK berubah
  (kompatibel data lama). Test baru `ConstantsTest` (12 test): mengunci kontrak
  nilai semua Fields/koleksi/peran/pref/kategori, dan memverifikasi keselarasan
  dengan DTO lewat `CustomClassMapper` (jalur serialisasi Firestore yang sama
  dengan `toObject()`) — setiap field `CloudMessage`/`CloudTransaction` yang
  diserialisasi harus punya konstanta bernilai sama, plus anotasi
  `@PropertyName("isFinancial")` konsisten dengan `Constants.Fields.IS_FINANCIAL`
  (regresi BUG-1).
- **Motion Kelola Anggota & Pengaturan diseragamkan (2026-08-12)**:
  - **Kelola Anggota** diubah dari Dialog full-screen (muncul fade/zoom) menjadi
    **ModalBottomSheet** — muncul slide dari bawah, shape 24dp,
    `skipPartiallyExpanded = true`, dan tutup via `sheetState.hide()`
    (jendela turun ke bawah) — satu motion language dengan SettingsSheet.
  - **SettingsSheet** kini menutup lewat pola `dismiss()` = `sheetState.hide()`
    lalu `onDismiss()` — sebelumnya `onDismissRequest` langsung memanggil
    `onDismiss` sehingga sheet hilang instan tanpa animasi turun. Sekarang
    swipe ke bawah / tap scrim / back → sheet turun ke bawah dengan mulus.
  - Wrapper AnimatedVisibility fade/zoom utk Kelola Anggota di MainOverlays
    dihapus (sheet punya animasi bawaan). Terverifikasi runtime emulator:
    buka/tutup kedua menu tanpa crash.
  - **Audit menu Pengaturan — penataan ulang 6 seksi (2026-08-12)**:
    - Struktur baru: **Tampilan** (Mode, Notifikasi) · **Keamanan** (PIN
    Workspace) · **AI & API** · **Data & Backup** · **Tentang** (Periksa
    Update, Kebijakan Privasi, Versi) · **Zona Berbahaya** — sebelumnya 4
    seksi tanpa keamanan/tentang, PIN & Export CSV tercampur di Data.
    - Versi pindah dari header ke seksi Tentang; status "Backup terakhir"
    digabung jadi subtitle baris Backup (hemat 1 baris, hilangkan ikon
    duplikat CloudUpload).
    - Baris navigasi (PIN, Kunci API, Periksa Update, Kebijakan Privasi)
    kini menampilkan **chevron** sebagai affordance — sebelumnya hanya kartu
    profil yang punya, baris lain tidak terlihat bisa di-tap.
    - Tinggi sheet dibatasi `heightIn(max = 640dp)` — sebelumnya konten
    ~2310px di layar 2400px (hampir full-screen); kini konsisten dengan
    ManageMembers (560dp) dan sisa layar terlihat sebagai scrim.
    - Baris baru **Kebijakan Privasi** membuka PRIVACY_POLICY.md di browser
    (wajib untuk rilis Play Store). Terverifikasi runtime emulator: struktur
    baru tampil, scroll berfungsi, kebijakan privasi membuka Chrome, tutup
    via swipe — 0 crash.
  - **Audit lanjutan — 4 sheet lain diseragamkan ke pola dismiss yang sama**
    (AddTransactionDialog, AiReportDialog, ProfileAccountSheet,
    ChatAttachmentSheet): `onDismissRequest` kini lewat `dismiss()` =
    `sheetState.hide()` lalu `onDismiss()` — sebelumnya gesture
    swipe/scrim/back menutup instan tanpa animasi turun, tidak konsisten
    dengan tombol internal. Tombol internal AiReportDialog di-DRY-kan ke
    `::dismiss`. Header ManageMembers padding disamakan ke 20/8 dengen
    SettingsSheet. Terverifikasi runtime: buka/tutup semua sheet tanpa crash.
- **Audit bug & potential bug (2026-08-12) — double-submit Simpan transaksi**:
  tombol Simpan di dialog tambah/edit transaksi memanggil `onConfirm` (insert
  DB, fire-and-forget async) lalu `onDismiss` sinkron tanpa guard — dua tap
  cepat bisa mencatat transaksi DUPLIKAT. Kini guard `isSaving` di-set sebelum
  aksi pertama & tombol dinonaktifkan sampai dialog tertutup (`return@Button`
  untuk tap kedua). Terverifikasi runtime di emulator: double-tap Simpan →
  tepat 1 record di DB (bukan 2). Bonus: guard juga menutup double-fire
  `updateTransaction` di mode edit.
- **Audit bug & potential bug (2026-08-12) — `chatResetTrigger` naik 2× per
  simpan**: dialog selalu memanggil `onDismiss()` SETELAH `onConfirm()`, jadi
  increment trigger reset input chat berjalan dua kali per penyimpanan.
  Dipindah ke `onDismiss` saja — perilaku tetap sama (trigger naik sekali per
  penutupan), kode lebih bersih.
- **Audit ketahanan (2026-08-11)**: `isAiThinking` dipakai counter (race
  saat 2 kiriman bersamaan) + hilangkan `!!` NPE di jalur snapshot.
- **Audit r1.2.4 (2026-08-11) — 4 bug chat/AI/heuristik**:
  - "bayar gaji/potong gaji" tidak lagi salah dikenali PEMASUKAN
    (`incomeBlocker` diperluas);
  - "3.5jt" (titik desimal) dibaca **3,5 juta**, bukan 35 juta (`toRupiah`);
  - hapus 1 transaksi dari pesan MULTI → badge dihitung ulang dari sisa,
    tidak hilang total;
  - pertanyaan finansial: timeout/balasan generik AI diganti jawaban
    berbasis data DB. +6 unit test regresi.
- **Audit keanggotaan (2026-08-11, commit `ce6a192`)**:
  - **P1-1**: role owner/member kini SINKRON saat resume & realtime —
    owner yang di-demote dari perangkat lain langsung kehilangan UI owner
    tanpa restart (MainActivity memantau snapshot members → update role +
    prefs + listener joinRequests);
  - **P2-1**: spam re-request setelah DITOLAK diblokir — tombol "Coba
    Lagi" disembunyikan pada kondisi terminal (REJECTED/PIN_OWNED);
  - **P2-2**: pending op tidak di-retry selamanya saat member di-kick —
    op dibuang saat `PERMISSION_DENIED` (drain berhenti membuang kuota);
  - **Keamanan**: kick ≠ logout total — `performKickedCleanup` kembali ke
    layar PIN tanpa sign-out Google & tanpa hapus API key BYOK/avatar;
  - **Identitas**: nama disinkronkan ke member doc (sekali via pref
    `NAME_SYNCED`), label ikut tersinkron hanya bila masih default;
  - P3: dedup `ensureSelfMemberDoc`, hapus `FAMILY_NOT_FOUND` (dead),
    `addedAt` pakai serverTimestamp, filter digit PIN, empty-state owner.
- **Relay aiComplete (server)**: fix crash import logger (`const
  { logger }` → require langsung) + log debug "Relay OK" (model, panjang,
  cuplikan) untuk observability — ditemukan live test HP (cashback shopee
  200 ribu → PEMASUKAN via relay, model gemma-4-26b-a4b-it:free auto-rotate
  saat 429).
- **FAB jump-to-bottom (r1.2.0 lanjutan)**: solid penuh `surfaceVariant`
  (tanpa cincin border) + fix crash 'Padding must be non-negative'
  (clamp `startPadding` dari spring overshoot); kemudian diseragamkan
  dengan chip rekomendasi — outline transparan, pusat sejajar baris chips,
  animasi lebih soft (spring LowBouncy 600f); konstanta
  `FAB_SPRING_STIFFNESS` diekstrak agar FAB & chipShift selalu sinkron;
  reserve tinggi baris chips (56dp) saat FAB tampil tanpa saran cepat —
  FAB tidak lagi menimpa pesan terakhir.
- **Avatar & warna profil tampil di bubble chat (2026-08-12, BUG live)**:
  lingkaran avatar di header pesan sebelumnya pakai latar `alpha 0.16`
  (16% opasitas — nyaris transparan, terbukti via analisis piksel
  `rgb(251,253,249)` = SAMA dengan background, sehingga seolah "profil &
  warna belum muncul"). Kini **solid** mengikuti `avatarColorFor` (8 warna
  unik per orang, konsisten dengan topbar & kartu anggota). Inisial teks
  **adaptif WCAG** (luminance > 0.22 → gelap `#202124`, else putih) dipakai
  di KEDUA jalur — foto (saat gagal decode) & fallback lingkaran inisial
  (sebelumnya jalur foto masih `senderColor` ≈ 3:1 di bawah standar 4.5:1).
  Golden `chat_bubble_income_other` & `chat_bubble_reply` di-record ulang.
- **Shadow bubble di light mode dihapus (2026-08-12)**: bubble chat di mode
  terang terasa "ada bayangan" — konsistensi tanpa shadow kini menyeluruh
  (bubble, pill composer, chip, FAB, preview) di kedua mode.
- **Fix crash approve join request (2026-08-12)**: `IllegalArgumentException:
  Key was already used` saat owner menyetujui permintaan gabung — UID yang
  sama muncul di 2 section LazyColumn (join request belum terhapus sementara
  member baru sudah masuk). Key kini di-prefix per section
  (`join_${uid}` / `member_${uid}`) — unik di seluruh list.

### Changed
- **Dokumentasi proyek (2026-08-13)**: peta proyek baru di `docs/` —
  `STRUCTURE.md` (pohon proyek + statistik), `NAVIGATION.md` (peta
  layar/sheet/dialog + pola konsisten), `DATA_FLOW.md` (alur offline-first
  Room → Firestore → PendingOp); `DEVELOPER.md` menautkan ketiganya di seksi
  "Peta Proyek" — orientasi repo cepat tanpa membaca kode.
- **Audit & rapikan SEMUA animasi (2026-08-11)**: satu motion language
  terpusat di `ui/theme/Motion.kt` — QUICK 150ms (dismiss), FAST 200ms
  (composer/toggle), BASE 250ms (chip/navbar/snackbar), NAV 300ms
  (navigasi tab); semua tween memakai FastOutSlowIn (ringan, natural,
  tanpa bounce di elemen layout). Spring hanya untuk gesture: FAB &
  geser chips (LowBouncy 600f) dan swipe-reply bubble.
- **Tuning AI pencatatan keuangan r1.2.4 (2026-08-11)**: schema AI baru
  `transactions[]` (1 pesan bisa N transaksi) + `date` eksplisit; format
  lama tetap diterima (backward compat); kategori AI dipaksa ke daftar
  valid (`normalizeCategory`); heuristik offline: parse multi-segment aman
  (split koma/dan/sama + fallback), guard reminder/rencana; AI menjawab
  pertanyaan finansial BERDASARKAN data DB (bukan mengarang).
- **Layout pesan media gaya WhatsApp/Telegram (2026-08-11)**: gambar jadi
  bubble edge-to-edge (tanpa "frame di dalam frame" / padding besar);
  `AttachedFileCard` diekstrak (DRY, dipakai jalur teks & media), guard
  aspectRatio rasio ekstrem, quote media pakai aksen garis kiri, hapus
  blok gambar dead-code di jalur teks.
- **Polish composer & area chat (2026-08-12)**:
  - Chip rekomendasi & FAB diberi **fill** (satu keluarga dengan pill
    composer) + animasi masuk **kereta dari kanan** (slide + fade,
    stagger 45ms/chip);
  - **Batas scroll chat turun ke kolom input** (Telegram-style): daftar
    pesan full-height, chips & FAB jadi OVERLAY melayang di atas pesan
    dengan fill **memudar** (`CHIP_FILL_ALPHA` 0.75 dark / 0.45 light)
    sehingga pesan yang lewat di belakangnya tetap samar terbaca;
    contentPadding bottom dinamis & di-animasi (`CHIP_ROW_HEIGHT + 8dp`
    saat draf kosong / 16dp saat mengetik) — tanpa layout jump.
  - Audit kecil: `isDark` via token semantik (single source), shadow
    preview dihapus (konsistensi tanpa shadow), `NumberFormat` di-remember;
    urutan import ASCII; aksen bar quote media pakai
    `IntrinsicSize.Min` mengikuti tinggi konten.

- **Redesign jendela Backup & Restore (2026-08-12, audit live HP)**: dialog
  progres backup kini informatif — ikon 22dp + judul + detail, tombol lebar;
  daftar file di dialog Restore dirapikan: badge "Terbaru" untuk file
  terakhir + badge 🔒 "Terenkripsi" + waktu format lokal per file (lebih
  jelas & konsisten dengan chip UI lain). +unit test `BackupDialogsTest`.
- **Motion bottom sheet satu bahasa (2026-08-12, audit motion)**: sheet
  lampiran (`ChatAttachmentSheet`) kini `skipPartiallyExpanded = true` —
  langsung buka penuh dari bawah, konsisten dengan 4 sheet lain (Settings,
  Catat Transaksi, AI Report, Profil & Akun). Tidak ada lagi sheet yang bisa
  berhenti di posisi setengah.

### CI / Infra
- `deploy-functions.yml`: tambah `--force` agar cleanup policy artifact
  diset otomatis (tanpa ini deploy berakhir non-zero walau fungsi sukses),
  catatan WIF (resource wajib di proyek `nyachat-in`, bukan
  `ngodingsendiri-note`) + trigger deploy ulang.

### Added
- **Viewer foto full-screen + zoom/pan (2026-08-13, permintaan user)**: sentuh
  SEKALI pada bubble gambar membuka foto diperbesar (bukan menu). Gestur gaya
  galeri: **pinch** zoom 1×–5× (titik di bawah jari stabil), **geser** pan
  (di-clamp ke tepi konten, otomatis pusat saat 1×), **double-tap** toggle
  2.5×/1×, **tap / ✕** tutup. Dekode 2200px (lebih tajam dari bubble 1100px)
  via `BitmapCache`. Logika transform diekstrak ke fungsi murni
  `applyZoomPan` + persentase zoom di-announce TalkBack (`stateDescription`).
  +10 unit test `ImageViewerDialogTest` (pinch nyata via `performTouchInput`,
  double-tap, tap, clamp/centroid-stabil).
- **Uji otomatis gestur bubble untuk CI (2026-08-13)**: `ChatBubbleGestureTest`
  (6 test — tap vs tahan lama bubble teks/gambar, geser balas, badge) &
  `ChatScreenGestureTest` (4 test integrasi — menu Dropdown muncul hanya via
  tahan lama, tap gambar buka viewer) — Robolectric, jalan di step
  `testDebugUnitTest` workflow `build-apk.yml`.

### Fixed
- **Gestur bubble chat (2026-08-13, permintaan user)**: sentuh SEKALI tidak
  lagi membuka menu — menu hanya lewat **TAHAN LAMA**; bubble gambar: sentuh
  sekali membuka viewer foto, tahan lama menu. TalkBack/keyboard tetap punya
  jalur ke menu lewat custom accessibility action "Buka menu pesan".
  Sebelumnya `combinedClickable(onClick = menu)` membuat tap = menu dan tidak
  ada cara memperbesar foto lampiran.
- **Audit lapisan `local/` — Room & penyimpanan lokal (2026-08-13)**:
  - **Dead code dihapus**: `TransactionDao.getByChatMessageId` (LIMIT 1 —
    tanpa pemanggil; sistem sudah multi-transaksi via `getAllByChatMessageId`)
    & `AvatarStore.saveAvatar`/`getAvatarPath`/`deleteAvatar`/`keyHash`/`fileFor`
    (sistem avatar sudah pindah ke `custom.jpg` + member cache — 0 pemanggil
    eksternal; risiko collision `String.hashCode()` ikut hilang);
  - **Keystore off main thread**: 4 tulis `SecureStorage` sinkron (logout,
    set PIN, API key ×2) → varian `*Async` via `scope.launch`; anotasi
    `@MainThread` yang basi dihapus — hardware-backed key tidak lagi memblokir
    main thread, konsisten dengan semua `getSecret` yang sudah async;
  - `cacheGooglePhoto` kini **sample-decode** (bounds → `inSampleSize`,
    sebelumnya decode penuh 12MP+ ≈ 48MB alokasi transien per foto);
  - Cache avatar anggota dibersihkan: versi lama `member_<uid>_*` dihapus
    saat versi baru disimpan (sebelumnya menumpuk selamanya).
- **Audit lapisan `remote/` — Firebase & layanan eksternal (2026-08-13)**:
  - 🔴 **BUG: `deleteAllAttachments` tidak rekursif** — lampiran per-workspace
    (`attachments/<pin>/`, M9) tidak pernah terhapus saat clear data/logout
    (`File.delete()` pada direktori non-kosong gagal diam-diam) → storage
    leak + sisa privasi di disk. Kini `deleteRecursively()` +
    `deleteWorkspaceAttachments` ikut rekursif + **3 test regresi**
    (`ImageFileUtilAttachmentCleanupTest`);
  - **Repo GitHub diduplikasi** — `Constants.Links.REPO` (URL penuh) vs
    `GitHubUpdateChecker.REPO` (path literal) → rename tidak merambat, update
    checker 404 diam-diam. Kini `Constants.Links.GITHUB_OWNER_REPO` satu
    sumber kebenaran; `REPO` & `API_URL` diturunkan dari sana + asersi
    konsistensi di `ConstantsTest`;
  - KDoc ganda `MembershipManager.uploadMyAvatar` digabung; 2 import mati
    dihapus (`Job`, `withContext`);
  - Test flaky `MainViewModelTest` (race badge: state dibaca sebelum badge
    mendarat) diperbaiki dengan `awaitTrue` — deterministik.
- **Audit lapisan `backup/` — Drive terenkripsi + CSV (2026-08-13)**:
  `DriveBackupController.showMessage()` = dead code (setter publik tanpa satu
  pun pemanggil — produksi menulis `_message.value` langsung, UI hanya
  meng-koleksi flow) dihapus + komentar audit.
- **Tutup celah audit lapisan test (2026-08-13)**:
  - **`RekapScreenStateTest` baru (+6 test, JUnit murni)**: default state,
    `Saver` round-trip (tab/bulan/kategori bertahan), `pendingDelete`
    sengaja TIDAK disimpan (dialog transien), validasi restore (tab di luar
    0..2 ditolak, bulan invalid ditolak, kategori kosong ditolak, list
    kosong/pendek tidak crash dan isi yang hilang jadi default). Pemanggilan
    `Saver.save` memakai `Saver.run { scope.save(...) }` — bentuk member
    extension yang valid (receiver `SaverScope` wajib).
  - **Asersi lemah diganti di `ImageFileUtilAttachmentCleanupTest`**:
    `assertTrue(true)` (test "no-crash") diganti asersi bermakna — delete
    tanpa lampiran tidak membuat folder baru di disk, dan +1 test baru:
    workspace blank → early return tanpa efek samping.
- **Audit lapisan test `data/` — paritas kategori (2026-08-14)**:
  - **Celah `ConstantsTest` ditutup**: `Constants.Categories` punya 18
    konstanta (12 pengeluaran + 6 pemasukan) tapi test hanya mengunci 4 inti
    + cek duplikat — konstanta baru yang lupa dimasukkan ke
    `EXPENSE_ALL`/`INCOME_ALL`/`ALL` akan hilang dari dropdown UI tanpa
    terdeteksi. Kini ada paritas dua arah: `ALL.toSet()` harus sama persis
    dengan ke-18 konstanta, plus `EXPENSE_ALL ∩ INCOME_ALL` kosong (tidak
    boleh bocor antar jenis). +1 test (`setiap konstanta kategori tercakup di
    daftar ALL`);
  - Diaudit & dinyatakan sehat: seluruh 22 file test `data/` (3.773+ baris,
    nol TODO/`!!`) — semua punya asersi bermakna, 3 `assertEquals(true, x)`
    adalah gaya (nilai boolean nyata), bukan `assertTrue(true)` kosong;
    `ConstantsTest` tetap mengunci 32/32 Fields + DTO CloudMessage/
    CloudTransaction via `CustomClassMapper` + `@PropertyName isFinancial`
    anti-BUG-1.
- **Audit lapisan test `ui/` — dinyatakan sehat (2026-08-14)**:
  13 class / 93 test (1.853 baris), nol TODO/`!!`, nol `@Ignore`/`assumeTrue`
  — semua ter-execute penuh di CI. `MainViewModelTest` (13) meng-cover state
  machine undo hapus (cloudId dipertahankan, relasi pesan→transaksi dijaga
  ulang, emitUndo=false anti-snackbar-ganda), clear data (hanya op
  CLEAR_FAMILY tersisa saat offline — desain), AI report loading/error/dismiss
  (teks error dari resource, bukan literal), indikator AI berpikir via
  `DelayedAiService` (parse 800ms). `AppSnapshotTest` (11 golden Roborazzi
  via `captureRoboImage` + `mainClock.advanceTimeBy` untuk settle animasi)
  benar-benar diverifikasi di CI runner (verify default, record opsional).
  `AiThinkingCounterTest` uji thread-safety 8 thread; `MotionReducedMotionTest`
  snap 0ms + `springOrSnap` → `TweenSpec`; `DateLabelsTest` 10 murni;
  `RekapScreenStateTest` Saver round-trip; gesture test tap vs tahan vs swipe.
  Nol asersi lemah — `assertTrue(x == 0)` di AiThinkingCounterTest hanyalah
  gaya, bukan `assertTrue(true)` kosong.
- **Audit root proyek (2026-08-14)**:
  - **Fallback versi usang di `app/build.gradle.kts`** — `appVersion`/`appVersionCode`
    fallback literal `r1.1.3`/`26` padahal `gradle.properties` (satu sumber
    kebenaran L11) menetapkan `r1.3.0`/`28`; workflow build-apk.yml bahkan
    mendokumentasikan fallback ini sebagai jalur kompatibilitas → build diam-diam
    bisa memakai versi usang jika property hilang. Fallback disinkronkan ke
    `r1.3.0`/`28` + komentar "wajib sinkron dengan gradle.properties";
  - **Bug laten `BuildConfig.TEMPLATE = ;`** — Secrets plugin mem-parse baris
    `[TEMPLATE]` di `.env.example` sebagai key bernilai kosong → error kompilasi
    `illegal start of expression` saat BuildConfig di-regenerate. Baris section
    header dihapus (muncul setelah edit build.gradle.kts memicu regenerasi);
  - **`.env.example` menyesatkan** — klaim AI Studio "key will be packaged in the
    APK" kontradiktif dengan desain BYOK (app/build.gradle.kts: "TIDAK ada API
    key AI yang dikompilasi ke APK"). Komentar ditulis ulang jujur: placeholder
    `MY_GEMINI_API_KEY` sengaja dipertahankan (di-skip GeminiService);
  - **`securityCrypto` dead di version catalog** — entry version masih ada
    padahal library `androidx-security-crypto` sudah di-comment (migrasi ke
    SecureStorage/Keystore). Dihapus;
  - Komentar workflow `build-apk.yml` "tag v*" dikoreksi jadi "tag r*" (aktual).
  - Diaudit & dinyatakan sehat: `firestore.rules` (188 baris, aturan lengkap —
    anti-squatting PIN, rate-limit join request sisi server, schema validation
    messages/transactions, guard self-demote owner), lint rules lulus tanpa
    warning, `functions/index.js` (relay AI + FCM multicast + cleanup token
    invalid, nol TODO), `deploy-functions.yml` (WIF tanpa kunci JSON),
    `settings.gradle.kts`/`gradle.properties`/`roborazzi.properties`/
    `.gitignore`/`debug.keystore` (di-commit sengaja, SHA-1 stabil).
- **Audit root proyek pass 2 — dokumen & konsistensi (2026-08-14)**:
  - **`docs/STRUCTURE.md` usang**: ±25.065 → **±25.094** (19.170 produksi +
    5.924 test — selisih 29 baris dari test baru sesi ini);
  - **Contoh override menyesatkan di `docs/DEVELOPER.md`**:
    `-PappVersionCode=26` menurunkan versionCode (26 < 27 aktual) — Play Store
    menolak versionCode menurun. Contoh diubah jadi `28` + catatan "harus selalu
    naik";
  - Diaudit & dinyatakan sehat: `README.md` (badge versi r1.3.0 sinkron dgn
    gradle.properties), `PRIVACY_POLICY.md` (113 baris), `PLAY_STORE_CHECKLIST.md`
    (r1.3.0/28, secrets keystore, smoke test M7/M9), `backup-encryption.md`
    (600k iterasi PBKDF2 / cap 10.000.000 — persis `BackupCrypto.kt`),
    `package-lock.json` sinkron dgn `package.json`, eslint v10.8.0 jalan,
    `functions/package-lock.json` ada (dipakai `cache-dependency-path` workflow),
    `.firebaserc` (`nyachat-in`) konsisten dgn `deploy-functions.yml`.
- **Audit alur data lintas-lapisan (2026-08-14)**:
  - **Diagram "Alur Data Singkat" di `docs/STRUCTURE.md` tidak akurat**: backup
    digambar di bawah `FinanceRepository`, padahal aktualnya backup adalah jalur
    TERPISAH — `MainActivity` → `DriveBackupController` → `DataExporter`/
    `BackupCrypto` → `DriveBackupManager`, dengan JSON dari
    `MainViewModel.buildBackupJson` (hanya membaca state ViewModel) dan restore
    menulis lewat `repository.restoreBackup()`. Diagram dikoreksi;
  - Alur diverifikasi & dinyatakan sehat: `FinanceRepository` benar satu gerbang
    (DAO hanya diakses di `createRepository` wiring; Firestore/Drive terkapsulasi
    di `data/`), AI via `FinanceAiService` → `GeminiService` BYOK (kaskade
    OpenRouter/relay — audit remote/), sync `SyncLifecycle:90` → `startCloudSync`
    → `FirestoreSyncManager.start`, restore `restoreParsedBackup` →
    `repository.restoreBackup` (ganti data + `deleteAbsentFromBackup`), dan
    konsistensi 2 arah pesan⇄transaksi (badge multi-tx, undo dengan cloudId sama).
- **Audit lapisan `analytics/` — Rekap & insight (2026-08-13)**:
  - **Dead code dihapus**: `WeeklyInsights.groupByWeek` + `WeeklySummary`
    (data class + `savingsRate`) — API publik tanpa SATU pun pemanggil
    produksi (hanya test; konsumen nyata hanya `generateInsights` via
    `MainViewModel.weeklyInsights`). 2 test terkait ikut dihapus; guard
    pembagian-nol `savingsRate` (tanpa pemasukan → 0) dipindah ke
    `FinancialInsightsTest` agar formula tetap ter-cover;
  - `WeeklyInsights.weekStartOf` dijadikan `internal` (hanya dipakai internal
    `generateInsights` + test) — konsisten dengan `formatRupiah`;
  - Diaudit & dinyatakan sehat: `FinancialInsightsEngine` (compute/
    describeForPrompt/trendText — dipakai GeminiService sebagai konteks
    prompt AI + laporan offline), `MonthlyAnalytics` (groupByMonth →
    GeminiService:484, topExpenseCategory → GeminiService chained, isSameMonth
    → RekapScreen ×2), `WeeklyInsights.generateInsights` (MainViewModel).
    Semua fungsi murni, waktu di-inject, nol TODO/`!!`.
- **Audit lapisan `repository/` — FinanceRepository (2026-08-14)**:
  - **Return menyesatkan dihapus**: `deleteTransaction` mengembalikan
    `FinancialTransaction?` yang TAK PERNAH null dan tidak dipakai satu pun
    pemanggil (UNDO dibangun dari argumen di MainViewModel, bukan dari return)
    → dijadikan `Unit` + KDoc diperbaiki;
  - **Kontradiksi KDoc dibereskan**: FQN `GeminiService.isFinancialQuestion` /
    `isAiAvailable` di FinanceRepository bertentangan dengan KDoc file "semua
    panggilan lewat FinanceAiService" → kedua gate dipindah sebagai delegasi
    `open fun` di `FinanceAiService` (konsisten dengan arsitektur P3-1, bisa
    di-mock di test);
  - **Literal duplikat dihapus**: `"mode AI sedang offline"` diduplikasi
    (GeminiService balasan offline vs FinanceRepository deteksi fallback) →
    `GeminiService.OFFLINE_REPLY_MARKER` SATU sumber kebenaran + helper
    `isOfflineFallbackReply` (diakses repository lewat FinanceAiService).
    `offlineChatReply` dijadikan `internal` agar marker ter-uji. **+2 test**
    di `AiTuningAuditTest` (deteksi marker + marker terkandung dalam balasan
    offline asli);
  - **Efisiensi `restoreDeleted`**: badge pesan multi-transaksi dihitung ulang
    + disinkronkan N× (per transaksi) → SEKALI per pesan setelah semua insert
    (`linkedSetOf` id pesan terdampak) — hasil identik, N× write cloud dihemat.
- **Audit lapisan `ui/` — state & wiring (2026-08-14)**:
  - **Inkonsistensi indikator AI diperbaiki**: `MainViewModel.editMessage`
    menjalankan parse AI ulang di repository (termasuk foto nota) TAPI tanpa
    `setAiThinking` — berbeda dari `sendMessage`/`askAiInChat`. Indikator
    "AI berpikir" kini menyala selama edit dan mati di `finally` (counter
    tetap aman untuk kirim beruntun). **+1 test regresi** di
    `MainViewModelTest` (AI service dengan delay 800ms: indikator menyala
    saat edit berjalan → mati setelah selesai, tidak stuck);
  - Diaudit & dinyatakan sehat: `SyncLifecycle` (start/stop sync, BYOK,
    update throttle 1 jam, auto-backup 24 jam, pause/resume listener
    P2-12, re-check keanggotaan A3), `MainDialogController` (17 state —
    SEMUA terpakai), `MainCallbacks` (wiring Chat/Rekap, lookup transaksi
    O(1) via Map M8), `MainAppDialogs` & `MainOverlays` (state dialog
    global), `AiThinkingCounter` (AtomicInteger, di-test), `Motion`
    (semua durasi dipakai; `SOFT_ELASTIC_DAMPING` internal `elastic`),
    `AvatarImage` (warna deterministik, WCAG AA), `Theme` (dark/light +
    SemanticColors). Nol dead code, nol TODO/`!!`.
- **Audit ulang lapisan `ui/` — pass 2 (2026-08-14)**:
  - **Dead code dihapus**: 5 konstanta di `theme/Color.kt` tanpa satu pun
    pemakai — `CardBackground`, `TextDark`, `TextMuted` (peninggalan era
    sebelum SemanticColors P2.5) + `WifePinkLight`, `HusbandBlueLight`
    (tint tak pernah dipakai — SemanticColors hanya memakai varian
    utama/dark);
  - **Parameter mati dihapus**: `SyncLifecycleGlue.onLogoutCleanup` dikirim
    dari MainActivity (650) tapi TIDAK PERNAH dipanggil di body glue —
    cleanup logout sesungguhnya lewat `MainAppDialogs.onPerformLogoutCleanup`
    (970). Parameter + argumen dihapus di kedua sisi;
  - Diaudit & dinyatakan sehat: `theme/Type.kt` (Plus Jakarta Sans via
    Downloadable Fonts, fallback font sistem tanpa crash), `theme/Color.kt`
    (23 konstanta tersisa semua terpakai), `theme/SemanticColors.kt`
    (token semantik mode-aware — akses terpusat, bukan luminance manual),
    `SyncLifecycle` (5 parameter tersisa semua terpakai).
- **Audit ulang `data/` — pass lintas-lapisan (2026-08-14)**:
  - **Dead column didokumentasikan**: `ChatMessage.sourceMessageCloudId`
    tidak pernah di-set di runtime (mapping cloud FirestoreSyncManager:454/478
    tidak menyertakannya; hanya versi TRANSaksi yang dipakai cross-device
    lookup). Kolom dipertahankan demi kompatibilitas schema v8→v9 + backup
    JSON lama — KDoc di entity diperjelas ("senjaja tidak diisi", jangan
    dihapus tanpa migrasi v12);
  - Diaudit & dinyatakan sehat: `AppDatabase` (10 migrasi berantai,
    semuanya cocok dengan entity — diverifikasi kolom per kolom;
    MIGRATION_7_8 backup staging duplikat sebelum delete destruktif L1),
    `MigrationTest` (jalur v8→10, 9→10, 10→11 dengan skema historis
    app/schemas — v1–7 tidak punya skema historis, keputusan desain),
    DTO cloud (CloudMessage @get:PropertyName anti-BUG-1, serverUpdatedAt
    Timestamp anti-crash, lampiran sengaja tidak sync), `PendingOp`
    (antrian offline + pembersihan saat ganti workspace),
    `ConstantsTest` (Fields/Collections/Links terkunci),
    `SecureStorage`/`AvatarStore`/`ImageFileUtil` (sehat dari audit local/).
    Nol TODO/`!!`, nol dead code baru.
- **Audit `AndroidManifest.xml` (2026-08-14)**:
  - **Namespace `tools` tak terpakai dihapus** — dideklarasikan tapi tidak
    ada satu pun atribut `tools:` di manifest (hiasan);
  - Diaudit & dinyatakan sehat: 3 permission tepat sasaran (INTERNET,
    POST_NOTIFICATIONS runtime — dipakai MainActivity:227, ACCESS_NETWORK_STATE
    normal BUG-06), `REQUEST_INSTALL_PACKAGES` hanya di debug manifest
    (menghindari review Play sideload), 3 komponen semua `exported` eksplisit
    (launcher true, FCM service & FileProvider false + grantUriPermissions),
    `allowBackup=false` (Keystore PIN/API key tidak boleh ke backup Android),
    nol cleartext HTTP (semua HTTPS — tanpa networkSecurityConfig),
    FileProvider authority cocok dengan 4 pemakaian, FCM service ter-deklarasi
    dengan intent-filter MESSAGING_EVENT, debug manifest terpisah.
- **Audit lapisan `screens/` — 24 layar & komponen (2026-08-14)**:
  - **Duplikasi formatter Rupiah dihapus**: pola identik
    `NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID"))` +
    `maximumFractionDigits = 0` diduplikasi di **7 tempat** (RekapCharts ×3,
    RekapList, RekapScreen, ChatBubbles, MainActivity) — kelas masalah sama
    dengan temuan Fields/REPO: mengubah format berarti mengedit 7 file dan
    mudah melenceng antar layar → `ui/util/CurrencyFormat.kt`
    `idrCurrencyFormat()` SATU sumber kebenaran (instance baru tiap panggilan
    — NumberFormat tidak thread-safe, pola `remember{}` tetap benar).
    **+2 test** di `AmountFormatterTest` (format id-ID tanpa desimal +
    instance baru per panggilan);
  - Diaudit & dinyatakan sehat: `RekapCharts` (donut anti-sweep negatif,
    WCAG dot sinkron, P2-18 ringkasan aksesibel), `RekapList` (swipe
    Edit/Hapus + menu ⋮ untuk keyboard/TalkBack, filter sticky P1.3),
    `RekapScreen` (state di-hoist, tren MoM, stepMonth anti-masa-depan),
    `MembershipGateScreen` (scrim F2, terminal REJECTED/PIN_OWNED tanpa
    retry), `MembershipGateLogic` & `PinAttemptLimiter` (murni, di-test),
    `BackupDialogs` (badge 🔒 enkripsi, passphrase ≥8), `MainDialogs`
    (F3 fokus API key, L7 ClipData berlabel), `AiReportCard` (tombol
    AiBlue fixed AA 4.65:1), `MainTopBar` (avatar bertumpuk foto/inisial,
    inisial adaptif WCAG), `MainNavigationBar` (keyboard hide BUG-02),
    `GlowingBackground`, `StartupScreens`, `ImageViewerDialog` (pinch-zoom/
    pan/double-tap — di-test), `ChatBubbles`/`ChatScreen`/`ChatInput`
    (gestur tap/tahan/swipe — di-test). Nol dead code (8 fungsi publik
    semua terpakai), nol TODO/`!!`.
- **Audit lapisan `res/` — sumber daya (2026-08-14)**:
  - **Bug splash dark diperbaiki**: `values-night/colors.xml`
    `splash_background` = `#191C1B` — padahal itu warna `onBackground`/
    TEKS di dark theme, BUKAN background gelap asli (`#101414` di
    Theme.kt). Akibat: cold start mode gelap tampil #191C1B lalu
    "melompat" ke #101414 saat UI muncul. Dikoreksi ke `#101414`
    (konsisten dengan light: `#FBFDF9` = background light persis);
  - **Hardcoded contentDescription dibersihkan**: DonutChart (P2-18)
    memakai prefix "Ringkasan pengeluaran per kategori:" & satuan "persen"
    hardcoded di Kotlin → `donut_chart_summary` & `donut_chart_category_part`
    di strings.xml (kini 289 string). `stringResource` di-resolve di context
    composable (blok `semantics{}` bukan composable context);
  - Diaudit & dinyatakan sehat: `strings.xml` (289 string — **nol dead**,
    semua referensi R.string ter-resolve; 6 "missing" adalah Firebase
    generated yang sudah di-keep via `keep.xml`), `themes.xml` +
    `values-v31` (splash API 12+ benar), `colors.xml` (splash light
    cocok dgn Theme.kt), `font_certs.xml` (dipakai Type.kt P2.7),
    `keep.xml` (tools:keep Firebase resource), drawable (semua 7
    terpakai: ic_logo, ic_stat_logo monokrom, launcher foreground/
    background/monochrome, ic_google_logo, splash_screen). Nol duplikat
    nama string; duplikasi nilai yang tersisa (mis. app_name/pin_title,
    action_delete/manage_members_remove) sengaja — konteks berbeda.
  - **Verifikasi konsistensi format args (pass 8)**: ke-19 string ber-`%`
    (topbar_member_count, pin_rate_limited, settings_last_backup_time,
    donut_chart_*, dll.) diverifikasi satu per satu — semua pemanggil
    mengirim jumlah argumen yang cocok (via `stringResource(..., args)`
    atau `.format()`); 5 "mismatch" awal dari scan otomatis terbukti
    false positive (pola `.format()` di luar `stringResource`).
    Qualifier values-night + values-v31 juga diverifikasi benar untuk
    keempat kombinasi API × mode, dan `keep.xml` terbukti perlu (6 resource
    Firebase dibaca dinamis oleh SDK — bukan dead).

---

## [r1.2.0] - 2026-08-10 (T3 dekomposisi + M1 upgrade + audit UX)

### Added
- **FASE 4 (relay AI server — key milik server)**: Cloud Function `aiComplete`
  baru di `functions/index.js` yang memegang kunci AI MILIK SERVER (Firebase
  Functions secrets `OPENROUTER_API_KEY`/`GEMINI_API_KEY` — TIDAK pernah
  dikompilasi ke APK). User yang tidak mengisi kunci sendiri (BYOK) tetap
  mendapat deteksi AI: kaskade jadi 4 lapis OpenRouter(BYOK) → Gemini(BYOK) →
  **relay server** → heuristik offline. App memanggil via `RelayAiService`
  (Firebase Functions SDK — auth ID token otomatis, `request.auth` diverifikasi
  server; null-safe saat FirebaseApp belum aktif). Relay dipasang di 5 titik:
  parse transaksi, saran cepat, laporan audit, analisis bulanan, tanya AI.
  Catatan: model "opencode zen" tidak ada di katalog OpenRouter (400 model
  dicek per 2026-08-10) — daftar model gratis terverifikasi + Gemini dipakai.
  +5 unit test `RelayAiServiceTest`; panduan deploy di docs/DEVELOPER.md.
  Anti-regresi latensi offline (review): relay diberi timeout internal 15 s
  (bukan mewarisi 60 s kaskade) & di-skip saat NetworkMonitor melaporkan
  offline (`setNetworkOnline` — sinyal yang sama dengan indikator sync),
  sehingga user tanpa key yang sedang offline tetap langsung ke heuristik.
- **Auto-deploy Cloud Functions (`deploy-functions.yml`)**: push ke `main`/
  tag `r*` (atau manual) otomatis set secret `OPENROUTER_API_KEY` &
  `GEMINI_API_KEY` dari GitHub ke Firebase Functions secrets (Secret Manager)
  lalu deploy `aiComplete` + `notifyChatMessage` ke project `nyachat-in`.
  `.firebaserc` menetapkan project default. Butuh secret
  `FIREBASE_SERVICE_ACCOUNT` (izin cloudfunctions/run/secretmanager admin).
- **FASE 1 (T3)**: Dekomposisi 3 file raksasa tanpa mengubah behavior:
  - `ChatScreen.kt` (566 baris) — bubble & input bar dipisah ke `ChatBubbles.kt`
    & `ChatInput.kt`;
  - `RekapScreen.kt` (1442 → ~330 baris) — chart, daftar, & AI card dipisah ke
    `RekapCharts.kt`, `RekapList.kt`, `AiReportCard.kt` + state holder
    `RekapScreenState.kt`;
  - `MainActivity.kt` (1501 → 572 baris) — lifecycle glue ke `SyncLifecycle.kt`,
    callback ke `MainCallbacks.kt`, dialog ke `MainAppDialogs.kt`/`MainOverlays.kt`,
    state dialog ke `MainDialogController.kt`.
  Semua test hijau + Roborazzi compare + smoke test live.
- **FASE 2 (M1)**: Upgrade dependensi ke versi stabil terbaru (verifikasi tiap langkah):
  `compose-bom 2026.06.01`, `activity-compose 1.13.0`, `lifecycle 2.10.0` (2.11.0
  butuh compileSdk 37), `firebase-bom 34.17.0`, `okhttp 5.4.0` (major bump —
  audit API: semua pemakaian kompatibel; tervalidasi runtime via backup Drive
  live). `room` ditahan di **2.7.2** — seri 2.8.x membuat `AppDatabaseMigrationTest`
  Robolectric gagal (`SupportSQLiteDriver` menolak path DB yang di-redirect
  Robolectric); migration test adalah coverage kritis (M12) sehingga upgrade
  2.8 didefer.
- **BUG-06 lanjutan (P0)**: Deteksi jaringan nyata via
  `ConnectivityManager.NetworkCallback` (`NetworkMonitor` + izin
  `ACCESS_NETWORK_STATE`) — indikator sync kini jujur saat offline MURNI
  (sebelumnya "Tersinkron" walau jaringan mati, karena snapshot offline cache
  memanggil `markSynced()`). Fungsi murni `resolveStatusOnNetworkChange`/
  `resolveStatusOnSyncSuccess`/`resolveStatusOnDraining`; drain antrian pending
  tidak lagi menimpa status OFFLINE. Terverifikasi live: offline → "Mode
  offline", pulih → "Menyinkronkan…" → "Tersinkron", relaunch offline →
  "Mode offline".
- **3.9**: Label "· Terenkripsi" di Settings kini mencerminkan FILE backup
  terakhir yang dibuat (bukan toggle saat ini) — label hanya berubah saat
  backup baru dibuat.
- **3.10**: Snackbar "Passphrase salah" saat restore terenkripsi gagal
  (saluran `passphraseError` terpisah + durasi Long — sebelumnya tersembunyi
  di balik modal progres).
- **3.11**: Badge 🔒 "Terenkripsi" pada file terenkripsi di dialog Restore
  (deteksi via penanda nama + probe isi untuk backup lama).
- **3.8**: Indikator detail status sync — banner Rekap menampilkan
  "Tersinkron · 14:32" (waktu terakhir sinkron berhasil, dari
  `FirestoreSyncManager.lastSyncedAt`); status OFFLINE/SYNCING/ERROR tetap
  seperti sebelumnya.

### Fixed
- **BUG-1 (P0)**: Badge finansial hilang dari bubble chat ~5-10 detik setelah
  pesan tersinkron — `toObject(CloudMessage)` selalu memberi `isFinancial=false`
  karena CustomClassMapper tidak membaca Kotlin metadata (getter `is*()` →
  field "financial"). Fix `@get:PropertyName("isFinancial")` + test regresi
  round-trip. Badge bertahan & self-healing dari cloud setelah fix.
- **BUG-2 (P1)**: Draf chat hilang saat pindah tab Chat ⇄ Rekap —
  `AnimatedContent` menghancurkan state ChatScreen. Draf di-hoist ke
  MainActivity (`rememberSaveable`) via `draftText`/`onDraftChange`; reset saat
  logout & ganti PIN (isolasi antar-workspace). Terverifikasi live 2× round-trip.
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
- **BUG-05 (P1)**: Chips saran cepat tidak pernah tampil di runtime walau data
  saran terisi & kondisi tampil terpenuhi (laporan perangkat nyata "hilang saat
  keyboard terbuka" — ternyata tidak tampil sama sekali). Regresi upgrade
  compose-bom 2026.06 (M1): `LazyRow` meng-komposisi item tapi TIDAK
  me-layout-nya di runtime. Fix: `AnimatedVisibility` → `if` biasa dan
  `LazyRow` → `Row` + `horizontalScroll` + `height(48.dp)` di
  `QuickSuggestionRow`. Terverifikasi live: 3 chip tampil di atas input bar,
  tap chip mengisi draf; golden `quick_suggestions` di-record ulang (411×72)
  & PASS di verifyRoborazzi.

- **Saran cepat (quick suggestions)**: angka nominal DOBEL di chip
  rekomendasi di atas keyboard dihilangkan (laporan live) — deskripsi saran
  dibersihkan dari nominal ("Beli sayur", bukan "Beli sayur 10.000"),
  nominal diformat titik ribuan id-ID (`formatSuggestionAmount`), hanya
  transaksi PENGELUARAN yang ditampilkan; output AI disanitasi;
  +10 unit test.
- **Lint (M1)**: 16 error baru `LocalContextGetResourceValueCall` dari
  compose-bom 2026.06 diperbaiki — query resource di-hoist ke composable scope
  via `stringResource(...)` (MainActivity 4, PinConnectScreen 12). `lintDebug`
  kembali 0 error.

### Changed
- Bump `gradle.properties` ke **r1.2.0 (versionCode 27)** — satu sumber
  kebenaran versi (L11); README & DEVELOPER.md diselaraskan.
- **Redesign composer chat (2026-08-10)**: struktur layout dirapikan dari akar
  (pixel-verified, bukan asumsi):
  - Chip saran cepat jadi **outlined floating** (fill tipis mode-aware +
    `BorderStroke`, cap lebar 180dp) — bukan bar solid full-width;
  - Composer pill **tanpa shadow** (bayangan lama tampil sebagai garis di atas
    navbar) & bottom nav **menyatu** (`tonalElevation 0` + `surface`);
  - Tinggi pill **52dp = tombol Send** (ganti `OutlinedTextField` →
    `BasicTextField` karena M3 1.4.0 menghapus `contentPadding`; konstanta
    `CHAT_BAR_HEIGHT` dipakai di pill, Send, bar pratinjau); auto-grow tetap
    (maxLines 6) — fix pill melar 1650px akibat `fillMaxSize`+`weight`;
  - **Reply quote gaya Telegram**: quote pesan yang dibalas menempel DI DALAM
    pill di atas baris input (garis aksen vertikal kiri + nama tebal + snippet
    1 baris + ✕) — `ChatReplyBar` (card terpisah) dihapus, garis pemisah di
    tengah composer hilang; tombol Send bottom-aligned sejajar baris input.
  Golden baru `chat_composer_reply_quote` + 6 golden lama (banner ×2,
  rekap_transaction, chat_bubble ×3 — diff rendering M3 1.4.0) di-re-record;
  `verifyRoborazziDebug` kini **0 failure**.

- **Polish UI lanjutan (2026-08-10, setelah finalisasi rilis)**:
  - Chip saran cepat menjadi **frame-only transparan** (fill
    `Color.Transparent`, hanya border outline) — area di atas keyboard
    tidak lagi menutupi chat (sebelumnya fill surfaceVariant
    alpha 0.3/0.5);
  - Badge finansial lebih **ringkas**: indikator sumber heuristik dari
    teks "heuristik" (9 huruf) menjadi **ikon ⚡** kecil 12dp
    (contentDescription aksesibel), padding ramping 8/4; warna dark mode
    dilembutkan agar seimbang & tidak "menyala" (income sebelumnya paling
    terang: L=0.69 vs expense 0.46): expense `#FF8A80` → **`#F2A096`** dan
    income `#69EFC4` → **`#8FC6AD`** (sage lembut, kontras tetap ≥ 5:1);
  - **Snackbar pindah ke ATAS layar**: dari `BottomCenter` + `imePadding`
    (muncul tepat di atas keyboard & menutupi composer saat ketik cepat)
    → `TopCenter` + insets status bar, compact pill radius 28dp +
    `widthIn(max 480dp)`;
  - **Swipe-to-dismiss snackbar 3 arah (kiri/kanan/atas)**: material3
    1.3.0 tidak punya swipe bawaan (hanya dismissAction + aksesibilitas),
    jadi diimplementasi manual `DismissibleSnackbar` — drag 2 sumbu via
    `pointerInput` (akumulasi setelah touch slop + seed offset pra-slop
    anti-lompat), lepas ≥ 72dp → dismiss, di bawah ambang → animasi
    balik `tween 240ms` + fade-out proporsional; tap tombol aksi
    "Urungkan" tetap berfungsi;
  - **Animasi reply quote lembut**: `expandVertically` + fade (hapus
    double-animation ke Box field);
  - **FAB jump-to-bottom jadi overlay frame-only di pojok kanan-bawah
    daftar chat** (bukan di flow composer): lingkaran TRANSPARAN + border
    1dp outline + elevasi 0 ("cukup frame dari tombol aja", konsisten
    dengan chip saran); duduk di dalam `Box` pembungkus `LazyColumn`
    (`align(BottomEnd)`) sehingga chat scroll penuh di belakangnya dan
    TIDAK pernah menutupi tombol Send saat pill tinggi/keyboard terbuka
    — sebelumnya FAB di flow composer menimpa Send saat pesan panjang.
    Catatan teknis: di dalam Box, resolver Kotlin memilih
    `ColumnScope.AnimatedVisibility` dari receiver Column di luar dan
    gagal, jadi dipanggil dengan nama lengkap
    `androidx.compose.animation.AnimatedVisibility` (overload generik
    tanpa receiver, aman untuk scope apa pun).

  - **FAB tidak lagi menutupi bubble chat terakhir saat scroll
    (2026-08-10, follow-up)**: baris saran (chips) dipindah KE DALAM
    `Box` daftar chat (di bawah `LazyColumn` yang jadi
    `weight(1f)`), sehingga FAB overlay (`align(BottomEnd)`) melayang
    TEPAT DI ATAS baris chips — pesan berhenti di tepi atas chips dan
    tidak pernah lewat di bawah FAB (sebelumnya overlap terukur
    110×16px: ujung bawah pesan kanan masuk ke frame FAB). Chips diberi
    `endPadding` 64dp saat FAB tampil (diterapkan SEBELUM
    `horizontalScroll`) agar chip terakhir tidak tersembunyi di balik
    FAB; FAB juga disembunyikan saat draf terisi (mengetik — saat
    chips tersembunyi, FAB tak boleh melayang di atas daftar pesan
    lagi). Terverifikasi live: FAB frame y1801–1948 tepat di atas
    baris chips y1854–1896, tidak ada node teks pesan/chip yang
    tumpang-tindih, FAB bottom 1948 < tombol Send 2009, sembunyi saat
    mengetik, muncul lagi setelah draf dihapus, hilang di dasar daftar
    setelah tap (lompat ke pesan terbaru).

### Verifikasi live
- **Notifikasi chat real-time (FCM) end-to-end (2026-08-10)**: rantai penuh
  Firestore → Cloud Function `notifyChatMessage` → FCM → notifikasi Android
  terbukti live (2 emulator). Blocker infra GCP yang diperbaiki: runtime SA Cloud
  Run diberi `roles/datastore.user` (baca Firestore) & `roles/firebase.sdkAdminServiceAgent`
  (kirim FCM — `cloudmessaging.messages.create`), token FCM kini tersimpan ke
  `families/{pin}/members/{uid}/fcmToken` (`ensureTokenSynced`). Bukti: log fungsi
  `Multicast total=1 gagal=0 sender=Ari Purnomo Aji` + notifikasi
  "Ari Purnomo Aji / pesan-e2e-rest-987" tampil di status bar device B.
  Catatan: uji 2 identitas berbeda butuh akun Google kedua di emulator (lihat FASE 4 rilis).
- Edit pesan lintas perangkat (2 emulator, 2026-08-09) — LWW via server timestamp terbukti
  (editedAt + serverUpdatedAt terisi, transaksi ikut ter-update).
- Backup/restore Drive akun nyata — backup plain & terenkripsi (M5), restore
  ke perangkat kedua, passphrase salah ditolak tanpa merusak data.
- Daftar model AI via API `/models` — 1 model OpenRouter retired diganti 2
  model gratis terverifikasi; Gemini `gemini-3.5-flash` tetap valid.
- Smoke setelah upgrade M1: session bertahan, sync "Tersinkron", backup Drive
  sukses (terverifikasi live).
- BUG-05 chips saran cepat (2026-08-10): 3 chip tampil di atas input bar,
  tap chip → draf terisi; golden `quick_suggestions` re-record (411×72) PASS;
  unit test BUILD SUCCESSFUL; 0 log debug tersisa (terverifikasi live).

- Swipe-dismiss snackbar (2026-08-10): 3 arah diverifikasi live di
  emulator (dark mode) — snackbar hilang **0.92–0.96 s** setelah swipe
  (jauh di bawah timeout `SnackbarDuration.Long` 10 s; pill score pixel
  2870 → 150); tap tombol aksi "Urungkan" tetap berfungsi (bukan
  timeout). APK terbaru terpasang di emulator.
- FAB jump-to-bottom overlay (2026-08-10): terverifikasi live di
  emulator-5554 — saat scroll ke atas FAB frame-only tampil di
  pojok kanan-bawah daftar chat (icon "Ke pesan terbaru" y1064–1127,
  frame border y1022–1168), **jauh di atas composer** (tombol Send
  y1399–1457) → tidak ada overlap; scroll ke dasar 87 pesan → FAB
  hilang (hide path `AnimatedVisibility` bekerja); scroll balik ke atas
  → FAB muncul lagi. APK terbaru terpasang di emulator-5554 & -5556.
- FAB tidak menutupi bubble terakhir (2026-08-10, follow-up):
  terverifikasi live di emulator-5554 — FAB kini melayang DI ATAS
  baris chips saran (frame x901–1048 y1801–1948, persis menutupi
  band chips y1802–1948) sehingga tidak ada pesan yang lewat di
  bawahnya saat scroll; chips berhenti sebelum FAB (endPadding 64dp,
  chip ke-3 tidak tersembunyi di balik frame); FAB sembunyi saat
  mengetik dan muncul lagi setelah draf dihapus; tap FAB → lompat ke
  pesan terbaru → FAB hilang; FAB bottom 1948 < tombol Send 2009
  (tidak pernah menutupi Send). APK terbaru terpasang di emulator-5554.

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