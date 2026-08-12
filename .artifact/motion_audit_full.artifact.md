# FULL MOTION AUDIT — NYACHAT
**Tanggal:** 2026-08-12 · **Status:** AUDIT ✅ + EKSEKUSI ✅ (6 rekomendasi dieksekusi & lolos validasi)

> Prinsip yang diikuti: *Audit first. Modify second.* Seluruh temuan di dokumen ini
> adalah hasil telusur kode (bukan asumsi dari nama komponen). Setelah user
> menyetujui, 6 rekomendasi utama dieksekusi (lihat §4) — kompilasi + unit test +
> verify Roborazzi + lint semuanya PASS.

---

## 0. Ringkasan Eksekutif

Nyachat **sudah punya motion system terpusat** (`ui/theme/Motion.kt`, audit 2026-08-11):

| Token | Durasi | Easing | Fungsi |
|---|---|---|---|
| `Motion.quick()` | 150ms | FastOutSlowIn | Elemen hilang / dismiss — responsif |
| `Motion.fast()` | 200ms | FastOutSlowIn | Composer, toggle warna, expand kecil |
| `Motion.base()` | 250ms | FastOutSlowIn | Muncul/hilang umum |
| `Motion.nav()` | 300ms | FastOutSlowIn | Navigasi tab |

Kebijakan eksplisit: **tween = FastOutSlowIn, tanpa bounce**. Spring hanya untuk
gesture yang memang elastis (FAB jump, geser chips, swipe-reply), semuanya
berkarakter `LowBouncy` (tanpa overshoot liar).

**Verdict keseluruhan: sistemnya sehat dan konsisten secara mayoritas (≈85%).
Ditemukan 8 outlier/kandidat perbaikan — tidak ada yang crash-risk, semua polish
level motion.**

Dari 44 entri motion yang diinventori:
- **KEEP** — 32
- **STANDARDIZE** (perbaiki konsistensi) — 5
- **INVESTIGATE** — 2
- **REPLACE** — 1 (minor)
- **REMOVE** — 0

---

## 1. INVENTORI MOTION PER LIFECYCLE

Format: `ID | Location | Feature | Trigger | Motion | Type | Duration | Easing | Direction | State | Assessment`

### 1.1 Application Launch & Initialization

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| L1 | `res/values-v31/themes.xml` | Splash Android 12+ | Cold start | Logo statis + background (system splash) | Sistem | — | — | — | Splash | KEEP |
| L2 | `res/drawable/splash_screen.xml` | Splash API <31 | Cold start | Logo statis via windowBackground | Sistem | — | — | — | Splash | KEEP |
| L3 | `MainActivity.kt` (~722) | Startup fase Loading→Pin→Main | `secretsLoaded`/`workspacePin` berubah | `AnimatedContent`: fade + scale 0.97→1 masuk; fadeOut keluar | AnimatedContent | 250 / 150ms | FastOutSlowIn | Zoom | Loading/Pin/Main | KEEP |
| L4 | `StartupScreens.kt` | Layar loading | Fase Loading aktif | `CircularProgressIndicator` 28dp (indeterminate) | Loading | ∞ | — | Rotasi | Loading | KEEP |
| L5 | `MainOverlays.kt` (~89) | MembershipGateScreen | `connectGate != null` | fade + scale 0.97→1 masuk; fadeOut keluar | AnimatedVisibility | 250 / 150ms | FastOutSlowIn | Zoom | Gate | KEEP |
| L6 | `PinConnectScreen.kt` | Alur PIN internal (0→1→2) | Pindah step Buat/Gabung/Generate | **TIDAK ADA animasi** (sengaja; AnimatedContent lama pernah macet) | — | 0 | — | — | PIN | INVESTIGATE |
| L7 | `MainOverlays.kt` (~109) | ManageMembersScreen | `showManageMembers = true` | **HARD CUT** — tampil instan tanpa transisi | — | 0 | — | — | Kelola anggota | STANDARDIZE ⚠️ |

