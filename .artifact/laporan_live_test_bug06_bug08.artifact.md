# Laporan Live Test — BUG-06 (label sync) & BUG-08 (reset field chat)

> Live test di emulator (AVD Android, 1080×2400) via ADB + uiautomator dump.
> Tanggal: 2026-08-09 · APK: `r1.1.3` (versionCode 26) debug — sama dengan sumber (FASE 3 tidak bump versi).
> Konteks: verifikasi fix FASE 3 commit `f11504f` (BUG-06 label netral + BUG-08 reset field chat).

---

## 1. BUG-06 — Indikator sync netral saat offline

### Hasil: ⚠️ SEBAGIAN — skenario utama (offline murni) GAGAL

**Langkah test (semua jaringan emulator benar-benar mati):**
1. `cmd connectivity airplane-mode enable` + `svc wifi disable` + `svc data disable`
2. Verifikasi network mati dari dalam emulator:
   - `ping 8.8.8.8` → **Network is unreachable**
   - `ping google.com` → unknown host
   - `settings get global airplane_mode_on` → `1`, `Wi-Fi is disabled`
3. Indikator di tab Rekap tetap **"Tersinkron"** — selama 60+ detik, bahkan setelah **force-stop + relaunch saat offline**.

**Akar masalah (bukti kode):**
- App **TIDAK memiliki deteksi jaringan sama sekali**: 0 hasil pencarian `ConnectivityManager|NetworkCallback|getActiveNetwork` di seluruh `src/main`.
- `markSynced()` (FirestoreSyncManager baris 290/336/817) dipanggil dari snapshot listener yang **berhasil dipenuhi Firestore offline cache** → status selalu `SYNCED`.
- `classifySyncFailure()` (fix BUG-06) hanya berjalan saat listener **ERROR** — yang TIDAK terjadi saat offline + cache tersedia.

**Kesimpulan:** Fix BUG-06 menangani klasifikasi *kegagalan* (label jadi "Mode offline"/"Belum sinkron") tetapi **tidak menangani offline-dengan-cache** — indikator tetap menyesatkan ("Tersinkron") saat user offline penuh.

**Saran perbaikan:**
1. Pasang `ConnectivityManager.registerDefaultNetworkCallback` → status `OFFLINE` saat jaringan putus, `SYNCING` saat pulih (dan `SYNCED` hanya setelah `markSynced()` dengan jaringan aktif).
2. Atau: jangan `markSynced()` bila snapshot hanya berasal dari cache lokal (deteksi via `SnapshotMetadata.isFromCache` — perlu dicek kelayakan dengan listener saat ini).
3. Alternatif minimal: tunjukkan label netral "Mode offline" berdasarkan state jaringan eksplisit, terlepas dari `SyncStatus` internal.

---

## 2. BUG-08 — Field chat bersih setelah dialog transaksi ditutup

### Hasil: 🟡 Mekanisme verified — test end-to-end TERBLOKIR oleh 2 bug baru

**Verifikasi kode (fix sudah benar):**
- `MainActivity` baris 562–632: dialog dari badge chat (`onOpenTransaction`) set `resetChatOnDialogClose = true` → saat tutup `chatResetTrigger++` → `ChatScreen` `LaunchedEffect` me-reset `inputText`. ✅
- Gate membedakan dialog dari Rekap (`false`, baris 594/602) — draf chat tidak di-reset saat dialog Rekap. ✅ (komentar desain: "draf yang diketik sebelum pindah tab")
- Dialog "Edit Transaksi" berhasil dibuka & ditutup (via Rekap → menu ⋯ → Edit → Batal). ✅

**Test end-to-end (ketik draf → buka dialog dari badge chat → tutup → cek field) TIDAK bisa dilakukan karena:**
- **BUG-1 (baru)**: badge finansial hilang dari bubble setelah ~5–10 detik → tidak ada badge untuk di-tap.
- **BUG-2 (baru)**: draf chat hilang saat pindah tab Chat ⇄ Rekap → asumsi gate ("draf bertahan saat pindah tab") tidak terpenuhi.

---

## 3. BUG-1 (BARU, SERIUS) — Badge finansial hilang dari bubble chat

