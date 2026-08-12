# LAPORAN PENGUJIAN MENYELURUH — Nyachat (r1.2.x, build terbaru)

> Tanggal: 2026-08-12 · Perangkat: emulator-5558 (Pixel 7a, dark mode aktif)
> Metode: instal APK debug terbaru → uji tiap menu/fitur → audit UI/UX & animasi → verifikasi piksel + accessibility tree + logcat.

---

## ✅ HASIL PER MENU / FITUR

### 1. LAYAR CHAT (DISKUSI)
| Fitur | Status | Keterangan |
|---|---|---|
| Composer pill floating (+ input pill + tombol Send circular) | ✅ | Struktur "2 elemen floating terpisah" tanpa panel besar — sesuai desain |
| Auto-grow field teks panjang | ✅ | Pill naik mengikuti keyboard, field bertambah |
| Kirim pesan | ✅ | Field bersih setelah kirim |
| Reply quote gaya Telegram | ✅ | "Membalas Budi Purnomo" + snippet menempel DI DALAM pill, aksen garis kiri |
| Menu konteks (long-press) | ✅ | Balas / Edit / Salin / Hapus |
| FAB jump-to-bottom | ✅ | Muncul saat scroll ke atas (slide dari kiri, spring lembut), hilang di dasar, tersembunyi saat mengetik, tap → lompat ke bawah |
| Chips rekomendasi | ✅ | Ter-render dengan teks (verifikasi piksel), tap chip → teks masuk field, animasi kereta dari kanan (stagger 45ms) |
| Snackbar "Tercatat" | ✅ | Posisi ATAS (minimalis), ada tombol **Urungkan**, **swipe dismiss bekerja** |
| Badge finansial di bubble | ✅ | Muncul di bawah pesan (fix BUG-1 berfungsi) |
| Grid background subtle | ✅ | Hampir polos (perbedaan 1-2 level), konsisten dark & light |
| Dark mode bubble outgoing | ✅ | `primaryContainer #005234` hijau brand — sesuai desain (bukan bug) |
| Sheet lampiran (+) | ✅ | Ambil Foto / Pilih dari Galeri / Kirim PDF |
| Photo picker Android | ✅ | Terbuka dengan benar |
| Tombol ✨ Tanya AI | ✅ | Enable hanya saat field terisi; prompt terkirim & field dibersihkan |

### 2. LAYAR REKAP
| Fitur | Status |
|---|---|
| Banner saldo + label "Tersinkron · HH:mm" | ✅ |
| Ringkasan Pemasukan/Pengeluaran + donut chart | ✅ |
| Daftar transaksi dengan kategori | ✅ |
| Dialog Catat Transaksi (12 kategori pengeluaran + pemasukan) | ✅ |
| End-to-end: chat → heuristik → transaksi masuk Rekap | ✅ |

### 3. PENGATURAN
| Fitur | Status |
|---|---|
| Struktur rapih: Profil & Akun / Umum / AI & API / Data & Backup / Zona Berbahaya | ✅ |
| Profil & Akun (nama, email, status, dialog ubah nama) | ✅ |
| PIN, Export CSV, Backup/Restore Drive, Enkripsi passphrase, Backup terakhir | ✅ |
| Zona Berbahaya: Hapus Semua Data + Logout | ✅ |

### 4. KELOLA ANGGOTA
| Fitur | Status |
|---|---|
| Ikon group di topbar → layar Kelola Anggota | ✅ |
| Permintaan Bergabung (empty state) + daftar Anggota Workspace | ✅ |
| Penanda "(kamu)" + peran (Pemilik/Anggota) | ✅ |
| Menu overflow member: Jadikan Pemilik / Hapus | ✅ |

### 5. UMUM / NAVIGASI / TEMA
| Fitur | Status |
|---|---|
| Pindah tab Chat ⇄ Rekap ⇄ Pengaturan | ✅ |
| Navbar tersembunyi saat keyboard terbuka | ✅ (perilaku disengaja) |
| Dark mode konsisten (grid, chips, badge, composer) | ✅ |
| Status bar / navbar edge-to-edge | ✅ |
| Tidak ada crash / force-close selama sesi uji | ✅ |

