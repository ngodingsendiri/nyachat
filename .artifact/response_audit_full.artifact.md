# FULL FEATURE & MENU RESPONSE AUDIT — NYACHAT
**Tanggal:** 2026-08-12 · **Status:** AUDIT ONLY (belum ada perubahan kode)

> Prinsip: *Setiap action bermakna harus punya response bermakna — tidak ada action
> senyap, tidak ada feedback membingungkan, tidak ada feedback berlebihan.*
> Audit dilakukan dengan telusur kode jalur action → state → feedback → result.

---

## 0. Ringkasan Eksekutif

**Skor kesehatan response system: 4/5.** Nyachat sudah punya response language yang
matang di sebagian besar alur: undo snackbar untuk transaksi, indikator AI berpikir
yang benar, fallback offline di SEMUA jalur AI, form dengan inline validation,
konfirmasi untuk semua aksi destruktif, dan status sync yang jujur.

Ditemukan **1 dead response nyata (P1)**, beberapa **feedback yang kurang
proporsional (P2)**, dan sejumlah **inconsistency kecil (P2-P3)**.

**Prioritas temuan:**
- **P1 — 1** (dead response sync recovery)
- **P2 — 5**
- **P3 — 5**

---

## 1. RESPONSE MATRIX

Format: `Feature | Action | Expected | Actual | Response Type | Latency | Problem | Recommendation | Priority`

### 1.1 Chat & AI

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| Chat | Kirim pesan teks | Pesan tampil + AI proses | Pesan tampil instan + AiThinkingBubble (spinner "AI berpikir") | Visual state | Instant | — | — | — |
| Chat | Kirim pesan berisi transaksi | Tercatat + konfirmasi | Snackbar **"Tercatat + Urungkan"** (ringkasan nominal+kategori) | Toast L3 | ~detik | — | — | — |
| Chat | Kirim 2 pesan beruntun | Indikator AI tetap menyala sampai selesai | Counter (`AiThinkingCounter`) — off hanya saat counter 0 | Visual state | — | — | — | — |
| Chat | Parse AI gagal / timeout | Tidak menggantung | Timeout 60s → fallback heuristik offline (pesan tetap tampil) | Fallback | ≤60s | — | — | — |
| Chat | Tombol ✨ Tanya AI | Jawaban AI | `askAiChat` → OpenRouter→Gemini→**balasan offline jelas** ("mode AI sedang offline...") | Bubble AI | ≤60s | — | — | — |
| Chat | Pertanyaan finansial ("hari ini keluar berapa?") | Jawab dengan data nyata | Jawaban berbasis DB (AI atau offline — keduanya angka asli) | Bubble AI | — | — | — | — |
| Chat | Balas pesan (swipe/menu) | Quote muncul di composer | `ReplyQuoteRow` di dalam pill + animasi expand | Micro L2 | Instant | — | — | — |
| Chat | Edit pesan | Perubahan tampil | Bubble berubah seketika (transaksi ikut rebuild) | Visual state | Instant | Tanpa konfirmasi/undo — wajar | — | — |
| Chat | Hapus pesan | Konfirmasi dulu | `AlertDialog` konfirmasi → hapus seketika (tanpa undo) | Modal L5 | — | Proporsional | — | — |
| Chat | Pilih foto/PDF lampiran | Pratinjau muncul | **TANPA loading** — `saveImageFromUri` di coroutine; pratinjau muncul setelah selesai; foto besar = user menunggu tanpa umpan balik | **Silent** | 0.5–3s | ⚠️ **DEAD RESPONSE** — "apakah tombol saya tadi berhasil?" | Tampilkan progress kecil/inline saat menyimpan lampiran | P2 |
| Chat | Kirim lampiran | Pesan + lampiran tampil | Pesan tampil, lampiran disimpan lokal (info "lampiran tidak sinkron") | Visual state | — | — | — | — |
| Chat | Scroll ke atas | FAB "ke pesan terbaru" | FAB muncul (animasi soft) + chips bergeser | Micro L2 | — | — | — | — |
| Chat | Kosong (belum ada diskusi) | Empty state menjelaskan | Ikon + "Belum ada diskusi" + "Kirim pesan atau catat transaksi untuk memulai" | Empty | — | — | — | — |
| Chat | Kirim saat teks kosong | Tombol tidak aktif | `canSend=false` — tombol Send disabled + warna redup | Disabled | — | — | — | — |

