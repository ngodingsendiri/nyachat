# Laporan Live Test — Edit Pesan Lintas Perangkat (3.4) & Backup/Restore Drive (3.5)

> Live test di **2 emulator paralel** (Pixel_7a / emulator-5554 = device A, Pixel_7a_b / emulator-5556 = device B, 1080×2400) via ADB + uiautomator dump + forensik DB (Python/sqlite3).
> Tanggal: 2026-08-09 · APK: `r1.1.3` (versionCode 26) debug.
> Konteks: verifikasi FASE 3 `implementation_plan_r1.2.0.artifact.md` item 3.4 & 3.5 (M5).

---

## 1. Test 3.4 — Edit pesan tersinkron lintas perangkat (LWW)

### Hasil: ✅ PASS TOTAL

**Setup:**
- AVD `Pixel_7a_b` = clone dari `Pixel_7a` → emulator kedua (`emulator-5556`), login akun Google sama → workspace & data tersinkron otomatis dari Firestore.
- Baseline device B: pesan "beli gorengan 8000" (belum diedit).

**Langkah test:**
1. Device A: long-press bubble → **Edit Transaksi** → ubah keterangan `beli gorengan 8000` → `beli gorengan 8500`, nominal Rp8.000 → Rp8.500 → **Simpan Perubahan**.
2. Amati device B secara real-time.

**Hasil:**
| Verifikasi | Hasil |
|---|---|
| Penanda "16:06 • diedit" muncul di bubble A | ✅ |
| **Real-time sync ke B** (~detik, tanpa restart) | ✅ "16:06 • diedit" muncul di B |
| Teks pesan diedit di A | ✅ "beli gorengan 8500" |
| Teks pesan diedit di B | ✅ "beli gorengan 8500" |
| Transaksi ikut ter-update di B (Rp8.000 → Rp8.500) | ✅ |

**Forensik DB device B (bukti kuat LWW):**
```
(9, 'beli gorengan 8500', editedAt✓, serverUpdatedAt✓, amount 8500)
```
- `editedAt` terisi ✓ + `serverUpdatedAt` terisi ✓ → mekanisme **Last-Write-Wins via server timestamp (M4)** terbukti bekerja end-to-end.

---

## 2. Test 3.5 — Backup/restore Google Drive dengan akun nyata

### 2.0 Blocker & penyelesaian
- Awalnya backup gagal: **403 `accessNotConfigured`** — Google Drive API **belum aktif** di Google Cloud project `340343053987` (`nyachat-in`).
- OAuth consent WebView berhasil (akun `ngampusendiri@gmail.com`, scope drive.file + drive.appdata) tapi upload ditolak API.
- **Penyelesaian**: Drive API diaktifkan via `gcloud services enable drive.googleapis.com --project=340343053987` (disetujui user) → backup langsung berhasil.

### 2.1 Backup manual (plain) — device A
| Langkah | Hasil |
|---|---|
| Settings → Backup ke Google Drive | ✅ Progress dialog "Menyiapkan data & menghubungkan Google Drive" |
| Upload | ✅ Selesai, kembali ke app |
| Verifikasi file di Drive | ✅ Restore picker menampilkan `Nyachat-backup-20260809-183501.json` |

### 2.2 Restore di device yang sama (A) — bukti data kembali
1. Rekap → menu ⋯ → **Hapus** transaksi "beli gorengan 8500" (Rp8.500) → saldo turun Rp8.500 (Rp4.213.401 → Rp4.204.901).
2. Settings → Restore → pilih `Nyachat-backup-20260809-183501.json` → Pulihkan.
3. **Hasil**: transaksi "beli gorengan 8500" **kembali** (+Rp8.500), data utuh. ✅

### 2.3 M5 — Backup terenkripsi (passphrase)
| Langkah | Hasil |
|---|---|
| Toggle "Enkripsi backup Drive" ON | ✅ (label "Backup terakhir 18:35 · Terenkripsi" muncul — lihat temuan) |
| Backup → dialog "Enkripsi Backup" minta passphrase | ✅ |
| Isi `rahasia123` → upload | ✅ `Nyachat-backup-20260809-184131.json` sukses, label "18:41 · Terenkripsi" |
| Restore file terenkripsi dengan passphrase benar | ✅ Data pulih |
| Restore dengan passphrase SALAH (8+ char, `salah999`) | ✅ **Ditolak** — dialog tutup, data tidak berubah (saldo tetap Rp803.599) |
| Validasi passphrase < 8 karakter | ✅ Ditolak di sisi validasi (min 8) |

### 2.4 Restore ke device kedua (B) — bukti lintas perangkat
1. Emulator B di-restart (cold boot; app tetap terpasang).
2. Baseline B sebelum restore: menampilkan riwayat sinkron (pesan "beli kopi 25000", "tes draf 333", dll) — **tanpa** riwayat lama "beli 20:27/20:32/20:33, beli kopi 20rb 20:36" dari A.
3. Settings B → Restore → pilih backup plain `183501` → selesai.
4. **Forensik DB B setelah restore:**
```
chat_messages: 10 pesan (id 11–20) — termasuk riwayat lama dari A
               yang SEBELUMNYA TIDAK ADA di B ✅
financial_transactions: 6 baris identik dengan backup
               (gorengan 8500, kopi 25000, kopi 20rb, gaji 5jt, ...) ✅
Pesan "beli gorengan 8500" → editedAt tetap terisi ✅
```
5. **Kesimpulan: restore lintas perangkat terbukti — data device A pulih penuh di device B.**