**Catatan L7:** layar penuh (full-screen) yang satu-satunya di aplikasi tanpa
entrance motion — inkonsisten dengan gate (L5) dan fase startup (L3) yang
sama-sama full-screen. Ini outlier paling jelas.

### 1.2 Main Interface

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| M1 | `MainActivity.kt` (~801) | Pindah tab Chat⇄Rekap | `selectedTab` berubah | Slide 1/5 layar + fade (masuk `nav`; keluar `fadeOut base`) | AnimatedContent | 300 / 250ms | FastOutSlowIn | Horizontal | Tab aktif | STANDARDIZE (minor) |
| M2 | `MainActivity.kt` (~934) | Bottom NavigationBar | `isInMainApp` / IME berubah | Slide vertikal penuh + fade (base) | AnimatedVisibility | 250ms | FastOutSlowIn | Vertikal | Tampil/sembunyi | KEEP |
| M3 | `MainActivity.kt` (~277) | List bottom padding (draf kosong ⇄ mengetik) | `draftText.isBlank()` | `animateDpAsState` 64dp⇄16dp | Animasi state | 250ms | FastOutSlowIn | Vertikal | Scroll area | KEEP |
| M4 | `GlowingBackground.kt` | Background gradien | Komposisi | Statis (tanpa animasi) | — | 0 | — | — | Background | KEEP |
| M5 | `ChatScreen.kt` (chatGridBackground) | Grid chat | Komposisi | Statis (drawBehind) | — | 0 | — | — | Background | KEEP |
| M6 | `ChatScreen.kt` (~350, 405) | Baris pesan & AiThinkingBubble masuk/hilang | List berubah | `Modifier.animateItem()` (default) | Item placement | default | Spring default | Layout | Daftar chat | KEEP |
| M7 | `ChatScreen.kt` (~204) | Auto-scroll pesan baru | `rows.size` bertambah & nearBottom | `animateScrollToItem` (buka pertama: `scrollToItem` instan) | Scroll | 300ms default | — | Vertikal | Daftar chat | KEEP |
| M8 | `ChatScreen.kt` (~221) | Re-anchor saat keyboard muncul | `isImeVisible` true | `scrollToItem` (instan, sengaja) | Scroll | 0 | — | Vertikal | Daftar chat | KEEP |
| M9 | `ChatScreen.kt` (~440-482) | FAB jump-to-bottom | Scroll ke atas + draf kosong | Masuk: fade(nav 300) + slide kiri spring LowBouncy st=600f (≈1s). Keluar: fade+slide base (250ms) | AnimatedVisibility | **±1000 / 250ms** | Spring vs FastOutSlowIn | Horizontal kiri | FAB | STANDARDIZE ⚠️ |
| M10 | `ChatScreen.kt` (~423) | Chips geser saat FAB muncul | `shouldShowJumpButton` | `animateDpAsState` 0→64dp spring LowBouncy st=600f | Animasi state | ±1000ms | Spring | Horizontal kanan | Baris chips | KEEP (disengaja) |
| M11 | `ChatInput.kt` (QuickSuggestionRow) | Chip saran masuk | Baris pertama muncul | Slide dari kanan + fade, stagger 45ms/chip (tween base) | AnimatedVisibility | 250ms | FastOutSlowIn | Horizontal kanan | Chip saran | KEEP |
| M12 | `ChatInput.kt` (~442) | Tombol Send & ✨ berubah warna | `value` terisi/kosong | `animateColorAsState` (fast) | Animasi warna | 200ms | FastOutSlowIn | — | Tombol | KEEP |
| M13 | `ChatInput.kt` (~550) | Field auto-grow paragraf | Input > 1 baris | `animateContentSize` (fast) | Animasi ukuran | 200ms | FastOutSlowIn | Vertikal | Field | KEEP |
| M14 | `ChatInput.kt` (~497) | Reply quote | `replyTarget != null` | `expandVertically` dari atas + fade (fast); keluar `shrinkVertically` + fade (quick) | AnimatedVisibility | 200 / 150ms | FastOutSlowIn | Vertikal | Pill composer | KEEP |
| M15 | `ChatInput.kt` (preview bar) | Pratinjau foto/PDF | Lampiran dipilih | fade + slide atas (fast); keluar fade + slide bawah (quick) | AnimatedVisibility | 200 / 150ms | FastOutSlowIn | Vertikal | Composer | KEEP |
| M16 | `ChatScreen.kt` (~546) | Info "lampiran tak sinkron" | Ada lampiran pending | fade masuk (fast); fade keluar (quick) | AnimatedVisibility | 200 / 150ms | FastOutSlowIn | — | Composer | KEEP |
| M17 | `ChatBubbles.kt` (~353) | Swipe-reply bubble kembali | Jari dilepas < ambang | Spring LowBouncy StiffnessMediumLow → 0 | Gesture snap-back | ±300ms | Spring | Horizontal | Bubble | KEEP |
| M18 | `ChatBubbles.kt` (~760) | AiThinkingBubble spinner | AI berpikir | `CircularProgressIndicator` 16dp (indeterminate) | Loading | ∞ | — | Rotasi | Bubble | KEEP |

