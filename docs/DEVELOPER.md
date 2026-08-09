# 🛠️ Panduan Developer — Nyachat

Dokumen teknis untuk developer yang ingin membangun, menguji, atau berkontribusi
pada **Nyachat**. Untuk pengguna umum lihat [README](../README.md).

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
appVersion=r1.1.3
appVersionCode=25
```

Override tanpa edit file: `./gradlew -PappVersion=r2.0.0 -PappVersionCode=26 :app:assembleDebug`.

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
- **AI 3 lapis**: OpenRouter (rotasi model gratis) → Gemini → mesin heuristik
  offline; semua BYOK
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
│   ├── remote/              Gemini, OpenRouter, FirestoreSync, Membership, UpdateChecker
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
    ├── screens/             ChatScreen, ChatBubbles, ChatInput, RekapScreen, RekapCharts, RekapList,
    │                        RekapScreenState, AiReportCard, PinConnectScreen, SettingsSheet,
    │                        ChatBubbles/ChatInput (TASK-1.1), MainDialogs/BackupDialogs/
    │                        MainTopBar/MainNavigationBar/GlowingBackground (TASK-1.3), ...
    ├── theme/               Color, Theme, Type, SemanticColors
    └── util/                AvatarImage, DateLabels
```

---

## 🧱 Tech Stack

- **Kotlin + Jetpack Compose (Material 3)**
- Room, OKHttp, kotlinx.coroutines
- Firebase (Auth, Firestore, Crashlytics)
- Gradle 9.3.1 · AGP 9.1.1 · Kotlin 2.x

---

## 🗺️ Roadmap

- [x] Backup & restore Google Drive (terenkripsi, auto 24 jam)
- [x] Export rekap CSV
- [x] Workspace bersama + kelola anggota + persetujuan owner
- [x] Sinkronisasi realtime Firestore + LWW server-timestamp
- [x] Badge provenance AI/heuristik + attachment namespace + Room v11
- [x] CI rilis otomatis (APK debug + release + AAB)
- [ ] Grafik bulanan & notifikasi pengingat
- [x] Refactor MainActivity/ChatScreen/RekapScreen (T3) — MainActivity 572 baris, ChatScreen 566 baris, RekapScreen ~330 baris (semua selesai 2026-08-09; RekapScreen dipecah ke RekapScreenState.kt, RekapCharts.kt, RekapList.kt, AiReportCard.kt)
- [ ] Upgrade dependensi ke versi stabil terbaru (M1)
