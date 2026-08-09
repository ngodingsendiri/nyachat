# Nyachat r1.2.0 — Implementation Plan (Detailed & Disempurnakan)

> Rencana rilis berikutnya setelah **r1.1.3 (build 26)**.
> Disusun: 2026-08-09 · Berbasis: sisa FASE 5 (T3, M1) + temuan audit live r1.1.3
> + rekomendasi prioritas dari `laporan_pengujian_live_emulator.artifact.md`.

---

## Ringkasan Rencana

| Fase | Isi | Risiko | Estimasi |
|------|-----|--------|----------|
| FASE 0 — Persiapan & baseline | Commit r1.1.3, re-record golden Roborazzi, baseline test | Rendah | 0.5 hari |
| FASE 1 — T3: Refactor file raksasa | `MainActivity`/`ChatScreen`/`RekapScreen` → dekomposisi | **Tinggi** | 2-3 hari |
| FASE 2 — M1: Upgrade dependensi | compose-bom, lifecycle, activity, room, okhttp, firebase-bom | Sedang-tinggi | 1-2 hari |
| FASE 3 — Perbaikan UX & verifikasi lanjutan | quick suggestion, label sync, field chat, edit pesan, backup Drive, model AI | Rendah-sedang | 1-2 hari |
| FASE 4 — Rilis r1.2.0 | CHANGELOG, docs, bump versi, CI, tag, Play Store | Rendah | 0.5-1 hari |

**Total estimasi:** ~6-9 hari kerja penuh. **FASE 0-3 bisa dirilis terpisah** (r1.2.0 = FASE 0-2 + FASE 4; FASE 3 optional ikut r1.2.1).

---

## FASE 0 — Persiapan & Baseline r1.1.3

**Tujuan:** mengunci kondisi r1.1.3 yang sudah lulus live test, sebelum mulai pekerjaan berisiko.

| # | Sub-Task | Detail | Status |
|---|----------|--------|--------|
| 0.1 | Commit pekerjaan r1.1.3 | 6 file belum di-commit: `FirestoreSyncManager.kt`, `FirestoreSyncManagerConflictTest.kt`, `strings.xml`, `gradle.properties`, `build.gradle.kts`, `CHANGELOG.md` | ⏳ TODO |
| 0.2 | **Re-record golden Roborazzi** | Tagline login berubah di r1.1.3 → `./gradlew :app:recordRoborazziDebug`, commit PNG baru. **WAJIB** agar CI `verifyRoborazziDebug` tidak gagal | ⏳ TODO |
| 0.3 | Baseline test | `testDebugUnitTest` + `lintDebug` + `verifyRoborazziDebug` PASS di lokal | ⏳ TODO |
| 0.4 | Buat branch `fix/r1.2.0` | Dari `main` (atau `fix/v1.1.0` yang sudah di-merge) | ⏳ TODO |
| 0.5 | Tag & release r1.1.3 | Push tag `r1.1.3` → GitHub Release berisi APK r1.1.3/26 yang benar (mengoreksi tag lama yang berisi APK r1.1.2) | ⏳ TODO |

**Pintu keluar (exit criteria):** unit test + lint + Roborazzi PASS; r1.1.3 ter-release benar.

---

## FASE 1 — T3: Refactor File Raksasa 🧱

**Tujuan:** memecah 3 file monolitik agar mudah dirawat, diuji, dan dikembangkan. **Paling berisiko** — dikerjakan bertahap dengan verifikasi di tiap langkah (tidak ada refactor murni besar-besaran sekali jalan).

### Prinsip
- **No behavior change**: refactor murni struktural; UI tampak identik.
- **Verifikasi tiap langkah**: build + `AppSnapshotTest` (Roborazzi) di tiap milestone.
- **Incremental**: pisahkan per layar/dialog, bukan tulis ulang sekaligus.

### TASK-1.1 — Dekomposisi `ChatScreen.kt` (~1400 baris)
| # | Sub-Task | Detail |
|---|----------|--------|
| 1.1.1 | Ekstrak composable bubble | `UserBubble`, `AiBubble`, `FinancialBadge`, `AttachmentBubble`, `ReplyPreview` ke file `ChatBubbles.kt` |
| 1.1.2 | Ekstrak input bar | `ChatInputBar` (field, tombol kirim, Tanya AI, lampiran) + quick suggestions ke `ChatInput.kt` |
| 1.1.3 | Ekstrak logika murni | `buildChatRows` (grouping per hari) → util `ChatRowBuilder.kt` + unit test |
| 1.1.4 | State holder | Kelola state input/fokus/reply di `rememberChatScreenState()` atau `ChatUiState` (jika layak) |
| 1.1.5 | Verifikasi | Build + unit test + Roborazzi |