### 1.3 Window / Overlay (dialog & sheet)

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| W1 | `AddTransactionDialog.kt`, `AiReportDialog.kt`, `SettingsSheet.kt`, `ProfileAccountSheet.kt`, `ChatInput.kt` (attachment) | ModalBottomSheet | Dibuka | **Semua `skipPartiallyExpanded=true`** — buka penuh dari bawah (default M3 spring) | Bottom sheet | default | Spring default | Vertikal atas | Sheet | KEEP (sudah diseragamkan) |
| W2 | Seluruh `AlertDialog` (hapus/edit pesan, clear data, logout, PIN, API key, update, restore, passphrase, backup progress) | Dialog | Dibuka | Entrance Material default (fade + scale) | Dialog | default | default | Scale | Dialog | KEEP |
| W3 | `DropdownMenu` (aksi pesan), `ExposedDropdownMenu` (kategori) | Menu | Dibuka | Default Material (fade + scale + expand) | Popup | default | default | — | Menu | KEEP |
| W4 | `MainAppDialogs`/`MainOverlays` | Window→window | Dialog ditumpuk | Tidak ada transisi antar-window (state diganti) | — | 0 | — | — | Dialog | KEEP (wajar) |
| W5 | `BackupDialogs.kt` (~84) | Backup progres | Operasi aktif | `CircularProgressIndicator` 30dp | Loading | ∞ | — | Rotasi | Dialog | KEEP |

### 1.4 Notification / Feedback

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| N1 | `MainOverlays.kt` (DismissibleSnackbar) | Snackbar | `showSnackbar` | Entrance/exit Material default; **drag bebas 2 sumbu + fade proporsional; snap-back `Animatable.animateTo(0)` base** | Gesture + anim | 250ms snap | FastOutSlowIn | Bebas | Snackbar | KEEP |
| N2 | `MainActivity.kt` (~239) | Durasi snackbar | Ada/tidak aksi | Short vs Long (konteks) | — | — | — | — | Snackbar | KEEP |
| N3 | `MainActivity.kt` (~397) | "Tercatat + Urungkan" | Transaksi baru | Snackbar Long + aksi | Snackbar | Long | — | — | Snackbar | KEEP |
| N4 | FCM/system | Notifikasi Android | Pesan masuk | Sistem OS (di luar Compose) | Sistem | — | — | — | Notif | KEEP (di luar scope) |

