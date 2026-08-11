# 📱 Laporan Pengujian Live di Emulator — Nyachat r1.1.3 (build 26)

> Pengujian end-to-end **live di emulator** (Pixel 7a, Android 14 / API 34) dengan
> APK debug terbaru, mode: **online (Firestore aktif)** — berbeda dari laporan
> perangkat nyata r1.0.3 yang offline-first.
> Tanggal: 2026-08-08/09 · Versi: `r1.1.3` (versionCode 26)

---

## Ringkasan Eksekutif

| Kategori | Status | Catatan |
|---|---|---|
| **Versi terpasang** | ✅ Diselaraskan | Emulator awalnya r1.1.2/25; tag GitHub "r1.1.3" berisi APK r1.1.2/25 (tag menyesatkan) → dibangun ulang r1.1.3/26 & di-install |
| **Stabilitas (crash)** | 🚨→✅ | Ditemukan **1 bug KRITIS crash sync** (deserialize `serverUpdatedAt`) → diperbaiki & diverifikasi tidak crash lagi |
| **Chat → transaksi** | ✅ | "beli kopi 20rb" → -Rp20.000 (Makanan & Minuman) + snackbar Urungkan |
| **Chat → pemasukan** | ✅ | "gaji masuk 5juta" → +Rp5.000.000 (Gaji & Pemasukan) |
| **Rekap** | ✅ | Saldo, donut, alokasi kategori, status "Tersinkron" |
| **Transaksi manual** | ✅ | Dialog lengkap → -Rp150.000 (Groceries & Sembako) |
| **Settings & Dark Mode** | ✅ | Sheet lengkap; dark mode aktif & **persist setelah restart** |
| **Kelola Anggota** | ✅ | Owner tampil + empty state join request |
| **Tanya AI (offline)** | ✅ | Bubble "Asisten AI" (fallback tanpa API key) |
| **PIN Workspace** | ✅ | Dialog tampil PIN 55574015 + tombol Salin |
| **Restart app** | ✅ | Sesi login dipertahankan, data & dark mode tetap, tanpa crash |

**Kesimpulan**: Dengan fix bug kritis sync, **seluruh fitur inti berfungsi stabil
di skenario online**. Aplikasi siap rilis r1.1.3.

---

## 🚨 Bug Kritis yang Ditemukan & Diperbaiki (di sesi ini)

### K2 — Crash FATAL saat pesan pertama tersinkron ke Firestore
| Aspek | Detail |
|---|---|
| **Reproduksi** | `pm clear` → login → kirim "beli kopi 20rb" → ~9 detik kemudian **FATAL EXCEPTION**, app kembali ke launcher |
| **Error** | `Could not deserialize object. Failed to convert a value of type com.google.firebase.Timestamp to long (found in field 'serverUpdatedAt')` |
| **Akar masalah** | DTO `CloudMessage`/`CloudTransaction` mendeklarasikan `serverUpdatedAt: Long?`, padahal cloud menyimpannya sebagai `com.google.firebase.Timestamp` (dari `FieldValue.serverTimestamp()`, fitur M4). `toObject()` gagal **sebelum** `.copy(serverUpdatedAt = serverMs)` dijalankan |
| **Dampak** | **Fitur sync lintas perangkat 100% rusak** — setiap perangkat crash di tulis pertama yang tersinkron |
| **Perbaikan** | (1) Tipe DTO → `com.google.firebase.Timestamp?` + konversi `toMillis()` saat simpan ke Room; (2) `toObject()` dipindah ke dalam `try/catch` (skema tak dikenal tidak mematikan proses); (3) unit test regresi |
| **Verifikasi** | Build + unit test PASS → install ulang → kirim pesan → snackbar "Tercatat" + sync "Tersinkron", **tanpa crash** |

---

## ✅ Hasil Live Test per Fitur (Detail)

### 1. Launch & Update Check
- App launch normal, tanpa crash.
- Dialog "Update tersedia" muncul di layar login (r1.1.3) ✅
- Data & workspace dari sesi sebelumnya tersimpan saat app di-restart.

### 2. Login & Workspace
- Google Sign-In via Credential Manager: akun `ngampusendiri@gmail.com` ✅
- Generate PIN 8 digit (SecureRandom): `55574015` ✅ — dialog PIN + tombol Salin bekerja.
- **Tagline login**: "Nyatat keuangan cukup dengan Chat" ✅ (koreksi r1.1.3).

