# 💬 Nyachat

[![Build APK](https://github.com/ngodingsendiri/nyachat/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ngodingsendiri/nyachat/actions/workflows/build-apk.yml)
![Versi](https://img.shields.io/badge/versi-r1.4.0-brightgreen)
![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-blue)

**Nyachat** adalah aplikasi Android pencatat keuangan keluarga/kelompok yang bekerja lewat **percakapan chat** — seperti WhatsApp, tapi otomatis mencatat uang.

Cukup ketik pesan biasa:

- 💸 *"beli kopi 20rb"* → tercatat sebagai pengeluaran
- 💰 *"gaji masuk 5 juta"* → tercatat sebagai pemasukan

AI mendeteksi transaksi, nominal, dan kategorinya dari obrolan sehari-hari — lalu semuanya tampil dalam rekap visual yang rapi.

## ✨ Fitur

- 💬 **Catat lewat chat** — AI membaca pesan biasa & mencatat transaksi otomatis
- 📷 **Foto nota** — lampirkan struk belanja, AI vision membacanya
- 📊 **Rekap visual** — saldo, diagram donat per kategori, progres anggaran
- 🤖 **Analisis AI** — evaluasi arus kas + rekomendasi hemat
- 🔄 **Workspace bersama** — beberapa perangkat tersinkron via PIN
- ☁️ **Backup Drive** — cadangan terenkripsi, otomatis tiap 24 jam
- 📤 **Export CSV** — buka di Excel / Google Sheets
- 🔒 **Offline-first** — data tersimpan di perangkat; AI pakai key Anda sendiri (BYOK)

## 📥 Install

Unduh APK terbaru dari **GitHub Releases**:

**⬇️ [Nyachat r1.4.0 — Download](https://github.com/ngodingsendiri/nyachat/releases/latest)**

1. Unduh `app-debug.apk` (atau `app-release.apk`) di HP.
2. Buka file → izinkan **"Instal dari sumber tidak dikenal"**.
3. Login dengan Google, buat PIN, dan mulai catat. ✨

> Aplikasi langsung jalan **tanpa API key** (mode offline). Mau AI lebih pintar?
> Tambahkan key Anda sendiri di **Pengaturan → Kunci API** (OpenRouter atau Gemini).

## 🧱 Teknologi

Kotlin · Jetpack Compose (Material 3) · Room · Firebase (Auth, Firestore) · OKHttp · Gradle/AGP modern. Tanpa server sendiri — data di perangkat, AI BYOK.

## 📚 Dokumentasi

| Dokumen | Isi |
|---|---|
| [📜 Changelog](CHANGELOG.md) | Riwayat perubahan per versi |
| [🔐 Kebijakan Privasi](PRIVACY_POLICY.md) | Data yang dikumpulkan & cara dipakai |
| [🛠️ Panduan Developer](docs/DEVELOPER.md) | Setup Firebase/SHA-1, build, CI, testing, arsitektur |
| [🏪 Checklist Play Store](docs/PLAY_STORE_CHECKLIST.md) | Langkah rilis ke Google Play |
| [🔒 Enkripsi Backup](docs/backup-encryption.md) | Detail teknis enkripsi backup Drive |

---

Dibuat dengan ❤️ oleh [@ngodingsendiri](https://github.com/ngodingsendiri)
