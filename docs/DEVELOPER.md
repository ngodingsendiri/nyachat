# 🛠️ Panduan Developer — Nyachat

Dokumen teknis untuk developer yang ingin membangun, menguji, atau berkontribusi
pada **Nyachat**. Untuk pengguna umum lihat [README](../README.md).

## 📚 Peta Proyek (mulai dari sini untuk orientasi)

| Dokumen | Isi |
|---|---|
| [STRUCTURE.md](./STRUCTURE.md) | 🗺️ Peta pohon proyek — semua folder & file, statistik, alur singkat |
| [NAVIGATION.md](./NAVIGATION.md) | 🧭 Peta navigasi antar layar, sheet, dan dialog |
| [DATA_FLOW.md](./DATA_FLOW.md) | 🔄 Alur data offline-first (Room → Firestore sync → PendingOp) |

---

## 🔐 Setup Google Sign-In (Firebase)

Login Google gagal ("gagal login") hampir selalu karena **salah satu dari 3 hal
ini belum beres di Firebase Console** — bukan karena kode app:

1. **Provider Google aktif** → Firebase Console → project `nyachat-in` →
   *Build → Authentication → Sign-in method* → **Google → Aktifkan**.
2. **SHA-1 penandatangan terdaftar** → *Project settings → Aplikasi Anda* →
   app `com.startupmini.nyachat` → **Tambahkan sidik jari**. Ini penyebab
   paling umum: Firebase menolak APK yang SHA-1-nya tidak terdaftar.
3. **google-services.json valid** → file `app/google-services.json` harus cocok
   dengan project; unduh ulang dari Console jika ragu.

### SHA-1 yang harus didaftarkan

| APK | Keystore | SHA-1 |
|---|---|---|
| **Debug** (CI/artifak, stabil) | `debug.keystore` (di-commit — jangan diganti) | `B5:9D:30:B8:D8:A0:BE:78:1D:D4:89:F6:73:B1:22:C1:45:59:03:B1` |
| **Release** (bertanda tangan) | keystore `upload` (secret `KEYSTORE_BASE64`) | cek via keytool ↓ |

```bash
keytool -list -v -keystore my-upload-key.jks -storepass PASSWORD | grep SHA1
```

> 💡 Sejak v1.2.5, app menampilkan SHA-1 miliknya sendiri di layar error login —
> siap disalin ke Firebase Console.

### ⚠️ Risiko `debug.keystore` publik (T2/P1)

Keystore debug di-commit ke repo agar SHA-1 debug stabil (Google Sign-In tidak
ditolak). Konsekuensi: siapa pun bisa menandatangani APK debug dengan SHA-1 yang
sama. Risiko hanya menyentuh **pengguna yang memasang APK debug dari sumber tak
tepercaya**. Mitigasi berlapis:

1. **Firebase App Check** — tolak request Firestore dari instal yang tidak
   terverifikasi (Play Integrity / App Attest).
2. **Play App Signing** — versi Play menggunakan kunci upload terpisah, bukan
   `debug.keystore` (SHA-1 produksi berbeda dari debug).
3. **Edukasi pengguna** — unduh hanya dari GitHub Releases resmi / Play Store.

---

## 🛠️ Build dari Source

### Prasyarat

- **JDK 21** (wajib — compileSdk 36 & test Robolectric butuh Java 21; JDK 17
  membuat unit test Robolectric gagal)
- Android SDK (compileSdk 36)

### Langkah

```bash
# 1. Build APK debug (tidak perlu API key — BYOK di-set lewat UI app)
./gradlew :app:assembleDebug

# 2. Hasil: app/build/outputs/apk/debug/app-debug.apk
```

### Release signing (opsional)