### TASK-1.2 — Dekomposisi `RekapScreen.kt` (~1441 baris)
| # | Sub-Task | Detail |
|---|----------|--------|
| 1.2.1 | Ekstrak chart composable | `BalanceBanner`, `DonutChart`, `CategoryProgressBar`, `MonthlyNav` → `RekapCharts.kt` |
| 1.2.2 | Ekstrak daftar & filter | `TransactionList` + filter chips → `RekapList.kt` |
| 1.2.3 | Ekstrak AI insight card | `AiInsightCard` + dialog laporan → `AiReportCard.kt` |
| 1.2.4 | State holder | Pisahkan state bulan terpilih/filter dari komposisi |
| 1.2.5 | Verifikasi | Build + unit test + Roborazzi |

### TASK-1.3 — Dekomposisi `MainActivity.kt` (~1474 baris)
| # | Sub-Task | Detail |
|---|----------|--------|
| 1.3.1 | Ekstrak wiring dialog | Snackbar host, dialog PIN/API key/update/export → `MainDialogs.kt` (mengikuti pola `SettingsSheet`/`AddTransactionDialog`) |
| 1.3.2 | Ekstrak callbacks | Kelompok callback `onSendMessage/onEdit/onOpenTransaction/onManageMembers...` → interface `ChatCallbacks` / `RekapCallbacks` |
| 1.3.3 | Ekstrak lifecycle glue | Listener attach/pause/resume sync & membership → `SyncLifecycle.kt` |
| 1.3.4 | (Opsional) Navigation Compose | Evaluasi: apakah migrasi layar ke NavController memberi nilai lebih besar dari risiko. **Jika ragu, lewati** — simpan untuk r1.3.0 |
| 1.3.5 | Verifikasi | Build + unit test + Roborazzi + smoke test live |

**Exit criteria:** 3 file di bawah ~600 baris masing-masing; seluruh test hijau; smoke test live (kirim pesan, rekap, settings) lulus.

---

## FASE 2 — M1: Upgrade Dependensi 📦

**Tujuan:** mengejar versi stabil terbaru (perbaikan bug/keamanan/performa). **Bertahap per modul**, verifikasi di tiap langkah; `okhttp 4→5` dipisah & diaudit.

### Kondisi saat ini (terverifikasi dari `gradle/libs.versions.toml`)
| Dependency | Terpasang | Target (perlu verifikasi versi stabil terbaru saat eksekusi) |
|---|---|---|
| `compose-bom` | 2024.09.00 | ~2026.xx terbaru (lihat catatan) |
| `lifecycle-runtime-ktx/viewmodel-compose/runtime-compose` | 2.8.7 | 2.11.x |
| `activity-compose` | 1.10.1 | 1.13.x |
| `room` | 2.7.0 | 2.8.x |
| `okhttp` | 4.10.0 | 5.x (major — audit breaking changes) |
| `firebase-bom` | 34.15.0 | terbaru |
| `robolectric` / `roborazzi` | 4.16.1 / 1.59.0 | sesuaikan bila diperlukan |

> ⚠️ **Catatan**: versi target harus dicek ke repositori resmi (Maven/Google Maven) **saat eksekusi** — daftar di atas indikatif dari audit 2026-08-06.

### Urutan & verifikasi
| # | Sub-Task | Verifikasi |
|---|----------|-----------|
| 2.1 | Upgrade `compose-bom` + `activity-compose` (satu batch, paling sering berdampingan) | `build` + `testDebugUnitTest` + `verifyRoborazziDebug` (kemungkinan besar **perlu re-record golden** karena font/rendering berubah!) |
| 2.2 | Upgrade `lifecycle` (3 artefak) | `build` + unit test |
| 2.3 | Upgrade `room` 2.7.0 → 2.8.x | `build` + unit test + **migration test** (`AppDatabaseMigrationTest` — pastikan skema v11 tetap valid) |
| 2.4 | Upgrade `firebase-bom` | `build` + unit test |
| 2.5 | Upgrade `okhttp` 4→5 | Audit API berubah (ex: `RequestBody`, interceptor, timeout API). Jika perubahan besar: **defer ke r1.3.0** |
| 2.6 | Regression penuh | `testDebugUnitTest` + `lintDebug` + `verifyRoborazziDebug` + smoke test live (chat, rekap, AI, backup) |

**Exit criteria:** semua test & lint hijau; tidak ada warning kompilasi baru; behavior app identik.

---

## FASE 3 — Perbaikan UX & Verifikasi Lanjutan (hasil audit live)

