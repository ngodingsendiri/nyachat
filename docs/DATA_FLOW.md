# 🔄 Alur Data Offline-First — Nyachat

Diagram alur data **Room → Firestore sync → PendingOp** (arsitektur
offline-first). Untuk struktur proyek lihat [STRUCTURE.md](./STRUCTURE.md);
untuk navigasi layar/sheet/dialog lihat [NAVIGATION.md](./NAVIGATION.md);
untuk panduan developer lihat [DEVELOPER.md](./DEVELOPER.md).

---

## ✍️ Jalur Tulis — "Tulis ke Room DULU, sinkron BELAKANG"

```
  UI (ChatScreen / RekapScreen / AddTransactionDialog)
    │  aksi: kirim pesan · tambah/edit/hapus transaksi · hapus pesan
    ▼
  MainViewModel
    ▼
  FinanceRepository ──(Dispatchers.IO)──────────────────────────────┐
    │                                                               │
    │  1. INSERT ke Room (sumber kebenaran UI)                      │
    │     • ChatMessageDao / TransactionDao                         │
    │     • cloudId = UUID (unik lintas perangkat)                  │
    │     • AI parse (foto nota) → badge finansial di bubble        │
    │  2. FirestoreSyncManager.sync*()  ← queue-aware               │
    │     ├─ canSync()? (familyId aktif + login Google)             │
    │     │    └─ YA → coba push LANGSUNG (syncXxxNow)              │
    │     │         ├─ ✅ sukses → selesai                          │
    │     │         └─ ❌ gagal / PERMISSION_DENIED (di-kick)       │
    │     │              └─ PD → BUANG (tak akan pernah sukses)     │
    │     └─ TIDAK (offline / workspace belum siap)                 │
    │          └─ enqueueOp(OP_*, payload JSON)                     │
    │               ├─ INSERT ke tabel pending_ops (Room)           │
    │               └─ opsSignal.trySend()  ← bangunkan drain      │
    ▼
  ┌─────────────────── ANTRIAN PENDING (pending_ops) ───────────────┐
  │  OP_SYNC_MESSAGE        OP_DELETE_MESSAGE                       │
  │  OP_SYNC_TRANSACTION    OP_DELETE_TRANSACTION                   │
  │  OP_CLEAR_FAMILY                                                │
  │  • Tersimpan di DISK (aman walau app ditutup saat offline)      │
  │  • DIPROSES LAGI saat workspace sama aktif berikutnya           │
  │  • DIHAPUS saat ganti workspace (PIN beda) / logout+hapus data  │
  │    → dilarang replay ke workspace lain                          │
  └─────────────────────────────────────────────────────────────────┘
```

## 🔄 Drain — kuras antrian dengan backoff (`startPendingDrain`)

```
  while familyId aktif:
    ops = pendingDao.getAll()          (urut tertua → terbaru)
    ├─ kosong → tidur di opsSignal     (TANPA polling — hemat baterai)
    │          status = SYNCED (atau OFFLINE bila jaringan jelas mati)
    └─ ada op → status = SYNCING
         untuk tiap op:
           executeOp(op) → syncXxxNow / deleteXxxNow / clearFamilyDataNow
           ├─ ✅ sukses → pendingDao.deleteById(op.id)
           └─ ❌ gagal → stop, delay BACKOFF: 1s → 2s → 4s → … → 32s (cap)
                PERMISSION_DENIED → op DIBUANG (member di-kick)
```

## 📡 Jalur Baca — realtime snapshot listener

