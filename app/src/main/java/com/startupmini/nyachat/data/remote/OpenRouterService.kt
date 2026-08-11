package com.startupmini.nyachat.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Layanan AI OpenRouter (BYOK): pengguna menempel API key OpenRouter miliknya
 * sendiri lewat Pengaturan → "Kunci OpenRouter API". Key disimpan lokal di
 * perangkat dan dipakai langsung — server tidak menyediakan API key.
 *
 * Menggunakan model GRATIS dengan rotasi otomatis: kalau satu model kena rate
 * limit (429) / kuota habis / error, otomatis mencoba model gratis berikutnya.
 * Kalau semua gagal, mengembalikan null agar pemanggil fallback ke mesin offline.
 */
object OpenRouterService {

    @Volatile
    var userApiKey: String? = null

    /** Key aktif: HANYA key pengguna (BYOK). Tidak ada key bawaan di APK —
     *  key yang dikompilasi bisa diekstrak, jadi produksi murni BYOK. */
    fun activeApiKey(): String? =
        userApiKey?.takeIf { it.isNotBlank() }

    private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

    /** Daftar model gratis (divalidasi langsung dari https://openrouter.ai/api/v1/models
     *  per 2026-08-09 — total 14 model `:free` aktif).
     *  Entri terakhir "openrouter/free" adalah router virtual yang otomatis
     *  memilih model gratis yang sedang tersedia.
     *  Catatan 3.6: `inclusionai/ling-3.0-flash:free` di-retired OpenRouter
     *  (hanya versi berbayar yang tersisa) → diganti `ling-3.0-tiny:free` dan
     *  `gemma-4-26b-a4b-it:free` (keduanya terverifikasi aktif & gratis). */
    private val FREE_MODELS = listOf(
        "google/gemma-4-31b-it:free",
        "google/gemma-4-26b-a4b-it:free",
        "openai/gpt-oss-20b:free",
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "inclusionai/ling-3.0-tiny:free",
        "poolside/laguna-xs-2.1:free",
        "openrouter/free",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Kirim prompt ke model gratis OpenRouter dengan rotasi otomatis saat gagal.
     *  imagePath opsional: kalau ada, dikirim sebagai bagian image_url (data URI)
     *  supaya model vision bisa membaca foto nota. */
    suspend fun completeChat(prompt: String, imagePath: String? = null): String? = withContext(Dispatchers.IO) {
        val key = activeApiKey() ?: return@withContext null

        for (model in FREE_MODELS) {
            try {
                val text = tryModel(key, model, prompt, imagePath)
                if (!text.isNullOrBlank()) return@withContext text
            } catch (e: Exception) {
                Log.w("OpenRouterService", "Model gagal, rotasi ke model gratis berikutnya", e)
            }
        }
        null
    }

    private fun tryModel(key: String, model: String, prompt: String, imagePath: String? = null): String? {
        // Dengan foto → content berupa array (teks + image_url); tanpa foto → string biasa.
        val content: Any = if (imagePath != null) {
            val b64 = ImageFileUtil.encodeBase64(imagePath)
            if (b64 != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                    )
            } else {
                prompt
            }
        } else {
            prompt
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)
            ))
            .put("temperature", 0.2)

        val request = Request.Builder()
            .url(BASE_URL)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 429 (rate limit) / 402 (saldo habis) / 404 (model tak ada) → lempar agar dirotasi
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val raw = response.body?.string() ?: return null
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            return choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content", "")
        }
    }
}
