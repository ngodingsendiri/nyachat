# Checklist Rilis ke Google Play (Nyachat)

Checklist langkah demi langkah untuk mengirim **Nyachat** (r1.4.0 / versionCode 29)
ke Google Play Console. Dibuat untuk memastikan tidak ada item yang terlewat.

---

## 1. Prasyarat Build (AAB)

> AAB (Android App Bundle) release dibuat otomatis oleh GitHub Actions **hanya jika**
> secrets keystore diset di repo (`Settings → Secrets and variables → Actions`):
>
> | Secret | Nilai |
> |---|---|
> | `KEYSTORE_BASE64` | isi file `my-upload-key.jks` dalam base64 (`base64 -w0 my-upload-key.jks`) |
> | `KEYSTORE_PASSWORD` | password keystore |
> | `KEY_PASSWORD` | password key alias `upload` |
>
> *Sejak P1, secret `OPENROUTER_API_KEY` tidak lagi dipakai — tidak ada API key AI yang dibakar ke APK (murni BYOK).*

1. Pastikan semua secret di atas tersedia (khususnya keystore upload).
2. Buka tab **Actions → Build APK**.
3. Jalankan build (Workflow dispatch), atau `push` tag `r*` (r1.2.0, ...).
4. Unduh artifact **`Nyachat-r1.4.0-release-aab`** → file `app-release.aab`.
   - Artifact debug (`...-debug.apk`) & release APK juga dibuat.
5. *(Opsional lokal)* `./gradlew :app:bundleRelease` — butuh `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD`.

**Sebelum submit**: pastikan SHA-1 **release** keystore didaftarkan di Firebase
Console → project `nyachat-in` → **Pengaturan project → Aplikasi Anda →
com.startupmini.nyachat → Tambahkan sidik jari** (agar Google Sign-In jalan
di versi produksi). Cek SHA-1 dengan `keytool -list -v -keystore my-upload-key.jks -storepass PASWORD`.

---

## 2. Google Play Console → Buat Aplikasi / New Release

- Nama app: **Nyachat – Pencatat Keuangan Keluarga via Chat + AI**
- Bahasa default: **Indonesia (id)**
- App/application type: **App** (bukan Game)

### Store listing
| Field | Isi / catatan |
|---|---|
| Nama aplikasi (≤30) | `Nyachat` |
| Deskripsi singkat (≤80) | `Catat keuangan keluarga lewat chat – AI bikin rekap, analisis & saran otomatis.` |
| Deskripsi lengkap | Lihat template di bawah |
| Kategori | **Finance** |
| Email kontak | alamat Google developer aktif |

**Deskripsi lengkap (draf — sesuaikan):**
```
Nyachat membantu keluarga/kelompok mencatat keuangan lewat obrolan, seperti chat biasa.

Cukup ketik pesan sehari-hari:
• "beli kopi 20rb" → otomatis tercatat sebagai pengeluaran
• "gaji masuk 5 juta" → tercatat sebagai pemasukan

Fitur utama:
• Pencatatan otomatis via chat + AI (Free: OpenRouter/Gemini)
• Rekap visual: saldo, diagram donat per kategori, progress bar alokasi
• Analisis AI bulanan: tren pengeluaran & rekomendasi penghematan
• Foto nota belanja: AI membaca struk & mencatat totalnya
• Workspace bersama via PIN: beberapa perangkat saling terhubung
• Peran anggota: pemilik mengatur anggota & label (Suami/Istri/Bendahara)
• Export rekap CSV (Excel / Google Sheets)
• Backup & restore Google Drive
• Mode gelap

Semua transaksi tersimpan di perangkat (offline-first). Sinkronisasi antar perangkat
dibuat aman dengan keanggotaan berbasis PIN + akun Google.
```

---

## 3. Screenshot & Aset

Screenshot wajib (ukuran), sangat disarankan di perangkat nyata:
- **Phone (mandatory):** 1 screenshot ≥ 640×480 & ≤ 8192×8192, minimal 2.
- Gunakan **applicationId `com.startupmini.nyachat`** di emulator/perangkat.
- Saran 6–8 screenshot: layar login, obrolan transaksi, rekap (donut), analisis
  bulanan, kelola anggota, pengaturan/backup, **badge provenance AI/heuristik**, **workspace switch**.
