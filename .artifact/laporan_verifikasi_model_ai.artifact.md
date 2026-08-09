# Laporan Verifikasi — Daftar Model AI (FASE 3 item 3.6)

> Verifikasi ketersediaan model AI yang dipakai aplikasi via API `/models` resmi.
> Tanggal: 2026-08-09 · APK: `r1.1.3` (versionCode 26) · Sumber: katalog live + dokumentasi resmi.

---

## 1. Gemini API — `GeminiService.MODEL_NAME = "gemini-3.5-flash"`

**Hasil: ✅ VALID & STABLE** — terverifikasi dari dokumentasi resmi
[Google AI for Developers — Models](https://ai.google.dev/gemini-api/docs/models):

| Model di app | Status | Catatan |
|---|---|---|
| `gemini-3.5-flash` | ✅ Stable & aktif | "Most intelligent model for sustained frontier performance on agentic and coding tasks" |

**Catatan**: Ada generasi lebih baru (`gemini-3.6-flash` stable). Upgrade ke 3.6 bersifat opsional
(meningkatkan kualitas parsing, tapi bukan keharusan — model saat ini tetap berfungsi).

---

## 2. OpenRouter — `OpenRouterService.FREE_MODELS`

**Metode**: `GET https://openrouter.ai/api/v1/models` (live, 400 model di katalog).

### Hasil verifikasi daftar lama
| Model (daftar lama) | Status | Prompt price | Context |
|---|---|---|---|
| `openai/gpt-oss-20b:free` | ✅ Aktif | $0 | 131072 |
| `google/gemma-4-31b-it:free` | ✅ Aktif | $0 | 262144 |
| `nvidia/nemotron-3-ultra-550b-a55b:free` | ✅ Aktif | $0 | 1,000,000 |
| `poolside/laguna-xs-2.1:free` | ✅ Aktif | $0 | 262144 |
| `openrouter/free` (router virtual) | ✅ Aktif | $0 | 200000 |
| **`inclusionai/ling-3.0-flash:free`** | ❌ **RETIRED** | — | — |

**Temuan kritis**: `inclusionai/ling-3.0-flash:free` **tidak lagi ada di katalog**
(versi berbayar `inclusionai/ling-3.0-flash` masih tersedia @$0.000000021/prompt token).
Kalau tidak diperbaiki, model ini selalu gagal (404) → app kehilangan 1 slot rotasi AI.

### Perbaikan (komit ke `OpenRouterService.kt`)
Daftar `FREE_MODELS` diperbarui (7 entri, prioritas model terkuat dulu):

```kotlin
private val FREE_MODELS = listOf(
    "google/gemma-4-31b-it:free",          // ✅ terverifikasi $0, 262k
    "google/gemma-4-26b-a4b-it:free",      // ✅ BARU: $0, 262k, VISION-READY (foto nota!)
    "openai/gpt-oss-20b:free",             // ✅ terverifikasi $0
    "nvidia/nemotron-3-ultra-550b-a55b:free", // ✅ terverifikasi $0, 1M ctx
    "inclusionai/ling-3.0-tiny:free",      // ✅ BARU: $0, 262k (pengganti keluarga ling)
    "poolside/laguna-xs-2.1:free",         // ✅ terverifikasi $0
    "openrouter/free",                     // router virtual (jaring pengaman)
)
```

**Verifikasi model baru** (dari katalog live):
- `inclusionai/ling-3.0-tiny:free` → prompt=$0, context=262144 ✅
- `google/gemma-4-26b-a4b-it:free` → prompt=$0, context=262144, **input modalities: image+text+video** ✅
  (penting: mendukung foto nota — model vision di daftar)

**Bonus**: total 14 model `:free` aktif di katalog saat ini — daftar app mencakup
7 di antaranya + router virtual, rotasi 2 jalur AI tetap berfungsi.

---

## 3. Verifikasi build

- `./gradlew :app:compileDebugKotlin` → **BUILD SUCCESSFUL** ✅
- Tidak ada test yang mereferensikan `FREE_MODELS` (daftar model = konstanta internal; perubahan aman).

---

## Ringkasan

| Item | Status |
|---|---|
| Gemini `gemini-3.5-flash` | ✅ Valid & stable |
| OpenRouter 5/6 model lama | ✅ Masih aktif |
| OpenRouter `ling-3.0-flash:free` | ❌ Retired → **diganti** 2 model baru (ling-3.0-tiny + gemma-4-26b) |
| Compile | ✅ PASS |

*Laporan berbasis verifikasi API live (katalog `/models`) + dokumentasi resmi, bukan analisis statis.*