### Screenshot bukti
- `.artifact/live_shots/consent_drive.png`, `consent2.png`, `consent3.png` (alur OAuth consent WebView)
- `.artifact/live_shots/b_restore_1.png` (device B pasca-restore)

---

## 3. Temuan & catatan (masuk backlog)

| # | Temuan | Severity | Catatan |
|---|---|---|---|
| 1 | **Label "· Terenkripsi" bisa menyesatkan**: backup pertama (18:35) dibuat saat toggle enkripsi OFF, tapi setelah toggle ON label backup terakhir berubah jadi "18:35 · Terenkripsi" — status enkripsi ditampilkan dari *setting saat ini*, bukan dari file backup aktual | 🟡 Minor | ✅ **FIXED 2026-08-09** — verifikasi live di bawah |
| 2 | **Drive API tidak aktif dari awal** di project Google Cloud `340343053987` | 🟡 Ops | Sudah diaktifkan; tambahkan ke dokumentasi setup supaya tidak terulang |
| 3 | Passphrase salah dengan 8+ karakter ditolak tanpa pesan error spesifik (dialog hanya menutup) | 🟢 Low | ✅ **FIXED 2026-08-09** — lihat verifikasi live di bawah |
| 4 | Picker restore menampilkan 5 backup terbaru tanpa indikator terenkripsi/tidak | 🟢 Low | ✅ **FIXED 2026-08-09** — lihat verifikasi live di bawah |
| 5 | Subtitle Settings "Auto-backup harian dijeda saat aktif" tidak akurat sejak M5 (auto-backup kini jalan dengan passphrase otomatis Keystore) | 🟢 Low | ✅ **FIXED 2026-08-09**: `settings_backup_encrypt_desc` → "Auto-backup harian tetap berjalan terenkripsi otomatis." — terverifikasi live di Settings (screenshot `live_shots/settings_subtitle_autobackup_fixed.png`) |

### Verifikasi live FIXED #3 — snackbar "Passphrase salah" (2026-08-09, device A)

1. Restore backup terenkripsi `Nyachat-backup-20260809-204145.enc.json` → prompt passphrase → isi `salah999` (8 karakter, lolos validasi min) → OK.
2. t+1s: modal "Memproses…" (KDF berjalan) — belum ada snackbar.
3. **t+3s: snackbar tampil** `Passphrase salah. Coba lagi dengan passphrase yang dipakai saat backup dibuat.` — durasi **Long (10s)**, modal progres sudah tertutup, snackbar tidak tertutup dialog. ✅
4. Auto-dismiss setelah ~10s ✅ · Data lokal **tidak berubah** (restore tidak diterapkan).
5. Bukti: `.artifact/live_shots/passphrase_salah_t0.png` (sebelum snackbar), `.artifact/live_shots/passphrase_salah_t3.png` (snackbar tampil).

Akar masalah sebelumnya: pesan error dikirim lewat `message` → snackbar durasi Short yang tampil SELAGAI modal progres masih terbuka (tersembunyi di balik dialog) → tidak teramati. Perbaikan: saluran khusus `passphraseError` + `busy=false` dulu + durasi Long.

### Verifikasi live FIXED #1 — label "Backup terakhir" (2026-08-09, device A)

Matriks pengujian (toggle vs status FILE aktual):

| Langkah | Toggle | Backup baru | Label "Backup terakhir" | Verdict |
|---|---|---|---|---|
| Baseline (file 20:37 terenkripsi) | ON | — | `20:37 · Terenkripsi` | ✅ |
| **Toggle OFF tanpa backup** | OFF | — | `20:37 · Terenkripsi` (**tetap**) | ✅ inti fix — sebelumnya salah jadi "Tanpa enkripsi" |
| Backup plain (20:40) | OFF | ✅ plain | `20:40 · Tanpa enkripsi` | ✅ berubah sesuai FILE |
| **Toggle ON tanpa backup** | ON | — | `20:40 · Tanpa enkripsi` (**tetap**) | ✅ arah sebaliknya — label ikut toggle lagi |
| Backup terenkripsi (20:41) | ON | ✅ terenkripsi | `20:41 · Terenkripsi` | ✅ berubah sesuai FILE |

Bukti: `.artifact/live_shots/settings_backup_label_encrypted.png` (label `20:41 · Terenkripsi` dengan toggle ON).

### Verifikasi live FIXED #4 (2026-08-09, device A)

1. Backup terenkripsi baru (passphrase `rahasia123`, toggle ON) → `Nyachat-backup-20260809-203724.enc.json` di-upload.
2. Buka Settings → Restore → picker menampilkan:
   - `…203724.enc.json` → badge `🔒 Terenkripsi` (penanda nama/metadata) ✅
   - `…184131.json` (backup terenkripsi LAMA dari live test 3.5, tanpa metadata) → badge `🔒 Terenkripsi` via **probe isi** ✅
   - `…184811.json` & `…183501.json` (plain) → tanpa badge ✅
3. Bukti: `.artifact/live_shots/restore_picker_badge_lock.png`.

---

## Ringkasan status FASE 3 verifikasi

| Item | Status |
|---|---|
| 3.4 Edit pesan lintas perangkat (LWW) | ✅ **PASS** |
| 3.5 Backup Drive manual (plain) | ✅ **PASS** |
| 3.5 Restore device sama | ✅ **PASS** |
| 3.5 M5 backup terenkripsi + restore benar/salah passphrase | ✅ **PASS** |
| 3.5 Restore ke device kedua | ✅ **PASS** |

*Laporan berbasis pengujian live nyata (adb/uiautomator + forensik DB + screenshot), bukan analisis statis.*