### 1.2 Rekap

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| Rekap | Pindah bulan | Label berubah + angka update | Crossfade label + saldo (motion) | Visual state | Instant | — | — | — |
| Rekap | Hapus transaksi (swipe kiri / menu) | Konfirmasi dulu | `AlertDialog` konfirmasi → hapus + badge pesan di-update | Modal L5 | — | Tanpa undo (beda dengan create) | Opsional: undo snackbar | P3 |
| Rekap | Edit transaksi (swipe kanan / menu) | Form terisi | Dialog AddTransaction dengan nilai lama → simpan = update + badge sinkron | Modal L5 | — | Tanpa snackbar sukses — cukup visual (angka berubah) | — | — |
| Rekap | Generate laporan bulanan/audit AI | Spinner + hasil | Tombol disabled + spinner inline → laporan bottom sheet (atau fallback offline) | Progress L3 | ≤60s | — | — | — |
| Rekap | AI report GAGAL | Pesan jelas | ViewModel set `_auditReport = "Gagal memuat laporan, silakan coba lagi."` — **muncul sebagai isi sheet** | Error L4 | — | ⚠️ **Error generik** + tampil di sheet (bukan snackbar/inline) | Tampilkan penyebab + tombol coba lagi di sheet | P2 |
| Rekap | Cari/filter kosong | Empty state | "Tidak Ditemukan" + "Coba kata kunci lain atau ubah filter" | Empty | — | — | — | — |
| Rekap | Kosong total | Empty state | "Belum Ada Transaksi" + hint (kirim chat / Tambah) | Empty | — | — | — | — |
| Rekap | Tambah manual | Simpan + konfirmasi | Snackbar "Tercatat + Urungkan" (sama dengan chat) ✅ konsisten | Toast L3 | — | — | — | — |
| Rekap | Input nominal invalid (≤0) | Inline error + save disabled | Cek inline + tombol disabled | Inline L4 | — | — | — | — |
| Rekap | Input deskripsi kosong | Save disabled | Tombol disabled sampai terisi | Disabled | — | — | — | — |

### 1.3 Settings & Profil

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| Settings | Toggle mode gelap | Berubah seketika | Tema swap instan (bisa berkedip? — sudah pernah diaudit, OK) | Visual state | Instant | — | — | — |
| Settings | Toggle notifikasi chat | Tersimpan | Pref disimpan; tanpa snackbar (cukup toggle) | Visual state | Instant | — | — | — |
| Settings | Toggle enkripsi backup | Tersimpan | Pref disimpan; status label "Backup terakhir terenkripsi" mencerminkan file aktual | Visual state | Instant | — | — | — |
| Settings | Simpan API key Gemini/OpenRouter | Konfirmasi | Dialog tertutup; **tanpa snackbar sukses** | Modal L5 | — | ⚠️ User tidak yakin tersimpan? (dialog close = sukses, tapi tak ada konfirmasi eksplisit) | Snackbar kecil "Kunci disimpan" | P3 |
| Settings | Salin PIN workspace | Feedback | Snackbar "PIN disalin" ✅ | Toast L3 | Instant | — | — | — |
| Profil | Ganti nama kosong | Error inline | `isError` + "Nama tidak boleh kosong" + save disabled | Inline L4 | — | — | — | — |
| Profil | Ganti avatar (gagal simpan) | Feedback | Snackbar "avatar_save_failed" ✅ | Toast L3 | — | — | — | — |
| Profil | Avatar sync ke cloud | Diam (benar — background) | Silent; hanya saat path berubah | Background | — | — | — | — |
| Settings | Hapus semua data | Konfirmasi kuat | `ConfirmClearDataDialog` + label destructive | Modal L5 | — | — | — | — |
| Settings | Logout (hapus data / simpan) | Konfirmasi | `LogoutDialog` 2 pilihan (hapus/pertahankan) | Modal L5 | — | — | — | — |

