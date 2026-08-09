# 🔐 Enkripsi Backup Nyachat (L9)

Dokumen teknis format & alur enkripsi backup Google Drive Nyachat.

> Berlaku sejak r1.1.0 (FASE 4, M5): **auto-backup harian kini jalan juga saat
> enkripsi aktif** — memakai passphrase otomatis yang disimpan aman di Android
> Keystore, sehingga pengguna tidak perlu prompt manual tengah malam.

---

## 1. Kapan backup dienkripsi

| Skenario | Enkripsi | Passphrase |
|---|---|---|
| Enkripsi **nonaktif** | ❌ Plaintext (JSON Nyachat) | — |
| Enkripsi **aktif**, backup manual | ✅ Amplop AES-256-GCM | Passphrase yang dimasukkan user |
| Enkripsi **aktif**, auto-backup 24 jam | ✅ Amplop AES-256-GCM | `BACKUP_AUTO_PASSPHRASE` (otomatis, dari Android Keystore) |

> Sebelum M5, auto-backup **dilewati** saat enkripsi aktif (`silentBackup` return
> false) — pengguna ber-enkripsi tidak pernah mendapat backup otomatis. Sekarang
> silent backup selalu berjalan; bila enkripsi aktif, dipakai auto-passphrase
> Keystore (`DriveBackupController.getAutoPassphrase`).

---

## 2. Format amplop (envelope)

Backup terenkripsi adalah satu objek JSON (`BackupCrypto.encryptToEnvelope`):

```json
{
  "app": "Nyachat",
  "encrypted": true,
  "envelope": 1,
  "kdf": "PBKDF2WithHmacSHA256",
  "iterations": 600000,
  "salt": "<base64 16 byte acak>",
  "cipher": "AES/GCM/NoPadding",
  "iv": "<base64 12 byte acak>",
  "data": "<base64 ciphertext + GCM tag 128 bit>"
}
```

### Kriptografi

- **Cipher**: AES-256-GCM (kerahasiaan + integritas otentikasi). Passphrase
  salah → `AEADBadTagException` → restore ditolak (bukan data rusak diam-diam).
- **KDF**: PBKDF2-HMAC-SHA256, **600.000 iterasi** (minimum OWASP 2023 untuk
  SHA-256). Nilai iterasi disimpan per-amplop.
- **Salt & IV**: acak per backup (`SecureRandom`) — backup yang sama dua kali
  menghasilkan amplop berbeda.
- **AAD** (`Nyachat-backup-envelope:1`): mengikat identitas amplop — header
  yang dipindah/ditukar antar file akan membuat dekripsi gagal.
- **Hardening restore**: amplifier memeriksa `kdf` & `cipher` (menolak amplop
  yang menurunkan ke algoritma lemah) dan membatasi iterasi ≤ 10.000.000
  (anti-DoS amplop jahat).
- **Tanpa key disimpan**: passphrase tidak pernah ditulis ke disk/Drive. Backup
  tanpa passphrase yang benar tidak bisa dibuka (bahkan oleh pengembang).

### Keterbatasan yang disengaja

- Restore hanya menerima `envelope <= 1` — amplop versi lebih baru ditolak
  (konsisten dengan kebijakan format backup JSON).

---

## 3. Alur restore (passphrase)

1. Pengguna memilih file backup Drive → app mendeteksi amplop via
   `isEncryptedEnvelope` (`app == "Nyachat" && encrypted == true`).
2. Jika auto-passphrase Keystore tersedia, dicoba **dulu** (restore otomatis
   tanpa prompt — M5).
3. Gagal → prompt manual passphrase.
4. `decryptEnvelope` → plaintext JSON → `parseBackupJson` → restore data lokal
   + sinkronisasi ke cloud.

> Backups lama plaintext (tanpa amplop) tetap bisa di-restore — dideteksi via
> `isEncryptedEnvelope == false`.

---

## 4. Restore lintas workspace

JSON backup menyimpan `familyId` (PIN workspace asal). Saat restore:

- Backups dari workspace **yang sama** → diterapkan langsung.
- Backups dari workspace **lain** → dikonfirmasi ke pengguna terlebih dahulu
  (data lintas-workspace tidak dicampur diam-diam), lalu di-merge ke data lokal
  + cloud; `deleteAbsentFromBackup` membersihkan data workspace lama yang tidak
  ada di backup.

---

## 5. Implementasi

| File | Peran |
|---|---|
| `data/backup/BackupCrypto.kt` | Enkripsi/dekripsi amplop (murni JVM, teruji unit) |
| `data/backup/DataExporter.kt` | Build/parse JSON backup + enkripsi opsional |
| `data/backup/DriveBackupController.kt` | Wiring backup/restore, `getAutoPassphrase`, `silentBackup` |
| `data/backup/DriveBackupManager.kt` | Upload/download Drive, `pruneOldBackups`, metadata `appProperties.encrypted` |
| `SecureStorage.kt` | Menyimpan `BACKUP_AUTO_PASSPHRASE` di Android Keystore |

### Test terkait

- `BackupCryptoTest` — round-trip enkripsi, passphrase salah, versi amplop,
  iterasi ekstrem, tamper header.
- `DriveBackupControllerTest` — `silentBackupTerenkripsiDipakaiAutoPassphrase`,
  `silentBackupTanpaAutoPassphraseDilewati`, probe badge 🔒 picker restore.
- `DataExporterTest` — round-trip JSON, format lama, enkripsi opsional.

---

## 6. Badge 🔒 di picker restore (temuan #4)

Picker restore menampilkan badge `🔒 Terenkripsi` pada file backup yang
terenkripsi. Status enkripsi **per file** diketahui dari 3 sumber (tanpa
mengunduh isi):

1. **Penanda nama** `.enc.json` (mis. `Nyachat-backup-20260809-184131.enc.json`)
   → selalu `true`. Nama ini dipakai controller untuk semua backup terenkripsi
   (manual & auto).
2. **`appProperties.encrypted`** Drive — ditulis saat upload untuk SEMUA backup
   baru (`true`/`false`), termasuk yang plain → file baru tidak pernah
   di-probe.
3. **Probe isi amplop** — backup LAMA (sebelum r1.1.3, tanpa metadata) ber-
   status `null`; `DriveBackupController.resolveEncryptionFlags` mengunduh isi
   (paralel, ≤5 file) & memeriksa `isEncryptedEnvelope` sebelum picker tampil.
   Restore tetap mendeteksi enkripsi dari isi saat unduh (`handleDownloadedBackup`).