**Gejala (live):**
- Setelah kirim pesan finansial: badge "Tercatat: -Rp8.000 (Lain-lain)" tampil di detik ke-2, **hilang di detik ke-7** (dibuktikan via 2 dump berurutan), permanen setelah restart app.
- Transaksi tetap tersimpan di Rekap (Rp25.000, Rp8.000, dst tercatat & "Tersinkron").
- Menu long-press bubble kehilangan opsi "Edit Transaksi" (badge hilang → tidak dianggap transaksi).

**Forensik DB (bukti kuat):**
- Room lokal (`keuangan_pasutri_db` + WAL, dibaca via Python/sqlite3):
  - `SELECT COUNT(*) WHERE isFinancial=1` → **0**
  - `beli kopi 25000` → `isFinancial=0, detectedAmount=25000.0`
  - `beli gorengan 8000` → `isFinancial=0, detectedAmount=8000.0` — **semua** pesan finansial jadi `isFinancial=0` padahal `detectedAmount` tersimpan.
- Cloud (Firestore SDK cache, parse protobuf `remote_documents`):
  - `beli kopi 25000` → **`isFinancial=1`**, amount 25000
  - `beli gorengan 8000` → **`isFinancial=1`**, amount 8000
- `pending_ops` Room → 0 baris (bukan replay antrian).

**Analisis awal:**
- Upload `syncMessageNow` mengirim `isFinancial` (baris 555–556) ✅; download `upsertMessage` mempertahankan `c.isFinancial` (446–470) ✅; `toObject(CloudMessage)` seharusnya memetakan benar.
- Kontradiksi: cloud `true`, lokal `false` → ada jalur penulis `isFinancial=false` yang belum teridentifikasi (dugaan: mapping/parse CloudMessage atau overwrite tak terduga di jalur snapshot/pending op lama).
- **Investigasi lanjut wajib** sebelum fitur badge dianggap sehat. Dampak: fitur inti transparansi transaksi rusak + opsi "Edit Transaksi" dari chat hilang + BUG-08 tidak bisa diuji E2E.

---

## 4. BUG-2 (BARU, UX) — Draf chat hilang saat pindah tab

**Gejala (live, test terkontrol):**
1. Ketik "xyz" di field chat → verified ada.
2. Pindah tab ke Rekap → kembali ke tab Chat.
3. Field → **kosong** (`text=""`).

**Dampak:** user kehilangan draf pesan setiap pindah tab; bertentangan dengan asumsi gate BUG-08. `ChatScreen` di-destroy oleh `AnimatedContent` saat tab berganti — state input tidak dipertahankan.

**Saran:** hoist draf ke `MainActivity` (`rememberSaveable`) atau pertahankan state `ChatScreen` (mis. `movableContentOf`/simpan per tab).

---

## 5. Hal lain yang terverifikasi normal

| Item | Hasil |
|---|---|
| Login & workspace "Keuangan Bersama" | ✅ stabil sepanjang sesi |
| Kirim pesan → parse AI/heuristik → transaksi | ✅ (termasuk fallback heuristik offline) |
| Rekap: saldo, kategori, riwayat, filter | ✅ sinkron dengan transaksi |
| Indikator "Tersinkron" saat online | ✅ |
| Dialog Edit Transaksi (buka/tutup, Batal) | ✅ |
| Menu per-item Rekap (⋯ → Edit/Hapus) | ✅ |
| Snackbar "Urungkan" setelah transaksi baru | ✅ (muncul, hilang ~5 detik) |

**Catatan:** beberapa pesan uji mangle oleh IME emulator ("tes draf 9beli tempe 600099" → transaksi Rp600.099) — artefak tooling, bukan bug aplikasi, namun menunjukkan parser sensitif terhadap teks kotor.

---

## Ringkasan prioritas tindak lanjut

1. **P0 — BUG-1**: badge finansial hilang dari bubble (`isFinancial` lokal jadi false walau cloud true). Investigasi & fix jalur penulis.
2. **P0 — BUG-06 lanjutan**: indikator tidak pernah "Mode offline" saat offline murni (tidak ada deteksi jaringan; offline cache → `markSynced`). Tambah `ConnectivityManager.NetworkCallback`.
3. **P1 — BUG-2**: draf chat hilang saat pindah tab → hoist draf.
4. **P1 — BUG-08 E2E**: ulangi test penuh setelah BUG-1 & BUG-2 beres.

Bukti screenshot: `.artifact/live_shots/chat_badge_hilang.png` (bubble tanpa badge pasca-hilang).