### 1.4 Backup & Restore Google Drive

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| Backup | Backup ke Drive | Progress + selesai | `BackupProgressDialog` (spinner + tombol Batal) → snackbar info | Progress L3 | Long | — | — | — |
| Backup | Backup terenkripsi | Minta passphrase | `PassphraseDialog` (min 8 digit, save disabled) | Modal L5 | — | — | — | — |
| Restore | Pilih file backup | Daftar file | `RestorePickerDialog` (dengan badge 🔒 terenkripsi) | Modal L5 | — | — | — | — |
| Restore | Konfirmasi restore | Overwrite jelas | `RestoreConfirmDialog` | Modal L5 | — | — | — | — |
| Restore | Backup dari workspace lain | Konfirmasi ekstra | `CrossFamilyRestoreDialog` | Modal L5 | — | — | — | — |
| Restore | Passphrase salah | Error jelas | Snackbar khusus "Passphrase salah" (Long) ✅ | Toast L3 | — | — | — | — |
| Backup | Batal saat proses | Bisa dibatalkan | Tombol Batal → `cancelActiveOperation` | Progress | — | — | — | — |
| Backup | Auto-backup (background) | Tenang | Silent; Settings menampilkan "Backup terakhir" + status enkripsi | Background | — | — | — | — |
| Backup | Token Drive gagal | Error | Snackbar error token (dengan detail) | Toast L3 | — | — | — | — |

### 1.5 Keanggotaan & Gate

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| Gate | Cek status | Spinner | `CircularProgressIndicator` 44dp + teks | Progress L3 | — | — | — | — |
| Gate | Permintaan join gagal/tolak | Feedback | Error state di gate | Error L4 | — | — | — | — |
| Kelola | Approve join request | Member masuk + UI update | List berubah seketika (duplicate-key crash sudah diperbaiki) | Visual state | — | — | — | — |
| Kelola | Rename member kosong | Error inline | Save disabled | Inline L4 | — | — | — | — |
| Kick | User di-kick (device lain) | Langsung keluar + tahu kenapa | **Snackbar "kamu dikeluarkan"** + kembali ke PIN (tanpa menunggu resume) ✅ | Toast L3 | Real-time | — | — | — |
| Kelola | Kosong (belum ada anggota) | Empty state | "Belum ada anggota lain. Bagikan PIN untuk mengundang." + "Tidak ada permintaan bergabung" | Empty | — | — | — | — |

### 1.6 Onboarding / PIN / Login

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| PIN | Masuk Google | Loading + error jelas | Tombol → "Masuk..." (disabled) + `authError` inline dengan **penyebab spesifik + hint SHA-1** ✅ | Inline L4 | Medium | — | — | — |
| PIN | Join dengan PIN salah/lockout | Rate limit jelas | `PinAttemptLimiter` + pesan sisa waktu tunggu | Inline L4 | — | — | — | — |
| PIN | Input PIN non-digit | Disaring | Filter digit otomatis + max length | Inline L4 | — | — | — | — |
| PIN | PIN < panjang minimum | Tombol disabled | Join disabled sampai 6 digit | Disabled | — | — | — | — |
| PIN | Batal Google / error kredensial | Diam / jelas | Cancellation = diam (benar); error = inline | Inline L4 | — | — | — | — |
| PIN | Step alur PIN | Transisi | Crossfade ringan (audit motion) | Micro L2 | — | — | — | — |

### 1.7 Sync & Background

