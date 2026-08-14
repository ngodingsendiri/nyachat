# 🧭 Peta Navigasi — Nyachat

Peta navigasi antar layar, sheet, dan dialog di **Nyachat**. Untuk struktur
proyek lihat [STRUCTURE.md](./STRUCTURE.md); untuk alur data offline-first
lihat [DATA_FLOW.md](./DATA_FLOW.md); untuk panduan developer lihat
[DEVELOPER.md](./DEVELOPER.md).

---

## 🌅 Alur Startup (fase layar penuh, AnimatedContent crossfade)

```
Splash (values-v31 / drawable)
  └─▶ StartupLoadingScreen        (Loading — dekripsi secret Keystore async)
        └─▶ PinConnectScreen      (Pin/login — pilih nama & peran)
              ├─ PIN ≠ lama ──▶ PinSwitchDialog ──▶ clearLocalData ──┐
              └─ PIN = lama / baru ─────────────────────────────────┘
                    └─▶ MembershipGateScreen  (gate keanggotaan)
                          ├─ Owner  → siapkan workspace
                          ├─ Member → kirim permintaan → tunggu approve
                          └─ onReady ──▶ applyPinConnect ──▶ 🏠 MAIN
```

## 🏠 Layar Utama (2 tab, AnimatedContent slide + NavigationBar bawah)

```
MainTopBar (avatar ⭘ · ikon ⚙)
  ├─ avatar ──▶ ManageMembersScreen   (sheet kelola anggota)
  └─ ⚙      ──▶ SettingsSheet         (sheet pengaturan)

┌─ TAB 0: ChatScreen ──────────────────────────────────────────────┐
│  + (lampiran) ─▶ ChatAttachmentSheet (sheet)                     │
│     ├─ Kamera ─▶ TakePicture launcher → kirim foto               │
│     ├─ Galeri ─▶ GetContent launcher → kirim foto                │
│     └─ PDF    ─▶ OpenDocument launcher → kirim file              │
│  Bubble teks:                                                     │
│     • tahan lama ─▶ menu: Balas (quote) · Edit ─▶ AlertDialog    │
│                      · Salin · Hapus ─▶ AlertDialog konfirmasi   │
│  Bubble gambar:                                                   │
│     • tap ─▶ ImageViewerDialog (full-screen, pinch-zoom/pan)     │
│     • tahan lama ─▶ menu (sama seperti teks)                     │
│  Badge finansial ─▶ AddTransactionDialog (sheet, edit/view)      │
│  Kirim "ask AI" ─▶ FinanceAiService → balasan bubble AI          │
└───────────────────────────────────────────────────────────────────┘

┌─ TAB 1: RekapScreen ─────────────────────────────────────────────┐
│  Kartu AI (AiReportCard):                                        │
│     • Audit ─▶ AiReportDialog (sheet) [retry jika error]        │
│     • Bulanan ─▶ AiReportDialog (sheet) [retry jika error]      │
│  + Tambah ─▶ AddTransactionDialog (sheet)                        │
│  Edit transaksi ─▶ AddTransactionDialog (sheet, mode edit)       │
│  Hapus transaksi ─▶ AlertDialog konfirmasi                       │
└───────────────────────────────────────────────────────────────────┘
```

## ⚙ Settings Sheet → tujuan tiap baris

> Semua aksi navigasi keluar memakai `dismissThen`: sheet turun beranimasi
> dulu (sheetState.hide()), baru tujuan dibuka. Toggle tetap di tempat.

```
SettingsSheet (ModalBottomSheet)
  ├─ Kartu profil ──▶ ProfileAccountSheet (sheet)
  ├─ Mode Gelap/Terang ──▶ toggle in-place (sheet tetap terbuka)
  ├─ Notifikasi chat ──▶ toggle in-place
  ├─ Enkripsi backup ──▶ toggle in-place
  ├─ PIN Workspace ──▶ PinDisplayDialog (tampil + salin)
  ├─ Kunci Gemini API ──▶ ApiKeyDialog
  ├─ Kunci OpenRouter ──▶ ApiKeyDialog
  ├─ Backup ──▶ DriveBackup: BackupProgressDialog ⇄ PassphraseDialog
  ├─ Restore ──▶ RestorePickerDialog ─▶ RestoreConfirmDialog
  │                ├─ (backup workspace lain) ─▶ CrossFamilyRestoreDialog
  │                └─ (terenkripsi) ─▶ PassphraseDialog
  ├─ Export CSV ──▶ launcher sistem (CreateDocument)
  ├─ Periksa update ──▶ UpdateAvailableDialog / snackbar "tidak ada"
  ├─ Kebijakan privasi ──▶ browser (Intent VIEW)
  ├─ Hapus semua data ──▶ ConfirmClearDataDialog ─▶ clearAllData
  └─ Keluar ──▶ LogoutDialog (Tetap data / Hapus data) ─▶ PIN

ProfileAccountSheet (sheet)
  ├─ Ganti foto profil ─▶ AlertDialog sumber: Google/Galeri/Kamera/Reset
  │     ├─ Galeri ─▶ GetContent launcher
  │     └─ Kamera ─▶ TakePicture launcher (FileProvider)
  └─ Ubah nama ─▶ AlertDialog input nama

ManageMembersScreen (sheet)
  ├─ Hapus/ubah peran member ─▶ AlertDialog konfirmasi
  └─ Edit label member ─▶ LabelEditDialog
```

## 🌐 Overlay Global (MainOverlays — tampil di SEMUA layar/fase)

```
MembershipGateScreen · ManageMembersScreen · PinSwitchDialog
UpdateAvailableDialog · UpdateMessageDialog
BackupProgressDialog · RestorePickerDialog · RestoreConfirmDialog
CrossFamilyRestoreDialog · PassphraseDialog
SnackbarHost (atas, pill, swipe-to-dismiss, tint hijau/merah utk transaksi)
```

## 📋 Dialog Lapisan Konten (MainAppDialogs — hanya di fase Main)

```
AddTransactionDialog · SettingsSheet · ProfileAccountSheet
ApiKeyDialog (Gemini & OpenRouter) · PinDisplayDialog
ConfirmClearDataDialog · LogoutDialog · AiReportDialog (audit & bulanan)
```

---

## 🧩 Pola Navigasi yang Konsisten

| Aspek | Pola |
|---|---|
| State dialog/sheet | Satu sumber: `MainDialogController` (di-remember MainActivity) |
| Sheet | Selalu `ModalBottomSheet` — muncul slide dari bawah, tutup turun ke bawah |
| Keluar dari sheet | Wajib `sheetState.hide()` dulu (`dismiss`/`dismissThen`) — tidak pernah hilang instan |
| Perpindahan tab/fase | `AnimatedContent` + motion language `Motion` (150/200/250/300ms, FastOutSlowIn) |
| Dialog konfirmasi | `AlertDialog` Material 3 (hapus pesan/transaksi/member, logout, clear data) |
| Full-screen | `Dialog` (ImageViewer foto — latar hitam, pinch-zoom/pan) |
| Feedback ringan | Snackbar atas (pill) — hasil export, info backup, "Tercatat + Urungkan" |
| Reduced motion | Semua tween snap ke 0ms saat ANIMATOR_DURATION_SCALE=0 (Motion.reducedMotion) |
