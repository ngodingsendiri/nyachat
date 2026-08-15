# Spec: Notifikasi Keanggotaan · Nama Workspace Custom · Cap Anggota Bertier

Tanggal: 2026-08-15
Status: Menunggu review user
Target rilis: r1.6.0

## Latar belakang

Hasil testimoni beta (fase sebelum r.2.0.0):

1. Pemilik tidak mendapat notifikasi saat ada permintaan bergabung.
2. Judul workspace masih statis ("Keuangan Bersama") — pengguna ingin nama
   custom (mis. "Keuangan Sekolah", "Kas Kelas").
3. UI Kelola Anggota: tombol "Jadikan Pemilik" berantakan; section permintaan
   bergabung selalu tampil (bahkan kosong) padahal sebaiknya hanya muncul saat
   ada permintaan.
4. Pertanyaan batas anggota per workspace.

### Hasil riset batas anggota

- Firestore tidak punya batas praktis jumlah listener/member per dokumen
  (auto-scale, Google: "scales to millions of concurrent users").
- FCM multicast: maks **500 token per panggilan**; Nyachat mengirim 1 multicast
  ke semua anggota per pesan chat → bahkan 500 anggota muat 1 panggilan. FCM gratis.
- Biaya Firestore naik **linear** dengan jumlah anggota (setiap pesan dibaca
  semua anggota yang online) → itu batas realistis utama.
- Produk ini = chat uang keluarga/kelas; data finansial sensitif, notifikasi ke
  semua anggota. **Keputusan produk**: free = 2 anggota, berlangganan (pro) = 6.

### Keputusan yang sudah disepakati user

- Notifikasi keanggotaan dikirim ke **owner (request masuk)** dan **pemohon
  (disetujui/ditolak)**, di-gate **toggle notifikasi chat yang ada**.
- Rename nama workspace di **sheet Kelola Anggota** (owner).
- Cap: **free = 2, pro = 6**.
- **Tier logic dulu, billing belakangan**: tombol Upgrade bersifat placeholder
  (langsung set plan=pro), siap disambungkan Play Billing saat produksi.

---

## 1. Notifikasi permintaan bergabung (owner + pemohon)

### 1.1 Backend — cloud function baru

File: `functions/index.js`, tambah `exports.handleJoinRequest` dengan trigger:

```
onDocumentWritten('families/{familyId}/joinRequests/{uid}')
```

**Event CREATE (permintaan masuk):**
1. Baca data request: `name`, `email`.
2. Baca doc keluarga → `plan` (untuk cek kapasitas) dan `name` (untuk body notif).
3. Hitung jumlah anggota (`families/{familyId}/members`).
4. Jika `jumlah >= limit(plan)` → **jangan notifikasi owner** (request tak bisa
   disetujui; owner hanya akan spam). Log saja.
5. Jika muat → cari member ber-role `owner`, ambil `fcmToken`, kirim data message:

   ```
   { type: 'join_request', requesterName, requesterEmail, familyName, familyId, requesterUid }
   ```

**Event DELETE (keputusan / dicabut):**
1. Baca `before.data().status`:
   - `'approved'` → kirim ke pemohon (token dari `before.data().fcmToken`):
     `{ type: 'join_decision', approved: '1', familyName }`
   - `'rejected'` → kirim: `{ type: 'join_decision', approved: '0', familyName }`
   - selain itu (dicabut sendiri) → tanpa notifikasi.
2. Tokens invalid dibersihkan seperti `notifyChatMessage` (pola yang sudah ada).

`sendToFcmToken` dan `cleanupInvalidTokens` diekstrak sebagai helper agar
`notifyChatMessage` & `handleJoinRequest` berbagi logika (DRY).

### 1.2 App — client

- `requestJoin` (`MembershipManager.kt`): tambah field `fcmToken` ke doc request
  (token dari `FirebaseMessaging.getInstance().token`).
- `approveJoin`: dalam transaksi yang sama dengan `set(member)` + `delete(request)`,
  tambahkan update request `{ status: 'approved' }` sebelum delete.
- `rejectJoin`: tulis `{ status: 'rejected' }` lalu delete.
- `ChatMessageFirebaseService.onMessageReceived`:
  - `data["type"] == "join_request"` → notifikasi channel `workspace_activity`
    ("Permintaan Bergabung" / "{name} ingin bergabung ke {familyName}").
  - `data["type"] == "join_decision"` → ("Keputusan Bergabung" /
    "Permintaan Anda disetujui/ditolak").
  - Keduanya **di-gate pref `CHAT_NOTIFICATIONS_ENABLED` yang sama** dengan chat
    (sesuai keputusan user); skip `sender == user` tidak berlaku di sini (bukan
    pesan diri).

### 1.3 Aturan Firestore

Tidak ada perubahan rules untuk joinRequests: create sudah menerima field
tambahan (`fcmToken`), owner sudah boleh update (`status`) & delete request.

---

## 2. Nama workspace custom

### 2.1 Data