---

## ⚠️ TEMUAN / KEKURANGAN (per menu)

### Chat
1. **[UX] Tanya AI gagal diam-diam tanpa API key** — saat tidak ada kunci Gemini/OpenRouter (atau relay server tidak aktif), menekan ✨ hanya mengosongkan field; TIDAK ada snackbar/error/indikator kegagalan. User tidak tahu kenapa tidak ada jawaban. → Saran: tampilkan snackbar "AI belum dikonfigurasi" atau mode fallback.
2. **[Heuristik] Kategorisasi minuman lemah** — "beli jus salak 8 ribu" tercatat sebagai **Lain-lain**, bukan Makanan & Minuman (minuman/makanan seharusnya satu keluarga). Saran: tambah pola minuman (jus, kopi, teh, es, susu) ke pemetaan kategori offline.
3. **[Aksesibilitas] Teks chips tidak ter-expose di accessibility tree** — screen reader / uiautomator tidak membaca label chip (hanya kotak). Saran: pastikan `clearAndSetSemantics` tidak menghapus teks chips, atau tambahkan `contentDescription`.
4. **[Minor] FAB & chips sesekali tumpang tindih saat chips belum selesai animasi bergeser** — tap di tepi kiri bisa kena chip jika startPadding animasi belum settle. Sudah di-clamp (`coerceAtLeast(0)`), tidak crash; hanya tap ambiguity sesaat.

### Rekap
5. **[Minor] Dropdown kategori ditutup SELURUHNYA oleh tombol back** — back pertama langsung menutup dropdown penuh (tidak bertahap). Wajar untuk dialog, tapi bisa ditambah: back pertama tutup dropdown, back kedua tutup dialog.
6. **[Kosmetik] Daftar transaksi panjang** — deskripsi memakai teks mentah pesan (termasuk typo user). Bukan bug.

### Pengaturan / Umum
7. **[Minor] Back saat keyboard tertutup langsung keluar app** tanpa konfirmasi/double-back guard. Perilaku standar Android — opsional tambah toast "tekan back sekali lagi" bila diinginkan.
8. **[UX] Navbar tersembunyi saat keyboard terbuka** — user harus menutup keyboard dulu untuk pindah tab. Disengaja (ruang mengetik), tapi bisa dipertimbangkan ikon pindah tab tetap kecil di atas keyboard.

---

## 🎨 AUDIT UI/UX & ANIMASI
| Aspek | Penilaian | Catatan |
|---|---|---|
| Konsistensi warna dark mode | ✅ | Grid, chips, badge, composer, navbar semua dari token tema yang sama; primaryContainer hijau brand = desain |
| Animasi FAB | ✅ | Slide dari kiri + spring LowBouncy ±1dtk — soft |
| Animasi chips | ✅ | Kereta dari kanan, stagger 45ms/chip, fade |
| Animasi padding composer (64↔16dp) | ✅ | animateDpAsState — tidak "lompat" |
| Reply quote di dalam pill | ✅ | AnimatedVisibility expand/fade lembut |
| Snackbar | ✅ | Muncul di atas, minimal, swipeable |
| Grid background | ✅ | Subtle, hampir tak terlihat |
| Hierarchy & spacing composer | ✅ | Pill tinggi nyaman, tombol Send sejajar, tanpa panel besar |

**Kesimpulan animasi:** karakter motion sudah konsisten (soft, spring lembut, no snap) sesuai motion language `Motion.kt`. Tidak ada animasi patah yang ditemukan.

---

## 📌 DATA TEST TERSISA (di emulator, bukan repo)
- Pesan uji: "kirim ke-1..8", "test composer...", "beli jus salak 8 ribu", beberapa pesan garbled dari input `%s` (artifact alat uji, bukan bug app).
- 1 transaksi -Rp8.000 "beli jus salak" di Rekap.
- Working tree git: **bersih** (tidak ada perubahan kode — murni sesi pengujian).
