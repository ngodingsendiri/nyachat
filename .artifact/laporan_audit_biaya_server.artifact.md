# Audit Ketahanan Biaya Server (GCP/Firebase) — Nyachat

> Audit: 2026-08-12 · Proyek: `nyachat-in` (billing aktif = **Blaze / pay-as-you-go**)
> Tujuan: memastikan aplikasi **tetap gratis** pada skala keluarga (1–10 pengguna).

## 1. Status Billing

| Item | Status |
|---|---|
| Billing | `billingEnabled: true` → Blaze (pay-as-you-go) |
| Cloud Functions | Wajib Blaze (Spark tidak bisa deploy fungsi) — **normal**, bukan masalah |
| Repo GitHub | `ngodingsendiri/nyachat` = **public** → GitHub Actions gratis tanpa batas |

Blaze itu wajib untuk fungsi relay AI, tapi semua **kuota gratis Blaze tetap berlaku** — selama pemakaian di bawah kuota, tagihan = Rp0.

## 2. Sumber Biaya & Pemakaian Aktual

### Cloud Functions (2 fungsi)
| Fungsi | Region | Timeout | maxInstances | Memori |
|---|---|---|---|---|
| `aiComplete` (relay AI) | us-central1 | 55 s | **3** ✅ | default |
| `notifyChatMessage` (FCM) | asia-southeast2 (Jakarta) | 60 s | **3** ✅ | default |

- Kuota gratis/bulan: **2 juta invocations**, 400K GB-seconds, 200K CPU-seconds, 5 GB outbound.
- **Pemakaian aktual (Cloud Logging, 7 hari): hanya 19 invocations `aiComplete`** → ≈80–100 calls/bulan. Ini **0,005% dari kuota 2 juta**. Sangat jauh di bawah batas. ✅
- `maxInstances: 3` sudah membatasi lonjakan concurrency → mencegah biaya tak terduga saat spike. ✅
- Cloud Functions gen2 berjalan di atas Cloud Run (service `aicomplete` + `notifychatmessage` dibuat otomatis) — **bukan resource terpisah yang menagih ganda**, billing tetap via Functions.

### Firestore (data utama)
- Kuota gratis: **50K reads/hari, 20K writes/hari, 20K deletes/hari, 1 GB storage**.
- Data aktual: 11 family (sebagian besar test kosong), total **113 pesan + 53 transaksi + ~10 member**.
- 4 snapshot listener per perangkat (messages, transactions, members, joinRequests), di-pause saat background. ✅
- Estimasi baca: ~155 dokumen × 2–3 perangkat × beberapa attach/hari ≈ **<2K reads/hari** → 4% kuota. Sangat aman.

### AI Relay (server-owned keys) — **satu-satunya sumber biaya nyata**
Urutan fallback di `aiComplete`:
1. **OpenRouter model `:free`** → **$0** (gratis), tapi ada **rate limit** (umumnya 20–50 req/menit, 50–1.000 req/hari/model).
2. **Gemini dengan key server** → **BERPOTENSI BERBAYAR** tergantung tipe key.

### FCM (notifikasi)
- **Gratis tanpa batas.** ✅

### Cloud Build / Artifact Registry (deploy fungsi)
- Free 120 menit/hari Cloud Build + 500 MB Artifact Registry.
- **Artifact Registry `gcf-artifacts`: 127 MB** (image docker fungsi di us-central1) — masih di bawah 500 MB gratis, tapi akan tumbuh tiap deploy. Aman untuk sekarang; pantau bila sering deploy. ✅

### Storage bucket
- Tidak ada bucket terpakai; gambar chat disimpan lokal; avatar disimpan sebagai Blob di Firestore (saat ini 0 bytes). Storage Firestore jauh di bawah 1 GB. ✅

## 3. Temuan (prioritas)

### 🟢 Sudah bagus (dipertahankan)
1. **Kaskade BYOK dulu, relay terakhir** — user yang mengisi key sendiri TIDAK membebani server. Relay hanya dipanggil saat BYOK tidak ada/gagal. Ini desain hemat terbaik. ✅
2. **Cooldown saran cepat 15 menit** + debounce 3 detik + `isAiAvailable()` skip jalur AI saat tanpa key. ✅
3. **Throttle cek update 1 jam** (GitHub API). ✅
4. **`maxInstances: 3`** di kedua fungsi. ✅
5. **Relay timeout internal 15 s** — kaskade tidak menggantung, langsung jatuh ke heuristik offline. ✅
6. **Model OpenRouter `:free` diutamakan** → biaya AI = $0 selama model gratis tidak kena limit. ✅

### 🟡 Risiko biaya nyata (perlu tindakan)
1. **Key Gemini server bisa berbayar.** Jika `GEMINI_API_KEY` di GitHub secrets adalah key **AI Studio free tier** → gratis (rate limit ~1.000 req/hari). Jika itu key **Vertex AI / paid tier** → setiap fallback Gemini = biaya per token. **Ini penentu terbesar "free terus".**
   - Verifikasi: cek jenis key di console AI Studio vs Google Cloud.
2. **Foto nota (vision) = token besar.** Gambar dikirim base64 (bisa 1.000–2.000+ token) → di Gemini berbayar, tiap foto nota = biaya nyata; di OpenRouter free, foto menghabiskan jatah rate limit lebih cepat.
   - Rekomendasi: pastikan kompresi gambar sudah memadai (periksa `ImageFileUtil`) & pertimbangkan batas ukuran upload.
3. **Tanpa rate limit per-user di server.** Satu akun/gangguan bisa memanggil `aiComplete` ratusan kali (mis. loop/luas) → memboroskan kuota OpenRouter/Gemini server.
   - Rekomendasi: tambah **rate limit per uid** (mis. 30 req/menit) di `aiComplete`.

## 4. Estimasi Biaya (skenario realistis)

| Skenario | Call relay/bulan | Biaya GCP | Biaya AI |
|---|---|---|---|
| 1 keluarga (~2–5 user), tanpa foto | ~3.000 | Rp0 (kuota gratis) | Rp0 (OpenRouter :free) |
| **Kondisi nyata saat ini (7 hari terakhir)** | **~80/bulan** | **Rp0** | **Rp0** |
| 5 keluarga aktif + 100 foto nota | ~15.000 | Rp0 (kuota gratis) | **Rp0** jika Gemini free tier; **berbayar** jika Gemini paid |
| Skala besar (1.000 user) | ~300.000 | Rp0 (masih di bawah 2M) | perlu evaluasi |

**Kesimpulan: pada skala keluarga, semua biaya = Rp0 selama key Gemini server adalah free tier dan foto tidak berlebihan.**

## 5. Rekomendasi Proteksi (disarankan)

1. **Pasang Budget Alert di Google Cloud Billing** — atur budget $1–5/bulan dengan alert di 50% & 90% (email + notifikasi). Gratis, mencegah tagihan tak terduga.
2. **Pastikan `GEMINI_API_KEY` server = key AI Studio (free tier)**, bukan Vertex/paid.
3. **Tambahkan rate limit per-uid di `aiComplete`** (server-side) — perlindungan terbaik dari pemakaian abnormal.
4. Pantau bulanan di Firebase console → **Usage & Billing** (lihat grafik per produk: Functions, Firestore, dll).

## 6. Keputusan yang sudah benar (jangan diubah)
- Wajib Blaze untuk Cloud Functions — jangan turun ke Spark (fungsi akan mati).
- Region Jakarta untuk notifikasi (latensi bagus untuk user Indonesia); region us-central1 untuk relay (tidak masalah biaya).
- Menyimpan gambar lokal + avatar di Firestore Blob kecil — hemat.