```
  Firestore (families/{PIN}/messages & transactions)
    │  addSnapshotListener (messages + transactions, terpisah)
    │  • PERMISSION_DENIED → berhenti (bukan anggota)
    │  • error lain → retry backoff 1s→32s
    ▼
  documentChanges (ADDED / MODIFIED / REMOVED)
    ▼
  upsertMessage / upsertTransaction  →  Room (ChatMessageDao/TransactionDao)
    │  RESOLUSI KONFLIK (last-writer-by-time):
    │  • serverUpdatedAt (waktu SERVER, deterministik) — prioritas #1
    │  • fallback editedAt ?: timestamp (waktu lokal, data lama)
    │  • cloud lebih tua → DITOLAK (edit lokal menang)
    │  • lampiran (foto/PDF) TIDAK dikirim cloud → path lokal dipertahankan
    ▼
  Room Flow (allMessages / allTransactions) → dedupeByCloudId (guard)
    ▼
  MainViewModel → collectAsStateWithLifecycle
    ▼
  UI recompose (bubble chat, badge finansial, Rekap)
```

## 📶 Status Sinkron — indikator jujur (bukan cache palsu)

```
  NetworkMonitor (ConnectivityManager, API 24+)
    └─ setNetworkAvailable(online/offline) → syncStatus StateFlow
         SYNCED  = snapshot sukses + jaringan aktif
         SYNCING = drain aktif / jaringan baru pulih (menunggu konfirmasi)
         OFFLINE = jaringan putus (walau cache masih memenuhi snapshot!)
         ERROR   = kode error nyata (PERMISSION_DENIED, kuota, dll.)
    └─ recoveryEvents → snackbar "Sinkron tersambung kembali" (OFFLINE→SYNCED)
    └─ lastSyncedAt → banner Rekap "Tersinkron · HH:mm"
```

## 🔄 Siklus Hidup (`SyncLifecycleGlue` — ikut lifecycle activity)

```
  start(pin, role, dao…)      → ensureFamilyDoc (owner) + 2 listener + drain
  pauseListeners()            → app background (hemat kuota; cache tetap utuh)
  resumeListeners()           → app foreground (pasang ulang listener)
  stop()                      → logout: batal semua, reset status SYNCED

  Pembersihan antrian (dilarang replay lintas-workspace):
    clearLocalData()  → ganti PIN: Room + pending_ops dihapus (cloud aman)
    clearAllData()    → hapus semua: Room + pending_ops + cloud (WriteBatch)
```

---

## 🧠 Poin Kunci Arsitektur

| # | Prinsip | Implementasi |
|---|---|---|
| 1 | **Room = sumber kebenaran UI** | Semua tulis masuk lokal dulu → UI responsif walau offline |
| 2 | **Antrian pending = jaring pengaman** | Op gagal disimpan sebagai JSON di disk; dikuras dengan backoff eksponensial; dibangunkan via `opsSignal` (Channel), bukan polling |
| 3 | **Resolusi konflik deterministik** | `serverUpdatedAt` (waktu Firestore) menang; tie-break ke `editedAt ?: timestamp` — edit bersamaan di 2 perangkat tidak saling menindas acak |
| 4 | **Indikator jujur** | Snapshot dari cache offline TIDAK dianggap "Tersinkron" — NetworkMonitor memutus (`resolveStatusOnNetworkChange`) |
| 5 | **Isolasi workspace** | PIN beda = data terpisah; pending op lama dibuang agar tidak ter-replay ke workspace baru |
| 6 | **Lampiran lokal-only** | Foto/PDF tidak dikirim cloud; path lokal dipertahankan saat merge snapshot |

## 📁 File Terkait

| File | Peran |
|---|---|
| `data/local/PendingOp.kt` · `PendingOpDao.kt` | Tabel antrian operasi offline (JSON payload) |
| `data/remote/FirestoreSyncManager.kt` | Mesin sync: listener, push, drain, backoff, resolusi konflik, status |
| `data/remote/NetworkMonitor.kt` | Deteksi jaringan → status indikator |
| `data/repository/FinanceRepository.kt` | Orkestrator: Room dulu → sync belakang, konsistensi pesan⇄transaksi |
| `ui/SyncLifecycle.kt` | Pause/resume listener mengikuti lifecycle activity |
