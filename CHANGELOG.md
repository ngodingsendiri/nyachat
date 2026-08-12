# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 2026-08-12 (polish UI chat, Profil & Akun, tuning AI, audit keanggotaan)

> Perubahan setelah r1.2.0 (belum dirilis — versi tetap r1.2.0/27).

### Added
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