```bash
export KEYSTORE_PATH=/path/ke/keystore.jks
export STORE_PASSWORD=...
export KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

### Versi & versionCode (L11)

Versi diambil dari **`gradle.properties`** (satu sumber kebenaran):

```properties
appVersion=r1.5.0
appVersionCode=30
```

Override tanpa edit file: `./gradlew -PappVersion=r2.0.0 -PappVersionCode=30 :app:assembleDebug`.
(versionCode harus SELALU naik — Play Store menolak versionCode menurun.)

---

## ☁️ Relay AI Server (FASE 4 — key AI milik server)

Aplikasi murni **BYOK** (user mengisi kunci Gemini/OpenRouter sendiri). Untuk
user yang **tidak mengisi kunci**, disediakan **relay AI server**: Cloud Function
`aiComplete` yang memegang **kunci milik server** (Firebase Functions secrets —
tidak pernah dikompilasi ke APK). App yang sudah login memanggil fungsi ini;
auth Firebase diverifikasi otomatis oleh protokol callable (`request.auth`).

### Alur kaskade AI (setelah fitur ini)

```
1. OpenRouter (BYOK user)  → key user di Pengaturan
2. Gemini (BYOK user)      → key user di Pengaturan
3. Relay server (FASE 4)   → key server di Cloud Function (tanpa key user)
4. Heuristik offline       → mesin aturan lokal (tanpa internet / gagal semua)
```

### Setup sekali jalan (dilakukan admin repo)

```bash
cd functions
npm install

# Set secret AI (sekali saja; tersimpan di Google Cloud Secret Manager)
firebase functions:secrets:set OPENROUTER_API_KEY
firebase functions:secrets:set GEMINI_API_KEY

# Deploy fungsi relay (+ notifikasi chat yang sudah ada)
firebase deploy --only functions
```

> ⚠️ Firebase Functions butuh paket **Blaze** (bayar per pemakaian; kuota gratis
> bulanan tetap ada). Tanpa deploy, app tetap berfungsi penuh via BYOK + heuristik
> offline — relay hanyalah lapisan tambahan.

### Implementasi

- `functions/index.js` → `exports.aiComplete` (onCall): OpenRouter gratis (rotasi
  7 model) → Gemini; mengembalikan `{ text }` atau `{ text: null }`.
- `app/.../data/remote/RelayAiService.kt` → memanggil callable via Firebase
  Functions SDK (auth otomatis); null-safe saat FirebaseApp belum aktif.
- `GeminiService.kt` → relay disisipkan setelah OpenRouter/Gemini BYOK gagal,
  sebelum heuristik offline (parse, saran cepat, audit, bulanan, tanya AI).
- Model "opencode zen" yang sempat diminta tidak ditemukan di katalog OpenRouter
  (400 model, per 2026-08-10) — daftar model gratis terverifikasi dipakai.

### Secrets yang dipakai di CI

| Secret | Fungsi |
|---|---|
| `OPENROUTER_API_KEY` | Key OpenRouter milik server (relay) — dari GitHub ke Firebase Functions secret |
| `GEMINI_API_KEY` | Key Gemini milik server (relay) — dari GitHub ke Firebase Functions secret |
| `FIREBASE_SERVICE_ACCOUNT` | (Deploy fungsi) JSON service account dengan izin `cloudfunctions.admin`, `run.admin`, `secretmanager.admin`, `iam.serviceAccountUser` |

> Key AI dipakai di **Cloud Function** (server-side), BUKAN di APK. Aman dari
> ekstraksi APK.

### Auto-deploy via GitHub Actions (`deploy-functions.yml`)

Push ke `main` / tag `r*` (atau tombol manual **Run workflow**) otomatis:

1. Autentikasi ke Google Cloud via `FIREBASE_SERVICE_ACCOUNT`
2. Set `OPENROUTER_API_KEY` & `GEMINI_API_KEY` (jika terisi) sebagai Firebase
   Functions secrets (Google Cloud Secret Manager)
3. Deploy `functions` (`aiComplete` + `notifyChatMessage`) ke project
   `nyachat-in`

`.firebaserc` menetapkan project default `nyachat-in` — deploy lokal cukup
`firebase deploy --only functions` tanpa `--project`.

> ⚠️ **Blaze plan**: Cloud Functions TIDAK tersedia di Spark (gratis). Blaze
> (pay-as-you-go) tetap punya kuota gratis — 2 juta invocations/bulan, 400rb
> GB-detik, 5 GB outbound — jadi praktis tidak ditagih pada pemakaian normal.
> `firebase functions:secrets:set` juga hanya tersedia di Blaze.

---

## 🤖 GitHub Actions (CI)

Workflow **Build APK** (`.github/workflows/build-apk.yml`) jalan saat push ke
`main` atau tag `r*`:

1. Unit test + lint + **snapshot UI Roborazzi** + lint Firestore rules
2. Build APK debug → upload artifact `Nyachat-rX.Y.Z-debug`
3. Jika secrets keystore ada → build + upload APK **release** & **AAB** (Play)
4. Push **tag** → buat **GitHub Release** dengan APK/AAB (link permanen)

### Secrets yang dipakai

| Secret | Fungsi |
|---|---|
| `KEYSTORE_BASE64` | Isi file keystore `.jks` dalam base64 (untuk APK release) |
| `KEYSTORE_PASSWORD` | Password keystore |
| `KEY_PASSWORD` | Password key (alias `upload`) |

> `OPENROUTER_API_KEY` **tidak dipakai** sejak P1 — tidak ada API key AI yang
> dibakar ke APK (murni BYOK).

---

## 🧪 Testing

```bash
# Unit test (JVM, tanpa emulator) — logika bisnis, parser heuristik, gate, dll.
./gradlew :app:testDebugUnitTest

