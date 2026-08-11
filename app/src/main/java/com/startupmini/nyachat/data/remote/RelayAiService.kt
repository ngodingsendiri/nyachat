package com.startupmini.nyachat.data.remote

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Relay AI server (FASE 4) — akses model AI lewat Cloud Function `aiComplete`
 * yang memegang API key MILIK SERVER (Firebase Functions secrets).
 *
 * Kenapa ada: aplikasi ini murni BYOK (Bring Your Own Key) — kalau user tidak
 * mengisi kunci Gemini/OpenRouter di Pengaturan, deteksi transaksi jatuh ke
 * mesin heuristik offline yang kaku. Dengan relay, user yang belum mengisi
 * kunci tetap mendapat deteksi AI karena servernya yang membayar/memakai kunci.
 *
 * Keamanan: key server TIDAK pernah dikompilasi ke APK (tidak bisa diekstrak).
 * Firebase Functions SDK otomatis melampirkan Firebase Auth ID token user yang
 * sedang login ke callable — server memverifikasi (request.auth) sebelum
 * memproses. App yang belum login tidak bisa memakai relay.
 *
 * Posisi di kaskade GeminiService: OpenRouter (BYOK) → Gemini (BYOK) →
 * [RelayAiService] → heuristik offline.
 */
object RelayAiService {

    private const val TAG = "RelayAiService"
    private const val FUNCTION_NAME = "aiComplete"

    /**
     * true selama relay layak dicoba. Di-set false permanen kalau fungsi tidak
     * terdeploy (NOT_FOUND) atau FirebaseApp tidak terinisialisasi, supaya
     * kaskade tidak buang waktu memanggil fungsi yang pasti gagal tiap pesan.
     */
    @Volatile
    private var relayUsable = true

    /**
     * Status jaringan yang diketahui TERAKHIR (default null = belum tahu).
     * Di-set dari NetworkMonitor via [setNetworkOnline] (sinyal yang sama
     * dengan indikator sync). Saat jelas offline, relay TIDAK dicoba — pesan
     * langsung jatuh ke heuristik offline tanpa menunggu timeout jaringan
     * (regresi latensi yang dicegah saat review FASE 4).
     */
    @Volatile
    private var networkOnline: Boolean? = null

    /** Batas waktu internal untuk satu panggilan relay — jauh lebih pendek dari
     *  AI_CALL_TIMEOUT_MS (60 s) supaya saat jaringan lambat/gagal diam-diam,
     *  kaskade tidak menghabiskan jatah waktu seluruhnya. */
    private const val RELAY_CALL_TIMEOUT_MS = 15_000L

    /** Teruskan status jaringan dari NetworkMonitor (sinyal yang sama dengan sync). */
    fun setNetworkOnline(online: Boolean) {
        networkOnline = online
        // Jaringan jelas mati → tidak ada gunanya mencoba relay (hemat waktu).
        if (!online) {
            relayUsable = false
            return
        }
        // Jaringan pulih → relay layak dicoba lagi. Ini juga membatalkan flag
        // "mati permanen" dari NOT_FOUND: fungsi aiComplete bisa di-deploy
        // kapan saja SETELAH app terinstall (kasus nyata: APK terpasang dulu,
        // Cloud Function menyusul) — mematikan selamanya sampai app restart
        // membuat relay tak pernah aktif walau server sudah siap.
        relayUsable = true
    }

    /** Reset saat login/logout / app baru — untuk keperluan test & lifecycle. */
    internal fun resetUsable() {
        relayUsable = true
        networkOnline = null
    }

    /**
     * true kalau relay belum terbukti mati DAN jaringan tidak diketahui mati —
     * dipakai isAiAvailable() (L6). Saat offline murni, mengembalikan false
     * supaya kaskade langsung ke heuristik (bukan nunggu timeout relay).
     */
    fun isAvailable(): Boolean =
        relayUsable && networkOnline != false

    /**
     * Kirim prompt ke relay server; kembalikan teks mentah dari model AI, atau
     * null kalau relay gagal (tidak terdeploy / offline / semua penyedia gagal).
     * imageBase64 opsional (foto nota) — dikirim agar model vision bisa membaca.
     */
    suspend fun completeChat(prompt: String, imageBase64: String? = null): String? {
        if (!isAvailable()) return null
        return try {
            val data = buildMap {
                put("prompt", prompt)
                if (!imageBase64.isNullOrBlank()) put("imageBase64", imageBase64)
            }
            // Timeout internal: kalau relay lambat/gagal diam-diam, kaskade tetap
            // bisa jatuh ke heuristik offline tanpa menunggu 60 s penuh.
            val result = withTimeoutOrNull(RELAY_CALL_TIMEOUT_MS) {
                FirebaseFunctions.getInstance()
                    .getHttpsCallable(FUNCTION_NAME)
                    .call(data)
                    .await()
            }
            val text = (result?.data as? Map<*, *>)?.get("text") as? String
            text?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // Nonaktifkan PERMANEN hanya kalau pasti sia-sia dicoba lagi:
            // fungsi tidak terdeploy (NOT_FOUND) atau FirebaseApp belum aktif
            // (unit test / salah init). Gagal lain (network, timeout, 500,
            // unauthenticated) bersifat sementara — jangan dimatikan supaya
            // relay bisa pulih setelah login/network normal.
            if (msg.contains("NOT_FOUND", ignoreCase = true) ||
                msg.contains("FirebaseApp", ignoreCase = true)
            ) {
                relayUsable = false
            }
            // runCatching: di unit test (tanpa Robolectric) android.util.Log
            // tidak dimock — logging tidak boleh mematikan jalur pemanggil.
            runCatching { Log.w(TAG, "Relay gagal, lanjut jalur berikutnya: $msg") }
            null
        }
    }
}