| Feature | Action | Expected | Actual | Type | Latency | Problem | Rekomendasi | Prio |
|---|---|---|---|---|---|---|---|---|
| Sync | Status indikator | Jujur | SYNCED/SYNCING/OFFLINE/ERROR + "Tersinkron · HH:mm"; offline = netral (bukan alarm palsu) ✅ | Visual state | Real-time | — | — | — |
| Sync | Koneksi pulih (OFFLINE→SYNCED) | **Snackbar "Sinkron tersambung kembali"** | ⚠️ **`recoveryEvents` DI-EMIT tapi TIDAK PERNAH DIKOLEKSI di UI** — komentar kode bilang "UI menampilkan Snackbar" tapi wiring tidak ada | **DEAD RESPONSE** | — | 🔴 **P1 — janji feedback tak dipenuhi** | Koleksi `recoveryEvents` di MainActivity → `showSnack` | **P1** |
| Sync | Kirim saat offline | Tidak hilang | Pesan masuk antrian pending → ter-drain saat online; indikator OFFLINE | Background | — | — | — | — |
| Saran | Refresh suggestion | Diam (cooldown 15 menit) | Silent — tanpa indikator (benar, background) | Background | — | — | — | — |
| Update | Cek update saat buka | Diam jika tidak ada | Silent jika up-to-date (benar) | Background | — | — | — | — |
| Update | Cek manual | Info | Snackbar "Tidak ada pembaruan" / dialog update | Toast/Modal | — | — | — | — |
| Update | Unduh APK | Progress | `isDownloadingUpdate` state di dialog + tombol disabled | Progress L3 | Long | — | — | — |

---

## 2. DEAD RESPONSE (prioritas audit)

| # | Lokasi | Masalah | Dampak | Prio |
|---|---|---|---|---|
| D1 | `FirestoreSyncManager.recoveryEvents` | Event "Sinkron tersambung kembali." **di-emit tapi tidak pernah di-koleksi** di lapisan UI mana pun — komentar kode menjanjikan snackbar yang tidak pernah ada | User tidak pernah diberi tahu bahwa koneksi sudah pulih; indikator berubah diam-diam | **P1** |
| D2 | Chat: simpan lampiran foto/PDF | `saveImageFromUri` di coroutine tanpa loading — untuk file besar user menunggu tanpa umpan balik | Momen "apakah tombol saya tadi berhasil?" | P2 |
| D3 | Simpan API key | Dialog tertutup tanpa konfirmasi eksplisit bahwa kunci tersimpan | Ketidakpastian (minor) | P3 |
| D4 | AI report gagal | Pesan generik "Gagal memuat laporan, silakan coba lagi." muncul sebagai ISI SHEET (bukan feedback) | Error generik tanpa penyebab/tindakan; tampil di tempat yang tidak jelas | P2 |

## 3. RESPONSE HIERARCHY — Audit

| Level | Status | Catatan |
|---|---|---|
| L1 Visual state | ✅ Baik | Toggle, warna tombol Send/✨, FAB, badges |
| L2 Micro | ✅ Baik | Ripple, animateColor, crossfade angka, quote expand |
| L3 Toast/Snackbar | ⚠️ 1 lubang | "Tercatat+Urungkan", PIN disalin, CSV, backup, kicked — lengkap; **sync recovery hilang (D1)** |
| L4 Inline | ✅ Baik | Error form (nama, amount, PIN, rate limit, auth), isError |
| L5 Modal | ✅ Baik | Hanya untuk keputusan penting (delete, restore, logout, clear, passphrase) — **tidak ada over-use modal** |

**Tidak ada kasus "modal untuk hal yang cukup toast"** — hierarki sehat.

## 4. KONSISTENSI RESPONSE

| Pola | A | B | C | Verdict |
|---|---|---|---|---|
| Catat transaksi (chat/manual/AI) | Semua → "Tercatat+Urungkan" | Sama | Sama | ✅ Konsisten |
| Hapus | Confirm dialog → hapus instan, tanpa undo | Sama di chat & rekap | Sama | ✅ Konsisten (tanpa undo = ok, ada confirm) |
| AI action loading | Tombol disabled + spinner inline | Sama (audit & monthly) | Sama | ✅ |
| AI fallback offline | Chat, audit, monthly, suggestions — semua punya | Sama | Sama | ✅ |
| Backup error | Snackbar (token, passphrase) | Sama | Sama | ✅ |
| Sync recovery | **Tidak ada feedback** | — | — | ❌ Satu-satunya yang "silent" di mana seharusnya ada toast |