### 3. Chat → Deteksi Transaksi (fitur inti)
| Input | Hasil |
|---|---|
| `beli kopi 20rb` | Snackbar **"Tercatat: - Rp20.000 (Makanan & Minuman)"** + tombol **Urungkan** ✅ |
| `gaji masuk 5juta` | Snackbar **"Tercatat: + Rp5.000.000 (Gaji & Pemasukan)"** ✅ |
| Tanpa API key | Parsing **heuristik offline** berjalan (badge "HEURISTIK") ✅ |

- Tidak ada crash setelah kirim pesan (regresi K2 teratasi).

### 4. Tab Rekap
- Status sinkron: **"Tersinkron"** ✅ (sebelum fix, ini crash; sesudah fix normal).
- Pemasukan Rp5.000.000 · Pengeluaran Rp170.000 · **Saldo Rp4.830.000** ✅
- Donut chart kategori + progress bar alokasi benar (Makanan & Minuman, Groceries & Sembako) ✅
- Transaksi dari chat & manual muncul di riwayat ✅

### 5. Tambah Transaksi Manual
- Dialog "Catat Transaksi Manual": toggle jenis, nominal (live-formatting), keterangan, kategori dropdown ✅
- Simpan → snackbar "Tercatat: - Rp150.000 (Groceries & Sembako)" → Rekap ter-update ✅

### 6. Settings Sheet
- Identitas (nama, role Pemilik, PIN masked ••••4015), Mode Gelap, Periksa Update, Kunci AI, Backup/Restore Drive, Enkripsi backup, Export CSV, Zona Berbahaya ✅
- **Dark Mode**: toggle bekerja; tema berubah; **persist setelah force-stop + relaunch** ✅

### 7. Kelola Anggota
- Anggota "Ari Purnomo Aji (Pemilik)" tampil + empty state "Tidak ada permintaan bergabung" ✅

### 8. Tanya AI (fallback offline)
- Input "bagaimana cara menabung" → bubble **"Asisten AI"** dengan balasan offline ✅ (tanpa API key, tanpa crash)

### 9. Restart & Persistensi
- `force-stop` → relaunch: sesi login dipertahankan (tidak minta PIN ulang), dark mode aktif, data & AI bubble tersimpan ✅

---

## ♿ Penilaian UI/UX (observasi live)

| Aspek | Nilai | Catatan |
|---|---|---|
| **Konsistensi desain** | 👍 Baik | Material 3, radius terstandar, kategori & role konsisten |
| **Hierarki visual** | 👍 Baik | TopBar → konten → bottom nav; banner saldo jelas |
| **Micro-interaction** | 👍 Baik | Snackbar undo, live-formatting nominal, badge provenance AI/heuristik |
| **Dark mode** | 👍 Baik | Tema konsisten, kontras ok (terverifikasi restart) |
| **Empty states** | 👍 Baik | "Belum ada transaksi", "Tidak ada permintaan bergabung" |
| **Aksesibilitas** | 🟡 Cukup | Content-desc ikon ada; kontras default M3 ok; belum ada uji TalkBack menyeluruh |
| **Kritik minor** | 🟡 | (1) Indikator sync di Rekap bisa lebih informatif (detail status); (2) quick suggestion chips hilang saat keyboard terbuka; (3) field chat kadang menyisakan karakter setelah dialog ditutup |

---

## 🎯 Rekomendasi Tindak Lanjut (urutan prioritas)

| # | Item | Alasan |
|---|---|---|
| 1 | **Re-record golden Roborazzi** (tagline login berubah di r1.1.3) | Kalau tidak, CI `verifyRoborazziDebug` gagal di push berikutnya |
| 2 | **Commit + tag r1.1.3** (6 file berubah belum di-commit) | Menyelaraskan release GitHub dengan sumber |
| 3 | **Uji alur edit pesan** end-to-end (tap badge → edit → simpan → sync) | Belum diuji live; rawan konflik LWW |
| 4 | **Uji backup/restore Drive** dengan akun nyata | Belum diuji live end-to-end |
| 5 | **Verifikasi daftar model** Gemini/OpenRouter via API `/models` | Model bisa retired kapan saja |
| 6 | **T3 refactor file raksasa + M1 upgrade dependensi** | Rencana r1.2.0 (lihat `implementation_plan_r1.2.0.artifact.md`) |

---

*Laporan berbasis pengujian live nyata di emulator (adb/uiautomator + screenshot), bukan analisis statis.*