# Snapshot UI (Roborazzi + Robolectric): bandingkan render dengan golden file
# di app/src/test/snapshots/. Gagal kalau ada regresi visual.
./gradlew :app:verifyRoborazziDebug

# Rekam ulang baseline setelah mengubah UI, lalu commit PNG yang berubah:
./gradlew :app:recordRoborazziDebug

# Lint Android
./gradlew :app:lintDebug

# Lint Firestore rules
npm ci && npm run lint:rules
```

> ⚠️ **Roborazzi & font OS**: golden PNG direkam di CI runner (ubuntu + Temurin
> JDK 21). Render font Robolectric berbeda antar-OS — rekam baseline di runner
> CI (pola komit `c3a01a6`), bukan di Windows lokal, agar `verify` tidak gagal.

---

## 🏗️ Arsitektur

```
┌─ HP Android (app Nyachat) ─────────────────────────────┐
│  Chat + AI → OpenRouter cloud (BYOK)  ← key user        │
│           → Google Gemini cloud (BYOK) ← key user       │
│  Data utama → Room (SQLite) di perangkat ← offline-first│
│  Sync aktif → Firebase Firestore (google-services.json) │
└────────────────────────────────────────────────────────┘
```

- **Tanpa server sendiri** — semua berjalan di perangkat + cloud AI pihak ketiga
- **MVVM + Repository**: `MainViewModel` (StateFlow) ↔ `FinanceRepository`
  (persist lokal + sync cloud + AI via `FinanceAiService`)
- **AI 4 lapis**: OpenRouter (BYOK) → Gemini (BYOK) → relay server (FASE 4,
  key milik server) → mesin heuristik offline
- **Sync cloud**: `FirestoreSyncManager` — last-writer-wins deterministik pakai
  `FieldValue.serverTimestamp()` (immune clock-skew), listener realtime,
  antrian offline (`PendingOp`) dengan exponential backoff
- **Keamanan**: SecureStorage (Android Keystore) untuk API key & PIN, rules
  Firestore diperketat, backup Drive terenkripsi (AES-256-GCM + PBKDF2 600k)

### Struktur kode

```
app/src/main/java/com/startupmini/nyachat/
├── MainActivity.kt          (UI shell + wiring — TASK-1.3: 1501→572 baris)
├── Constants.kt             (satu sumber kebenaran konstanta/pref/field)
├── data/
│   ├── local/               Room DB, DAO, SecureStorage, AvatarStore
│   ├── remote/              Gemini, OpenRouter, FirestoreSync, Membership, UpdateChecker,
│   │                        NetworkMonitor (ConnectivityManager.NetworkCallback → status sync jujur saat offline)
│   ├── repository/          FinanceRepository (logika bisnis utama)
│   ├── analytics/           FinancialInsights, MonthlyAnalytics, WeeklyInsights
│   └── backup/              DataExporter, BackupCrypto, DriveBackup{Controller,Manager}
└── ui/
    ├── MainViewModel.kt     (StateFlow; jembatan UI ↔ repository)
    ├── SyncLifecycle.kt     (TASK-1.3: lifecycle glue — sync/API key/update/auto-backup/pause-resume/re-check membership)
    ├── MainCallbacks.kt     (TASK-1.3: interface ChatCallbacks/RekapCallbacks + factory wiring)
    ├── MainDialogController.kt (TASK-1.3: state holder 16 dialog/overlay)
    ├── MainAppDialogs.kt    (TASK-1.3: dialog lapisan konten — transaksi, settings, API key, PIN, logout, AI report)
    ├── MainOverlays.kt      (TASK-1.3: overlay global — gate, kelola anggota, update, backup Drive, snackbar)
    ├── screens/
    │   ├── ChatScreen.kt / ChatBubbles.kt / ChatInput.kt   (TASK-1.1 — bubble & input bar terpisah)
    │   ├── RekapScreen.kt / RekapCharts.kt / RekapList.kt / RekapScreenState.kt / AiReportCard.kt
    │   │                                                   (TASK-1.2 — orkestrasi ~330 baris + komponen)
    │   ├── MainTopBar.kt / MainNavigationBar.kt / GlowingBackground.kt (TASK-1.3)
    │   ├── PinConnectScreen.kt / SettingsSheet.kt / ManageMembersScreen.kt / MembershipGateScreen.kt
    │   ├── AddTransactionDialog.kt / AiReportDialog.kt / PinAttemptLimiter.kt
    │   └── ...
    ├── theme/               Color, Theme, Type, SemanticColors
    └── util/                AvatarImage, DateLabels