## 5. RESPONSE LATENCY — Ringkasan

| Kategori | Contoh | Feedback |
|---|---|---|
| Instant (<100ms) | Tab, toggle, warna tombol, FAB | Visual state langsung ✅ |
| Medium (1-10s) | Parse pesan AI | AiThinkingBubble ✅ |
| Medium | Laporan AI | Spinner inline + disabled ✅ |
| Long (>10s) | Backup/restore, download update | Progress dialog + tombol Batal ✅ |
| Unknown | AI tanpa key | Fallback offline instan + pesan jelas ✅ |
| Gagal | Semua jalur AI | Fallback offline/error — ✅ (kecuali D4 generik) |

## 6. FORM RESPONSE — Verdict

| Form | Validasi | Inline error | Disabled state | Verdict |
|---|---|---|---|---|
| PIN join | digit-only, min length, rate limit | ✅ | ✅ | ✅ |
| Nama (PIN/profil/member) | non-blank | ✅ | ✅ | ✅ |
| Nominal manual | >0 | ✅ | ✅ | ✅ |
| API key | non-blank | — | ✅ | ✅ (cukup) |
| Passphrase | ≥8 | — | ✅ | ✅ |
| Edit pesan | non-blank | — | ✅ | ✅ |

## 7. SKENARIO FINAL — "Apakah user selalu tahu apa yang terjadi?"

| Skenario | Tahu? | Catatan |
|---|---|---|
| A. Buka app | ✅ | Splash → loading → PIN/main (motion mengalir) |
| B. Buat/kirim pesan | ✅ | Pesan tampil + indikator AI |
| C. AI memproses | ✅ | AiThinkingBubble (beda dengan selesai) |
| D. AI selesai | ✅ | Reply bubble muncul, indikator hilang |
| E. AI gagal | ✅ | Balasan offline jelas ("mode AI sedang offline") / fallback heuristik |
| F. Buka menu/settings | ✅ | Bottom sheet + state jelas |
| G. Ubah setting | ✅ | Toggle langsung berubah |
| H. Aksi destruktif | ✅ | Confirm dialog |
| I. Aksi lama | ✅ | Progress dialog / spinner + disabled |
| J. Kesalahan input | ✅ | Inline error |
| K. Keluar app / di-kick | ✅ | Snackbar penjelasan + kembali ke PIN |
| L. Koneksi pulih | ❌ | **Tidak ada feedback (D1)** — satu-satunya titik buta |

---

## 8. REKOMENDASI PERBAIKAN (belum dieksekusi)

**P1 (harus):**
1. Wire `FirestoreSyncManager.recoveryEvents` → snackbar "Sinkron tersambung kembali." di MainActivity (koleksi dengan `LaunchedEffect` + `collectAsStateWithLifecycle` atau collect di scope). Kecil, aman, memenuhi janji kode yang sudah ditulis.

**P2 (disarankan):**
2. Lampiran chat: tambah feedback saat menyimpan foto/PDF (mis. ikon kecil/teks "Menyimpan..." di area pratinjau, atau disable sementara tombol +). Menghilangkan momen hening.
3. AI report error: ganti pesan generik → tampilkan penyebab + tombol "Coba lagi" di dalam sheet (atau snackbar + otomatis buka ulang). Konsisten dengan prinsip Problem → Cause → Action.
4. (opsional) Snackbar kecil "Kunci disimpan" setelah simpan API key.

**P3 (nice-to-have):**
5. Undo untuk hapus transaksi/pesan (sejajar dengan undo create).
6. Dokumentasi response hierarchy di docs (opsional).

**Tidak akan diubah (sudah benar):**
- Semua fallback offline AI, indikator sync jujur, konfirmasi destruktif, empty states, form validation, undo transaksi.