**Tujuan:** menutup sisa temuan minor + memverifikasi alur yang belum teruji live.

| # | Sub-Task | Detail | Sumber |
|---|----------|--------|--------|
| 3.1 | Quick suggestion chips tetap terlihat saat keyboard terbuka | Pindah/sematkan chips di atas input bar (atau area scrollable) — BUG-05 laporan perangkat nyata | UX |
| 3.2 | Label indikator sync netral | "Gagal sinkron" → "Mode offline" (abu/kuning) saat offline — BUG-06 | UX |
| 3.3 | Reset field chat setelah dialog tutup | Field tidak menyisakan "." / karakter sisa — BUG-08 laporan perangkat nyata | Bug |
| 3.4 | **Uji alur edit pesan end-to-end** | Tap badge finansial → edit → simpan → verifikasi LWW sync (editedAt/serverUpdatedAt) di 2 perangkat emulator | Verifikasi |
| 3.5 | **Uji backup/restore Drive dengan akun nyata** | Backup manual + auto-backup terenkripsi (M5) + restore ke device kedua | Verifikasi |
| 3.6 | **Verifikasi daftar model AI via API `/models`** | `GeminiService.MODEL_NAME` (`gemini-3.5-flash`) & daftar `OpenRouterService.FREE_MODELS` — pastikan tidak retired; tambah fallback dinamis bila perlu | Keandalan |
| 3.7 | (Opsional) Notifikasi pengingat & grafik bulanan | Fitur roadmap; jika dimasukkan ke r1.2.0, buka sub-plan terpisah | Roadmap |
| 3.8 | (Opsional) Indikator detail status sync | Tooltip/label "Menyinkronkan…/Terakhir sinkron 14.32" di Rekap | UX |

**Exit criteria:** semua item selesai sesuai cakupan yang disepakati; live test ulang di emulator lulus.

---

## FASE 4 — Rilis r1.2.0 🚀

| # | Sub-Task | Detail |
|---|----------|--------|
| 4.1 | CHANGELOG.md | Entri r1.2.0 (T3, M1, perbaikan UX, verifikasi) |
| 4.2 | Dokumen | README (fitur baru/struktur baru), docs/DEVELOPER.md (struktur file setelah refactor), PRIVACY_POLICY (bila ada fitur baru) |
| 4.3 | Bump versi | `gradle.properties`: `appVersion=r1.2.0`, `appVersionCode=27` |
| 4.4 | Golden Roborazzi | Re-record final (UI berubah karena refactor/upgrade) |
| 4.5 | CI penuh | `testDebugUnitTest` + `lintDebug` + `verifyRoborazziDebug` + lint firestore rules |
| 4.6 | Commit + tag | `fix: r1.2.0 ...` → push tag `r1.2.0` → GitHub Release |
| 4.7 | Play Store checklist | Jalankan `docs/PLAY_STORE_CHECKLIST.md`; upload AAB release |

---

## Manajemen Risiko

| Risiko | Dampak | Mitigasi |
|--------|--------|----------|
| Refactor T3 merusak UI | Regresi visual/fungsional | Verifikasi per langkah (Roborazzi + smoke test); tidak ubah behavior; batasi scope per commit |
| Upgrade compose-bom mengubah rendering | Golden Roborazzi gagal massal | Re-record goldens di CI runner (ubuntu) — pola yang sudah dipakai r1.1.2; bandingkan diff PNG |
| Upgrade room 2.8.x bermasalah dengan skema v11 | Migration error di produksi | Migration test dijalankan sebelum merge; jalur 8→11 penuh |
| okhttp 5 breaking | Kompilasi gagal / runtime error | Defer ke r1.3.0 jika audit API besar |
| Tag r1.1.3 salah lagi (seperti r1.1.3 lama berisi APK r1.1.2) | Release menyesatkan | Verifikasi `versionName` APK hasil build sebelum upload (FASE 0.5) |

---

## Definisi Selesai (DoD)

- [ ] Semua test unit hijau (termasuk migration test & test baru)
- [ ] Lint PASS (tanpa warning baru)
- [ ] Roborazzi verify PASS (golden diperbarui & di-commit)
- [ ] Live smoke test di emulator: chat→transaksi, rekap, settings/dark mode, Tanya AI, restart — tanpa crash
- [ ] CHANGELOG & docs diperbarui
- [ ] Tag r1.2.0 → GitHub Release berisi APK dengan versionName benar

---

*Rencana hidup — dapat disesuaikan dengan temuan saat eksekusi. Prioritaskan FASE 0 dulu (mengunci r1.1.3).*