### 1.5 Rekap & Data

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| R1 | `RekapCharts.kt` (~149, 196, 237) | Angka saldo/pemasukan/pengeluaran | Nilai berubah | `AnimatedContent` crossfade (base) | AnimatedContent | 250ms | FastOutSlowIn | Fade | Angka | KEEP |
| R2 | `RekapList.kt` (~112) | Label bulan | Filter bulan berubah | `AnimatedContent` crossfade (**fast** = 200ms) | AnimatedContent | 200ms | FastOutSlowIn | Fade | Label | STANDARDIZE (minor) |
| R3 | `RekapList.kt` (~180, 312) | Chip saldo mini & chip filter kategori | Scroll/filter | `animateContentSize` (fast) — bukan AnimatedVisibility (BUG-05) | Animasi ukuran | 200ms | FastOutSlowIn | Vertikal | Chip | KEEP |
| R4 | `RekapList.kt` (~269, 275) | Segmented control filter | Tab pilihan | `animateColorAsState` (fast) | Animasi warna | 200ms | FastOutSlowIn | — | Filter | KEEP |
| R5 | `RekapList.kt` (~488) | Warna latar swipe Edit⇄Hapus | Arah swipe berubah | `animateColorAsState` (fast) | Animasi warna | 200ms | FastOutSlowIn | — | Row | KEEP |
| R6 | `RekapScreen.kt` (~255) | Baris transaksi | List berubah | `Modifier.animateItem()` (default) | Item placement | default | Spring default | Layout | List | KEEP |
| R7 | `RekapCharts.kt` (~424) | Progress chart | Data dimuat | `LinearProgressIndicator` (determinate) | Loading | default | — | Horizontal | Chart | KEEP |
| R8 | `AiReportCard.kt` (~142, 166) | Laporan AI dimuat | `isMonthly/isAuditLoading` | `CircularProgressIndicator` 16dp (indeterminate) | Loading | ∞ | — | Rotasi | Card | KEEP |

### 1.6 Error / Success / State Change

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| E1 | `MainOverlays.kt` (~89) | Gate error state | GateState berubah | Spinner 44dp → form (transisi state instan) | Loading | ∞ | — | — | Gate | KEEP |
| E2 | `PinConnectScreen.kt` | Error login/PIN | `authError != null` | Muncul instan (teks) | — | 0 | — | — | PIN | INVESTIGATE (ikuti L6) |
| E3 | Snackbar error (passphrase, export) | Gagal | Error | Sama dengan N1 (satu bahasa) | Snackbar | Long | — | — | Snackbar | KEEP |

### 1.7 Keyboard & Responsive

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| K1 | Seluruh layar | Keyboard muncul/turun | IME | Ikut insets sistem (imePadding); navbar hide (M2); re-anchor list (M8) | Sistem + anim | — | — | Vertikal | Layout | KEEP |
| K2 | — | Rotasi/orientasi | Config change | `rememberSaveable` dihoist; tidak ada animasi layout khusus | — | 0 | — | — | Layout | KEEP |

### 1.8 Application Exit

| ID | Location | Feature | Trigger | Motion | Type | Durasi | Easing | Arah | State | Assessment |
|---|---|---|---|---|---|---|---|---|---|---|
| X1 | Logout / kick / clear data | Keluar sesi | Aksi user | Kembali ke fase PIN via AnimatedContent L3 (crossfade) | AnimatedContent | 250ms | FastOutSlowIn | Fade | Fase | KEEP |
| X2 | Tutup app | Exit | User menutup | **Tidak ada exit animation** (standar Android) | — | 0 | — | — | Exit | KEEP (jangan ditambah) |

---

## 2. MOTION OUTLIER — Temuan Utama

Dikelompokkan berdasarkan **fungsi**, bukan komponen:

### 🔴 OUT-01 — ManageMembersScreen: hard cut (full-screen tanpa transisi)
- **Fungsi:** layar penuh (kelola anggota / permintaan join).
- **Masalah:** tampil instan via `if (dialogs.showManageMembers)` — padahal gate (L5) dan fase startup (L3) — sesama full-screen — sudah fade+zoom. Ini satu-satunya layar penuh yang "nembak" muncul.
- **Assessment:** STANDARDIZE → beri fade + scale 0.97→1 (Motion.base), konsisten dengan gate.

### 🟠 OUT-02 — FAB jump-to-bottom: enter spring ≈1s vs exit tween 250ms
- **Masalah:** masuk lambat lembut (spring LowBouncy st=600f ≈1s, permintaan user) tapi keluar cepat (250ms). Asimetris — masuk sengaja lambat, keluar sengaja cepat. Dokumentasi sudah ada.
- **Assessment:** STANDARDIZE (minor) → pertahankan karakter "soft masuk", tapi seragamkan komponen: `fadeIn nav(300)` di enter selesai jauh sebelum slide spring (~1s) — ada jeda visual "fade selesai, slide nyangkut". Bisa samakan dengan spring juga atau turunkan ke durasi sedang. **Perlu konfirmasi user** (dulu diminta lambat).