- Doc keluarga (`families/{pin}`) mendapat field `name` (default "Keuangan
  Bersama") dan `plan` (default `'free'`).
- Ditulis saat pembuatan workspace:
  - `ensureOwnerWorkspace` (`MembershipManager.kt:666-696`)
  - `ensureFamilyDoc` (`FirestoreSyncManager.kt:242-260`)
- Workspace lama tanpa field → fallback default saat dibaca.

### 2.2 Constants

- Reuse `Fields.NAME`. Tambah `Fields.PLAN = "plan"`.
- `object Plans { const val FREE = "free"; const val PRO = "pro" }`
- `object Defaults { const val FAMILY_NAME = "Keuangan Bersama" }` (cek apakah
  sudah ada `Defaults.LABEL`).
- `object Limits { const val FREE_MAX_MEMBERS = 2; const val PRO_MAX_MEMBERS = 6 }`

### 2.3 MembershipManager

- `familyName: StateFlow<String>` (default `Defaults.FAMILY_NAME`).
- `familyPlan: StateFlow<String>` (default `Plans.FREE`).
- Listener doc keluarga dipasang di `attachListeners` (semua peran boleh baca
  doc keluarga sesuai rules) → update kedua StateFlow; bila doc tak ada → default.
- `setFamilyName(pin, name)` → update doc keluarga `{ name }`.
- `setPlan(pin, plan)` → update doc keluarga `{ plan }` (dipakai placeholder upgrade).
- `memberLimit()`: `2` bila plan free, `6` bila pro.

### 2.4 Rules (firestore.rules)

Batasi update doc keluarga ke field yang sah (mengikuti pola audit repo):

```
allow update: if signedInGoogle() && isOwner(familyId)
  && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['name', 'plan']);
```

### 2.5 UI

- **Top bar** (`MainTopBar.kt`): ganti `stringResource(R.string.topbar_title)`
  dengan param baru `familyName: String` (diisi dari `MembershipManager.familyName`
  di `MainActivity.kt:1007`).
- **Header sheet Kelola Anggota** (`ManageMembersScreen.kt:124-143`): tampilkan
  nama workspace; owner mendapat ikon edit (pencil) → `WorkspaceNameDialog`
  (pola `LabelEditDialog`) → `setFamilyName`.

---

## 3. Kelola anggota: rapi + section request dinamis

### 3.1 MemberCard (`ManageMembersScreen.kt:381-527`)

Buang dua kontrol yang berantakan:
- `IconButton` edit label (baris 464-471)
- tombol teks di dalam `IconButton` yang membuka dropdown (baris 473-523)

Ganti dengan **satu `IconButton` overflow `⋮` (Icons.Rounded.MoreVert)** yang
membuka `DropdownMenu` berisi (owner & bukan diri sendiri):
1. **Ubah Label** → `onEditLabel` (dialog yang sama).
2. **Jadikan Pemilik / Jadikan Anggota** → `onToggleRole` (konfirmasi tetap).
3. **Hapus** (hanya bila member bukan owner) → `onRemove` (konfirmasi tetap).

### 3.2 Section Permintaan Bergabung

Hapus empty-state (`manage_members_no_requests`) — saat `joinRequests.isEmpty()`
section (title + konten) **tidak dirender sama sekali** (baris 154-185). Muncul
hanya saat ada request.

---

## 4. Batas anggota bertier (free 2 / pro 6)

### 4.1 Enforce (app-level)

- **`approveJoin`**: sebelum transaksi, hitung jumlah member
  (`famRef.collection(MEMBERS).get()`). Jika `jumlah >= memberLimit()` →
  jangan approve, kembalikan hasil baru `ApproveResult.WORKSPACE_FULL`.
  `approveJoin` diubah agar mengembalikan hasil (saat ini `Unit`).
- **`requestJoin`**: TIDAK melakukan pre-check (pemohon bukan member → rules
  menolak baca koleksi members). Kapasitas dicek saat approve. Cloud function
  yang tidak menotifikasi owner saat penuh mencegah spam ke owner.
- Workspace lama yang sudah penuh/di atas cap → **grandfathered**, tidak
  dikeluarkan; cap hanya memblokir persetujuan baru.

### 4.2 UI

- **`ManageMembersScreen`** (owner saja), tambah row/baris "langganan" di atas
  daftar:
  - Plan free: "Free — 2 anggota" + tombol **"Upgrade ke Pro (6 anggota)"**.
  - Plan pro: "Pro — 6 anggota" (label saja).
- Upgrade = dialog konfirmasi → `setPlan(pin, 'pro')` (placeholder; komentar di
  kode & spec menyatakan ini akan diganti alur Play Billing saat produksi).
- Saat approve diblokir karena penuh → snackbar: "Workspace penuh (2/2).
  Upgrade ke Pro untuk menampung hingga 6 anggota."

### 4.3 Catatan keamanan (dokumentasikan di kode)

Enforce kapasitas di sisi **app** saja (Firestore rules tidak bisa COUNT koleksi).
Ini konsisten dengan pola repo yang sudah ada (mis. "workspace tidak boleh
yatim" di `leaveWorkspace`). Untuk produksi + billing, tambahkan enforce
server-side (cloud function) sebagai lapisan otoritatif.

---

## File yang disentuh

| Area | File |
|---|---|
| Backend notif | `functions/index.js` |
| Constants | `Constants.kt` |
| Membership | `MembershipManager.kt` |
| FCM service | `ChatMessageFirebaseService.kt` |
| UI | `ManageMembersScreen.kt`, `MainTopBar.kt`, `MainActivity.kt` |
| Sync (default nama) | `FirestoreSyncManager.kt` |
| Rules | `firestore.rules` |
| Strings | `res/values/strings.xml` |
| Tests | unit test logika cap/plan + ConstantsTest sync |

## Testing

- Unit test murni (pola `canLeaveWorkspace`/`ownsWorkspaceElsewhere`):
  `memberLimit(plan)`, `canApproveMember(count, plan)`.
- Test `approveJoin` cap di level repository (fake DAO / Robolectric).
- `firestore.rules` lint (`npm run lint:rules`) + deploy function ke emulator.
- Snapshot Roborazzi untuk MemberCard baru & section request dinamis.
- Manual E2E di emulator: request → owner dapat notif; approve → pemohon dapat
  notif; free penuh → approve diblokir; upgrade → 6 anggota.