```

---

## 🧱 Tech Stack

- **Kotlin + Jetpack Compose (Material 3)** — compose-bom `2026.06.01`
- Room `2.7.2` (seri 2.8.x sengaja ditunda — `MigrationTestHelper` Robolectric rusak,
  lihat CHANGELOG r1.2.0) · OKHttp `5.4.0` · kotlinx.coroutines
- Firebase (Auth, Firestore, Crashlytics) — firebase-bom `34.17.0`
- Lifecycle `2.10.0` · activity-compose `1.13.0` (2.11/2.13 terbaru butuh compileSdk 37)
- Gradle 9.3.1 · AGP 9.1.1 · Kotlin 2.2.x

> ⚠️ **Lint & compose-bom baru**: sejak compose-bom 2026.06, rule
> `LocalContextGetResourceValueCall` melarang query resource via `LocalContext`
> di dalam fungsi non-composable. Pola baku proyek: hoist `stringResource(...)`
> ke composable scope, template berargumen pakai `.format()`.

---

## 🗺️ Roadmap

- [x] Backup & restore Google Drive (terenkripsi, auto 24 jam)
- [x] Export rekap CSV
- [x] Workspace bersama + kelola anggota + persetujuan owner
- [x] Sinkronisasi realtime Firestore + LWW server-timestamp
- [x] Badge provenance AI/heuristik + attachment namespace + Room v11
- [x] CI rilis otomatis (APK debug + release + AAB)
- [ ] Grafik bulanan & notifikasi pengingat
- [x] Refactor MainActivity/ChatScreen/RekapScreen (T3) — MainActivity 572 baris, ChatScreen 566 baris, RekapScreen ~330 baris (selesai 2026-08-09; RekapScreen → RekapScreenState.kt, RekapCharts.kt, RekapList.kt, AiReportCard.kt)
- [x] Upgrade dependensi (M1) — compose-bom 2026.06.01, lifecycle 2.10.0, activity 1.13.0, firebase-bom 34.17.0, okhttp 5.4.0 (selesai 2026-08-09); Room 2.8 didefer (Robolectric migration test)
- [x] Deteksi jaringan & indikator sync jujur (NetworkMonitor + BUG-06 lanjutan, 2026-08-09)
- [x] Badge finansial hilang (BUG-1) & draf chat hilang antar-tab (BUG-2) — FIXED 2026-08-09
- [x] Chips saran cepat tampil kembali (BUG-05) — regresi compose-bom 2026.06: `LazyRow`
  tak me-layout item → `Row`+`horizontalScroll`; FIXED 2026-08-10

---

## 🔍 Temuan & Kebijakan Audit 2026-08-14

### 1. Kunci pemulihan auto-backup (P1#1)
Auto-backup Drive dienkripsi dengan passphrase acak 32-char base64 yang HANYA
disimpan di Keystore perangkat. HP hilang/reinstall = backup tak bisa dibuka.
**Kebijakan**: kunci ditampilkan SEKALI saat pertama dibangkitkan (dialog
"Kunci Pemulihan Auto-Backup") supaya user bisa menyimpannya di tempat aman.
Jangan pernah menyimpan passphrase ini ke Firebase/Drive — di situ inti
keamanannya (kalau tersimpan di cloud, enkripsi jadi kosmetik).

### 2. Rate limit AI server (P1#2)
`aiComplete` (Cloud Functions) punya batas 30 panggilan/menit/uid (in-memory,
per-instance) + batas prompt 6.000 char & gambar 3 MB base64. Kalau app
membutuhkan panggilan lebih banyak, naikkan konstanta di `functions/index.js`
— atau ganti ke counter Firestore untuk penegakan lintas-instance.

### 3. Privasi log AI (P2#5)
Output AI TIDAK boleh di-log ke Cloud Logging — isinya data finansial user
(nominal, kategori). Cukup `model` + `len`. Jangan menambahkan kembali
cuplikan isi (`head=`) ke log.

### 4. Invariant uang (P2#6)
Nominal disimpan sebagai Double — aman untuk rupiah integer < 2^53 (semua
sumber nominal — parse AI & heuristik — menghasilkan integer).
`normalizeAmount()` (data/local, dipakai di FinanceRepository, DataExporter
restore, dan FirestoreSyncManager merge cloud) men-snap pecahan di SETIAP batas
persist; `MoneyExactnessTest` mem-pin invariant ini. **Kalau test ini gagal →
ada jalur pecahan/drift floating-point — selidiki sebelum menambah fitur
numerik apa pun.**

### 5. Mesin heuristik offline (P2#4)
Logika parse offline dipindah dari GeminiService.kt → `OfflineTransactionParser.kt`
(objek murni & deterministik). GeminiService hanya menyisakan delegasi supaya
referensi lama tetap bekerja. Tambahkan logika heuristik di file baru, JANGAN
di GeminiService.

### 6. Golden Roborazzi — REKAM HANYA DI CI
Golden yang direkam di Windows lokal sering TIDAK cocok dengan render CI
(font/AA beda) → verify gagal padahal bukan regresi. **Kebijakan**: rekam ulang
baseline HANYA lewat workflow_dispatch `mode=record` di runner CI (ubuntu),
lalu commit PNG dari artifact `roborazzi-goldens-recorded`. Jangan re-record
di lokal Windows.

### 7. Smoke test perangkat nyata (P2#3)
`app/src/androidTest/AppSmokeTest` berjalan di emulator sungguhan (CI job
`device-smoke`, API 34 google_apis). TIDAK memakai `waitForIdle` Compose —
animasi infinite (indikator `aiSpark` di ChatBubbles) membuat Compose tidak
pernah idle dan test bisa menggantung. Pola baku: `ActivityScenario` + bounded
sleep + cek Activity hidup. Saat menambah test device, ikuti pola ini.