- **Icon:** 512×512 (logo aplikasi) + adaptive icon 32-bit.
- **Feature graphic (opsional tapi disarankan):** 1024×500.
- **Video promo (opsional):** YouTube link.

---

## 4. Data Safety & Content (wajib akurat)

Formulir "Data safety" di Console → **Kebijakan → Data safety & content rating**.

- **Data dikumpulkan**: sebagian data *tidak*dikumpulkan/dikirim; sebagian disimpan
  di perangkat; header pembayaran tidak ada. Kelompokkan sesuai formulir.
- **Data personal yang valid diminta**: akun Google. Data dikirim **hanya** ke
  Firestore milik app untuk sinkronisasi antar perangkat workspace.
- **Content rating**: isi kuesioner (app untuk umum / semua usia; tidak ada konten
  dewasa, kekerasan, atau judi).
- **Target audience**: Keluarga umum (16+ atau Semua). Tidak ada pembelian maupun iklan.

> Setiap perubahan alur AI/analisis/data yang menyangkut pengumpulan data harus
> dicek ulang di formulir Data safety sebelum update besar.

---

## 5. Kebijakan Privasi

- Sediakan **kebijakan privasi publik** (wajib di listing).
- Repo sudah punya `PRIVACY_POLICY.md` → hosting sebagai halaman publik (mis.
  GitHub Pages / gist) lalu tempel URL di kolom **Privacy policy**.
- Isi minimum yang wajib ada di kebijakan: data apa yang dikumpulkan, bagaimana
  dipakai, dengan siapa dibagikan, cara menghapus data, & kontak.

---

## 6. Akun, Hak Akses & Kepatuhan

- **Akun yang bisa dihapus**: pengguna dapat keluar & "Keluar & Hapus Data".
  Sebutkan jalur ini di Data safety & kebijakan (memenuhi kebijakan penonaktifan akun).
- **Permissions framework**: app memakai koneksi internet (crucial), kamera/penyimpanan
  (untuk foto nota) — deskripsikan alasannya di formulir permissions jika diminta.
- **Google Sign-In**: pastikan SHA-1 release terdaftar di Firebase (lihat §1).
- **App Signing (Play App Signing)**: nyalakan; Google mengelola kunci berikutnya.

---

## 7. Unggah AAB & Review

1. Console → **Release → Production → Buat rilis baru** → buat release tracks
   (Production, atau Internal Testing untuk uji awal).
2. Unggah `app-release.aab`.
3. Catatan rilis: versi `r1.4.0` – `Auto-connect workspace: login Google otomatis masuk ke workspace milik akun tanpa PIN (1 akun = 1 workspace). Keluar dari Workspace baru di Settings (owner satu-satunya wajib promote dulu). Fix: workspace hilang saat login ulang akun sama. Avatar foto profil di bubble chat + ekstraksi multi-transaksi yang akurat. 439 test hijau, lint bersih.`
4. Terbitkan → tunggu review Google (biasanya jam–hari).

---

## 8. Firestore Rules (TIDAK boleh terlewat!)

Rules keanggotaan baru **wajib di-deploy** setelah atau sebelum rilis ini, kalau
tidak data hanya bisa diakses bagian lama:
```bash
firebase auth:login
firebase deploy --only firestore:rules  # upload firestore.rules
# atau: paste isi firestore.rules di Console → Firestore → Rules
```

**Catatan migrasi**: workspace yang sudah ada sebelumnya — pemilik akan otomatis
terdaftar ulang (backfill). Anggota non-pemilik harus diminta bergabung lagi oleh
pemilik lewat layar **Kelola Anggota** (alur PIN + persetujuan).

---

## 9. Uji Pra-Rilis (smoke test)

- [ ] Login Google + buat workspace (owner)
- [ ] Anggota bergabung via PIN → tunggu persetujuan owner
- [ ] Setujui/tolak permintaan di Kelola Anggota
- [ ] Ubah label & peran anggota
- [ ] Catat transaksi via chat (AI & offline)
- [ ] **Badge transaksi tampil "AI" atau "heuristik" (M7)**
- [ ] Rekap evaluasi + analisis Bulanan
- [ ] Export CSV, backup/restore Drive
- [ ] **Auto-backup terenkripsi jalan tanpa prompt (M5)**
- [ ] **Ganti workspace → lampiran workspace lama tidak terhapus (M9)**
- [ ] Mode gelap