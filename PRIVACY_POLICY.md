# Kebijakan Privasi — Nyachat

Terakhir diperbarui: 8 Agustus 2026

Kebijakan privasi ini menjelaskan bagaimana aplikasi **Nyachat** ("aplikasi", "kami")
mengumpulkan, menggunakan, dan melindungi data Anda. Dengan menggunakan aplikasi ini,
Anda menyetujui praktik yang dijelaskan di bawah ini.

## 1. Data yang Kami Proses

- **Pesan dan riwayat pembicaraan** yang Anda atau anggota keluarga ketik, termasuk
  pesan yang berisi catatan keuangan (nominal, kategori, jenis transaksi).
- **Transaksi keuangan** (pemasukan/pengeluaran, kategori, deskripsi, pencatat) yang
  direkam secara manual atau terdeteksi dari pesan.
- **Lampiran** (foto nota belanja dan dokumen seperti PDF/invoice) yang Anda lampirkan.
- **Informasi akun Google** (nama tampilan dan alamat email) yang digunakan untuk
  masuk (sign-in).
- **Kunci API AI** yang Anda masukkan secara manual di menu Pengaturan (lihat bagian 5).

## 2. Penggunaan Data

Data digunakan untuk:

- Menyimpan dan menampilkan riwayat chat serta rekap keuangan Anda;
- Menyinkronkan data workspace keluarga antar perangkat yang menggunakan PIN yang sama;
- Menganalisis pesan untuk deteksi otomatis transaksi keuangan (dengan bantuan AI);
- Membuat laporan/analisis keuangan dan saran hemat;
- Membuat cadangan (backup) ke Google Drive (jika Anda memilih);
- Memperbarui aplikasi (hanya di versi pengembangan/debug).

## 3. Penyimpanan Data

- **Data lokal:** Sebagian besar data (pesan, transaksi, lampiran) disimpan secara
  lokal di perangkat Anda dalam database aplikasi ("Room"). Lampiran (foto/dokumen)
  hanya disimpan di perangkat yang mengirimnya dan **tidak** ikut tersinkron.
  Lampiran di-namespace per workspace (folder `filesDir/attachments/<PIN>/`) sehingga
  ganti workspace tidak menghapus lampiran workspace lain.
- **Cloud (Firebase Firestore):** Jika Anda mengaktifkan sinkronisasi, pesan dan
  transaksi disinkronkan ke cloud Firebase. Cloud dipetakan berdasarkan **PIN keluarga**
  (bukan nama atau identitas pribadi). Akses dilindungi dengan wajib masuk menggunakan
  akun Google dan aturan keamanan Firestore.
  Sinkronisasi memakai **last-writer-wins deterministik berbasis server timestamp**
  (`FieldValue.serverTimestamp()`) — immune terhadap selisih jam antar-perangkat.
- **Google Drive:** Jika Anda memilih backup ke Google Drive, salinan data chat dan
  transaksi disimpan di akun Google Drive Anda.
  Auto-backup harian sekarang jalan walau enkripsi aktif — pakai passphrase otomatis
  dari Android Keystore (tidak ada interaksi manual; data tetap terenkripsi saat upload).

## 4. Akun Google

Anda masuk menggunakan akun Google (melalui Credential Manager / Firebase Authentication).
Kami menyimpan nama tampilan dan alamat email untuk identifikasi di dalam workspace, dan
menggunakannya sebagai syarat akses ke cloud. Kami tidak memposting ke akun Anda tanpa izin.

## 5. Pemrosesan AI (Bring Your Own Key)

Fitur kecerdasan buatan (AI) menggunakan **kunci API Anda sendiri** (BYOK — "bawa kunci
sendiri") atau kunci yang Anda masukkan di menu Pengaturan untuk penyedia:
- **Google Gemini** (Google AI / Generative Language API), dan
- **OpenRouter**.

- Kunci Anda **tersimpan hanya di perangkat Anda** dan dikirim langsung ke penyedia
  tersebut saat pemrosesan.
- Saat Anda menyediakan kunci sendiri, pemrosesan pesan Anda tunduk pada **Ketentuan dan
  Kebijakan Privasi dari penyedia tersebut** (Google AI, OpenRouter, dan model-model yang
  dipilih di dalamnya).
- Aplikasi ini menghasilkan laporan/parsing dengan mengirim isi pesan Anda ke penyedia AI.
  Sebaiknya jangan menulis informasi pribadi sensitif (nomor identitas, data kesehatan,
  data anak di bawah umur) di dalam pesan.
- **Transparansi asal deteksi:** badge transaksi di chat menampilkan label **"AI"** (diproses
  Gemini/OpenRouter) atau **"heuristik"** (mesin aturan lokal/offline fallback) — Anda
  selalu tahu nilai diproses mesin mana.

## 6. Izin yang Digunakan

- **Internet:** untuk masuk dengan Google, sinkronisasi cloud, backup Drive, dan pemrosesan AI.
- **Kamera / Galeri:** untuk mengambil/memilih foto nota (hanya saat Anda melampirkan).

## 7. Penghapusan Data

- **Data lokal per perangkat:** Anda dapat menghapus semua data lokal, lampiran, dan cloud
  melalui menu **Pengaturan → Keluar → "Keluar & Hapus Data"**.
- Startup ini juga menyediakan penghapusan akun/data sesuai ketentuan Google Play: dengan
  memilih "Keluar & Hapus Data", seluruh data lokal dan data workspace di cloud dihapus
  secara permanen dan tidak dapat dipulihkan.
- Menghapus aplikasi dari perangkat tidak serta-merta menghapus data cloud yang sudah
  tersinkron; gunakan fitur penghapusan di dalam aplikasi untuk menghapus data cloud.

## 8. Keamanan

- Akses cloud memerlukan login Google dan dibatasi oleh aturan keamanan Firebase.
- Kami tidak menyimpan kunci API AI atas nama Anda; kunci disimpan di perangkat Anda.
- Namun, mohon dipahami bahwa saat Anda berbagi **PIN keluarga**, siapa pun yang memiliki
  PIN tersebut dapat mengakses workspace yang sama.

## 9. Data Anak

Aplikasi ini tidak ditujukan untuk anak-anak di bawah umur yang disyaratkan peraturan,
dan kami tidak dengan sengaja mengumpulkan data anak-anak. Jangan menyertakan data
anak-anak dalam pesan.

## 10. Perubahan Kebijakan

Kami dapat memperbarui kebijakan ini dari waktu ke waktu. Versi terbaru akan selalu
tersedia di halaman ini dengan tanggal pembaruan di bagian atas.

## 11. Kontak

Untuk pertanyaan tentang privasi, hubungi kami melalui halaman proyek ini di GitHub.

---

_Disarankan untuk direview oleh pihak berwenang/layanan hukum sebelum dipublikasikan di
Google Play._