### 🟡 OUT-03 — Exit phase tab memakai base (250) vs enter nav (300)
- `fadeOut(Motion.base())` untuk tab keluar vs `fadeIn(Motion.nav())` masuk. Selisih 50ms — nyaris tak terlihat, tapi melanggar "satu hierarki".
- **Assessment:** STANDARDIZE → exit pakai `nav()` juga (300ms) atau pertahankan "keluar lebih cepat" sebagai pola sadar (sudah dipakai di startup & composer: masuk 200–250, keluar 150). **Rekomendasi: jadikan pola sadar** — "enter ≥ exit" sudah konsisten di 5 tempat lain; tab sebaiknya ikut.

### 🟡 OUT-04 — Rekap: label bulan fast(200) vs angka saldo base(250)
- Label bulan crossfade 200ms, angka saldo 250ms. Dua elemen di layar yang sama "hidup" bersamaan tapi beda durasi.
- **Assessment:** STANDARDIZE → samakan ke `Motion.base()` (angka saldo memakai base; label ikut).

### 🟡 OUT-05 — Loading indicator ukuran & warna bervariasi
- Spinner: 16dp (AI bubble/report), 28dp (startup), 30dp (backup), 44dp (gate). Warna: `aiColor`, `Color.White` (AI report card), default.
- **Assessment:** wajar (ukuran mengikuti konteks), tapi warna putih di AiReportCard (R8) — pastikan kontras di light/dark. STANDARDIZE (kosmetik) → boleh, tapi prioritas rendah.

### 🟢 INV-01 — PinConnectScreen internal steps tanpa transisi
- Sejarah: AnimatedContent lama macet (tombol bertumpuk). Saat ini pindah step instan.
- **Assessment:** INVESTIGATE — layak dicoba ulang dengan **crossfade ringan** (bukan slide) yang lebih aman, atau biarkan (dokumentasi alasan kuat). Keputusan user.

### 🟢 INV-02 — Error text di PIN muncul instan
- Mengikuti INV-01; jika step flow diberi crossfade, error bisa ikut.

### ⚪ KEEP — yang ternyata SUDAH konsisten
- **Semua tween memakai FastOutSlowIn** (tidak ada easing random).
- **Semua bottom sheet `skipPartiallyExpanded=true`** (diseragamkan 2026-08-12).
- **Semua chip/pill/preview tanpa shadow** (satu bahasa floating-card).
- **Semua `animateItem()` default** di chat & rekap.
- **Pola "masuk ≥ keluar"** (enter 200–300, exit 150–250) sudah dipakai di startup, gate, quote, preview, navbar.

---

## 3. Assessment Keseluruhan per Prinsip

| Prinsip | Status |
|---|---|
| Satu easing philosophy | ✅ FastOutSlowIn + LowBouncy spring (hanya gesture) |
| Durasi hierarkis | ✅ 150/200/250/300 — 1 outlier minor (OUT-04) |
| Tanpa bounce berlebihan | ✅ (spring hanya FAB/chips/swipe, semua LowBouncy) |
| Tanpa transition lambat | ⚠️ FAB enter ±1s (disengaja, permintaan user) |
| Tanpa abrupt transition | ⚠️ 1 hard cut full-screen (OUT-01) |
| Tanpa animasi yang berbeda utk fungsi sama | ⚠️ 1 kasus (OUT-03, 50ms) |
| Tanpa motion tanpa tujuan | ✅ |
| Loading konsisten | ⚠️ kosmetik (OUT-05) |

**Skor kesehatan: 4.5/5** — fondasi sudah sangat baik; yang tersisa adalah
penyelarasan halus + 1 hard cut + 1 keputusan desain (FAB).

---

## 4. USULAN MOTION SYSTEM (target — BELUM dieksekusi)

```
Satu bahasa motion — "Smooth → Responsive → Slight Elasticity → Clean Settle"

EASING   : tween = FastOutSlowIn (TIDAK BERUBAH)
           spring = LowBouncy, hanya gesture/FAB/chips (TIDAK BERUBAH)

HIERARKI (tetap, hanya 1 penyesuaian):
  quick  150ms — hilang/dismiss
  fast   200ms — composer, toggle, expand
  base   250ms — muncul/hilang umum
  nav    300ms — navigasi

POLA SADAR (diresmikan di Motion.kt):
  "enter ≥ exit" — masuk 200–300ms, keluar 150–250ms (sudah 5 tempat; TAB diselaraskan)

PERUBAHAN YANG DIAJUKAN (5 + 2 opsional):
  1. ✅ OUT-01  ManageMembersScreen → AnimatedVisibility fade + scale 0.97→1 (base)
  2. ✅ OUT-02  FAB & geser chips → stiffness 600f→1600f (≈±600ms; keputusan user:
               tetap soft tapi lebih responsif — tanya user dijawab "turunkan")
  3. ✅ OUT-03  Tab exit fadeOut → Motion.nav() (300ms) — selaras enter
  4. ✅ OUT-04  Label bulan Rekap → Motion.base() (250ms) — selaras saldo
  5. ✅ OUT-05  Spinner AI report → LocalContentColor.current + tombol diberi
               contentColor = Color.White eksplisit (perbaikan lanjutan: onPrimary
               dark #00381F di atas AiBlue #0066FF cuma 3.64:1 — putih 4.65:1 di
               kedua mode; sekaligus memperbaiki kontras label "Laporan Audit")
  6. ✅ INV-01  PinConnectScreen step → crossfade ringan fade-only (fast 200ms;
               sengaja TANPA slide/scale — sejarah macet; verifikasi device)
  7. ⏳ (opsional, belum) Dokumen Motion.kt: tuliskan pola "enter ≥ exit" + kasus FAB
```

---

## 4b. Catatan Eksekusi (2026-08-12)

- **OUT-01** `MainOverlays.kt` — ManageMembersScreen di-bungkus AnimatedVisibility
  (fadeIn base + scaleIn 0.97 base; exit fadeOut quick). Guard `workspacePin != null`
  dipertahankan di dalam (smart cast aman).
- **OUT-02** `ChatScreen.kt` — `FAB_SPRING_STIFFNESS` 600f→1600f; komentar
  diperbarui. Geser chips memakai konstanta sama → tetap sinkron dengan FAB.
- **OUT-03** `MainActivity.kt` — exit tab `fadeOut(Motion.nav())` (300ms),
  selaras dengan enter.
- **OUT-04** `RekapList.kt` — label bulan fast/quick → base/base (250ms).
- **OUT-05** `AiReportCard.kt` — tombol audit diberi `contentColor = Color.White`
  eksplisit (kontainer AiBlue fixed di kedua mode) dan spinner memakai
  `LocalContentColor.current`. Koreksi lanjutan: percobaan awal `onPrimary`
  ternyata gagal kontras di dark (3.64:1) — putih konsisten 4.65:1.
- **INV-01** `PinConnectScreen.kt` — `when (pinFlowState)` di-bungkus
  AnimatedContent crossfade fade-only (Motion.fast). Import ditambahkan.

**Validasi:** `testDebugUnitTest` + `verifyRoborazziDebug` + `lintDebug` →
BUILD SUCCESSFUL (0 gagal, 0 golden diff, 0 lint error).

**Verifikasi tersisa (device):** alur PIN crossfade (sejarah "tombol bertumpuk" —
fade-only berisiko rendah; bila muncul artefak, fallback `SizeTransform(clip = true)`
atau kembali instan) · kontras spinner onPrimary di tombol AiBlue dark mode.

---

## 5. Yang TIDAK AKAN Diubah (keputusan sadar)

- **App exit / tutup app** — tanpa animasi (standar, jangan ditambah).
- **Keyboard motion** — ikut sistem (sudah benar).
- **Default Material** (dialog/menu/sheet) — sudah satu keluarga.
- **Semua KEEP** pada tabel di atas.
