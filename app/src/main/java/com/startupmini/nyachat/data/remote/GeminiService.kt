package com.startupmini.nyachat.data.remote

import android.util.Log
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * SATU transaksi hasil parse (r1.2.4 — tuning AI). Pesan bisa memuat BANYAK
 * transaksi ("beli bakso 15rb, bensin 30rb sama rokok 20rb") sehingga hasil
 * parse memakai [AiChatParseResult.transactions]; field tunggal pada
 * [AiChatParseResult] (type/category/amount/description) tetap diisi dengan
 * ringkasan (transaksi pertama + total nominal) untuk kompatibilitas UI lama.
 */
data class AiTransaction(
    val type: String, // "PENGELUARAN" or "PEMASUKAN"
    val category: String,
    val amount: Double,
    val description: String,
    // Timestamp eksplisit (tuning AI): diisi saat pesan menyebut waktu transaksi
    // ("kemarin", "minggu lalu", tanggal tertentu). null → pakai waktu pesan.
    val timestamp: Long? = null
)

data class AiChatParseResult(
    val containsTransaction: Boolean,
    val type: String? = null, // "PENGELUARAN" or "PEMASUKAN"
    val category: String? = null,
    val amount: Double? = null,
    val description: String? = null,
    val aiReply: String,
    // M7: asal deteksi — "AI" (Gemini/OpenRouter) atau "HEURISTIK" (fallback
    // offline). Disimpan di ChatMessage.detectedBy untuk indikator badge UI.
    val detectedBy: String? = null,
    // r1.2.4: daftar transaksi lengkap (bisa > 1). Kosong → hanya field tunggal.
    val transactions: List<AiTransaction> = emptyList()
) {
    /**
     * Semua transaksi hasil parse — sumber kebenaran untuk penyimpanan.
     * Format lama (field tunggal) dipetakan ke list 1 item bila list kosong.
     */
    val all: List<AiTransaction>
        get() = if (transactions.isNotEmpty()) {
            transactions
        } else if (containsTransaction && amount != null && amount > 0) {
            listOf(
                AiTransaction(
                    type = type ?: Constants.TransactionTypes.EXPENSE,
                    category = category ?: Constants.Categories.MISC,
                    amount = amount,
                    description = description ?: "",
                    timestamp = null
                )
            )
        } else {
            emptyList()
        }
}

object GeminiService {

    /** API key Gemini milik pengguna (BYOK) — diisi lewat Pengaturan → "Kunci Gemini API".
     *  Key ini disimpan lokal di perangkat dan dipakai langsung ke Google.
     *  TIDAK ada key bawaan di APK: key yang dikompilasi bisa diekstrak siapa saja,
     *  jadi produksi murni BYOK (user menyediakan key-nya sendiri). */
    @Volatile
    var userApiKey: String? = null

    private fun getApiKey(): String =
        userApiKey?.takeIf { it.isNotBlank() } ?: ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MODEL_NAME = "gemini-3.5-flash"

    /** Batas waktu KESELURUHAN kaskade AI (rotasi OpenRouter + Gemini). Tanpa
     *  ini, rotasi 6 model × timeout baca OkHttp bisa membuat pesan menggantung
     *  beberapa menit (B6). Habis waktu → langsung fallback heuristik offline. */
    internal const val AI_CALL_TIMEOUT_MS = 60_000L

    /** L6: saran cepat statis (fallback saat tanpa key AI / riwayat kosong). */
    internal val DEFAULT_SUGGESTIONS =
        listOf("Makan siang 25.000", "Bensin 20.000", "Beli token listrik 50.000")

    /**
     * Rapikan deskripsi transaksi untuk saran cepat: buang nominal/angka mentah
     * yang menempel pada deskripsi (hasil parse chat menyimpan teks asli seperti
     * "beli mie ayam 20000" sehingga fallback lama menghasilkan
     * "beli mie ayam 20000 20000" — angka dobel), lalu kapitalisasi awal.
     */
    internal fun cleanSuggestionDescription(raw: String): String {
        // NUMBER_UNIT_PATTERN adalah java.util.regex.Pattern — pakai matcher.
        val cleaned = NUMBER_UNIT_PATTERN.matcher(raw)
            .replaceAll(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isEmpty()) {
            cleaned
        } else {
            cleaned.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    /** Format nominal untuk saran cepat: 20000.0 → "20.000" (titik ribuan id-ID). */
    internal fun formatSuggestionAmount(amount: Double): String =
        String.format(Locale("id", "ID"), "%,d", amount.toLong())

    /**
     * Fallback offline berbasis riwayat transaksi PENGELUARAN — deskripsi
     * dibersihkan dari angka & nominal diformat rapi (L6). Hasil dideduplikasi
     * & dibatasi 4. Pemasukan tidak dijadikan saran pengeluaran (selaras dengan
     * prompt AI & DEFAULT_SUGGESTIONS yang semuanya pengeluaran).
     */
    internal fun buildOfflineSuggestions(transactions: List<FinancialTransaction>): List<String> {
        val expense = transactions.filter {
            it.type == Constants.TransactionTypes.EXPENSE
        }
        val cleaned = expense
            .map { trans ->
                val desc = cleanSuggestionDescription(trans.description)
                val amount = formatSuggestionAmount(trans.amount)
                if (desc.isEmpty()) amount else "$desc $amount"
            }
            .distinct()
            .take(4)
        return cleaned.ifEmpty { DEFAULT_SUGGESTIONS }
    }

    /**
     * Sanitasi saran hasil AI: jamin format rapi walau model mengembalikan
     * angka dobel ("Beli mie ayam 20000 20000") — angka diekstrak ulang,
     * deskripsi dibersihkan, nominal diformat titik ribuan. Saran tanpa angka
     * (kreatif) dibiarkan apa adanya.
     */
    internal fun sanitizeSuggestion(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val amount = extractAmountFromText(trimmed.lowercase())
        val desc = cleanSuggestionDescription(trimmed)
        return when {
            amount != null && desc.isNotEmpty() -> "$desc ${formatSuggestionAmount(amount)}"
            amount != null -> formatSuggestionAmount(amount)
            else -> trimmed
        }
    }

    suspend fun parseChatMessage(
        messageText: String,
        sender: String,
        recentContext: List<ChatMessage>,
        imagePath: String? = null
    ): AiChatParseResult = withContext(Dispatchers.IO) {
        // Pesan dengan foto nota → prompt khusus membaca nota; teks biasa → prompt standar.
        val prompt = if (imagePath != null) {
            buildReceiptPrompt(messageText, sender, recentContext)
        } else {
            buildParsePrompt(messageText, sender, recentContext)
        }

        // Seluruh jalur AI dibatasi waktu total (B6) — lewat batas, fallback offline.
        val aiParsed = withTimeoutOrNull(AI_CALL_TIMEOUT_MS) {
            // 1) OpenRouter (BYOK) — model gratis dengan rotasi otomatis
            if (OpenRouterService.activeApiKey() != null) {
                try {
                    val text = OpenRouterService.completeChat(prompt, imagePath)
                    if (text != null) {
                        val parsed = parseJsonResponse(wrapOpenAiText(text), messageText, sender)
                        if (parsed != null) return@withTimeoutOrNull parsed
                    }
                } catch (e: Exception) {
                    Log.w("GeminiService", "OpenRouter/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 2) Gemini API (BYOK atau key bawaan app)
            val apiKey = getApiKey()
            if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                try {
                    val jsonResponse = callGeminiApi(prompt, apiKey, imagePath)
                    val parsed = parseJsonResponse(jsonResponse, messageText, sender)
                    if (parsed != null) {
                        return@withTimeoutOrNull parsed
                    }
                } catch (e: Exception) {
                    Log.w("GeminiService", "Gemini/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 3) Relay server (FASE 4): key AI milik server — dipakai saat user
            //    belum mengisi kunci sendiri ATAU kunci BYOK-nya gagal. Auth Firebase
            //    otomatis dilampirkan SDK; server memverifikasi & memanggil AI.
            val relayText = relayComplete(prompt, imagePath)
            if (relayText != null) {
                val parsed = parseJsonResponse(wrapOpenAiText(relayText), messageText, sender)
                if (parsed != null) {
                    return@withTimeoutOrNull parsed
                }
            }
            null
        }

        // 4) Fallback: teks biasa pakai mesin offline; foto nota tanpa AI hanya tersimpan
        //    (tidak bisa dibaca tanpa kunci AI vision). Juga dipakai saat habis waktu.
        // r1.4.0 (audit Finance AI): AI ONLINE tapi salah bilang "tidak ada
        // transaksi" pada pesan multi-nominal (paling rawan salah) → verifikasi
        // ulang heuristik supaya transaksi tidak hilang. Pesan koreksi/pembatalan
        // dan pertanyaan keuangan TIDAK diverifikasi (AI sengaja tidak mencatat).
        return@withContext when {
            aiParsed != null && aiParsed.containsTransaction -> {
                // r1.4.0 (audit campuran pemasukan+pengeluaran): AI ONLINE juga bisa
                // mengembalikan transaksi TIDAK LENGKAP — hanya 1 dari 2+ nominal
                // (paling sering pada pesan campuran income+expense). Bila jumlah
                // transaksi AI < jumlah nominal di pesan, lengkapi dengan hasil
                // heuristik yang BELUM terwakili (anti-duplikat).
                val aiCount = aiParsed.all.size
                if (aiCount < countAmounts(messageText) && shouldHeuristicBackup(messageText)) {
                    mergeAiWithHeuristic(aiParsed, offlineHeuristicParse(messageText, sender))
                } else {
                    aiParsed
                }
            }
            aiParsed != null && !aiParsed.containsTransaction && shouldHeuristicBackup(messageText) ->
                offlineHeuristicParse(messageText, sender)
            else -> aiParsed ?: offlineHeuristicParse(messageText, sender)
        }
    }

    /**
     * Gabungkan hasil AI yang kurang lengkap dengan transaksi heuristik yang
     * belum terwakili (r1.4.0 — audit campuran income+expense). Anti-duplikat:
     * transaksi heuristik dengan tipe+nominal yang SAMA dengan AI tidak
     * ditambahkan lagi. Hanya melengkapi, tidak pernah mengganti hasil AI.
     */
    internal fun mergeAiWithHeuristic(
        ai: AiChatParseResult,
        heuristic: AiChatParseResult
    ): AiChatParseResult {
        if (!ai.containsTransaction || heuristic.transactions.isEmpty()) return ai
        val existing = ai.all
        val additions = heuristic.transactions.filter { h ->
            existing.none { e ->
                e.type == h.type &&
                    kotlin.math.abs(e.amount - h.amount) < 0.5
            }
        }
        if (additions.isEmpty()) return ai
        val merged = existing + additions
        return ai.copy(
            transactions = merged,
            amount = merged.sumOf { it.amount }
        )
    }


    /** L6: true kalau setidaknya satu jalur AI tersedia (OpenRouter/Gemini BYOK
     *  atau relay server yang belum terbukti mati). */
    fun isAiAvailable(): Boolean {
        val key = getApiKey()
        return OpenRouterService.activeApiKey() != null ||
            key.isNotBlank() && key != "MY_GEMINI_API_KEY" ||
            RelayAiService.isAvailable()
    }

    /** Jalur relay server (FASE 4) — dipakai setelah OpenRouter & Gemini BYOK gagal. */
    private suspend fun relayComplete(prompt: String, imagePath: String? = null): String? {
        if (!RelayAiService.isAvailable()) return null
        return try {
            val imageBase64 = imagePath?.let { ImageFileUtil.encodeBase64(it) }
            RelayAiService.completeChat(prompt, imageBase64)
        } catch (e: Exception) {
            Log.w("GeminiService", "Relay gagal, lanjut jalur berikutnya", e)
            null
        }
    }

    suspend fun generateFrequentTransactionSuggestions(
        transactions: List<FinancialTransaction>
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (transactions.isEmpty()) {
            return@withContext DEFAULT_SUGGESTIONS
        }
        // L6: tanpa key AI, jangan buang waktu/timeout di jalur AI — langsung
        // fallback offline berbasis riwayat transaksi (personal tetap terjaga),
        // bukan statis kaku. Jalur AI tetap dilewati → hemat kuota & delay.
        if (!isAiAvailable()) {
            return@withContext buildOfflineSuggestions(transactions)
        }

        // Hanya PENGELUARAN yang jadi bahan saran (selaras dengan permintaan
        // user: rekomendasi pengeluaran dari data rekap). Pemasukan dikecualikan.
        val expense = transactions.filter { it.type == Constants.TransactionTypes.EXPENSE }
        if (expense.isEmpty()) {
            return@withContext DEFAULT_SUGGESTIONS
        }
        val transSummary = expense.take(30).joinToString("\n") {
            "- [${it.type}] ${it.description} (Rp ${it.amount.toLong()})"
        }

        val prompt = """
            Kamu adalah analis asisten untuk aplikasi pencatat keuangan chat.
            Diberikan daftar transaksi terakhir pengguna di bawah ini:

            $transSummary

            Tugasmu adalah menganalisis kebiasaan transaksi mereka (yang paling berulang/rutin) lalu menghasilkan 4 sampai 5 teks prompt singkat (rekomendasi chat quick-add) yang bisa mereka klik untuk menginput pengeluaran atau pemasukan dengan cepat berdasarkan pola mereka.
            Tulis saran dalam Bahasa Indonesia yang NATURAL & RAPI: awali dengan huruf kapital, satu nominal di akhir dengan TITIK ribuan (mis. 20000 ditulis 20.000), JANGAN mengulang angka dua kali.
            Contoh output yang diharapkan (sesuaikan dengan isi riwayat transaksi pengguna):
            "Beli bensin 25.000"
            "Makan siang 20.000"
            "Bayar listrik 100.000"
            "Belanja sayur 50.000"

            KEMBALIKAN OUTPUTMU SEBAGAI JSON ARRAY STRING SAJA. Contoh: ["Makan siang 20.000", "Bensin 15.000"].
            Jangan tambahkan penjelasan apa pun di luar JSON Array.
        """.trimIndent()

        // Seluruh jalur AI dibatasi waktu total (B6) — lewat batas, langsung
        // fallback offline supaya UI saran cepat tidak menggantung.
        val aiSuggestions = withTimeoutOrNull(AI_CALL_TIMEOUT_MS) {
            // 1) OpenRouter (BYOK) — model gratis dengan rotasi otomatis
            if (OpenRouterService.activeApiKey() != null) {
                try {
                    val text = OpenRouterService.completeChat(prompt)
                    if (!text.isNullOrBlank()) {
                        val cleanedText = text.replace("```json", "").replace("```", "").trim()
                        val jsonArray = JSONArray(cleanedText)
                        val suggestions = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            suggestions.add(sanitizeSuggestion(jsonArray.getString(i)))
                        }
                        if (suggestions.isNotEmpty()) return@withTimeoutOrNull suggestions
                    }
                } catch (e: Exception) {
                    Log.w("GeminiService", "OpenRouter/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 2) Gemini API
            if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                try {
                    val jsonResponse = callGeminiApi(prompt, apiKey)
                    val text = extractTextFromGeminiResponse(jsonResponse)
                    if (!text.isNullOrBlank()) {
                        val cleanedText = text.replace("```json", "").replace("```", "").trim()
                        val jsonArray = JSONArray(cleanedText)
                        val suggestions = mutableListOf<String>()
                        for (i in 0 until jsonArray.length()) {
                            suggestions.add(sanitizeSuggestion(jsonArray.getString(i)))
                        }
                        if (suggestions.isNotEmpty()) return@withTimeoutOrNull suggestions
                    }
                } catch (e: Exception) {
                    Log.w("GeminiService", "Gemini/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 3) Relay server (FASE 4) — saran cepat via key milik server.
            val relayText = relayComplete(prompt)
            if (!relayText.isNullOrBlank()) {
                try {
                    val cleanedText = relayText.replace("```json", "").replace("```", "").trim()
                    val jsonArray = JSONArray(cleanedText)
                    val suggestions = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        suggestions.add(sanitizeSuggestion(jsonArray.getString(i)))
                    }
                    if (suggestions.isNotEmpty()) return@withTimeoutOrNull suggestions
                } catch (e: Exception) {
                    Log.w("GeminiService", "Relay/parsing saran gagal", e)
                }
            }
            null
        }
        if (aiSuggestions != null) return@withContext aiSuggestions

        // 4) Fallback offline heuristic (L6): deskripsi dibersihkan dari angka
        //    & nominal diformat rapi — tidak ada lagi "beli mie ayam 20000 20000".
        return@withContext buildOfflineSuggestions(transactions)
    }

    suspend fun generateFinancialAuditReport(
        transactions: List<FinancialTransaction>,
        totalIncome: Double,
        totalExpense: Double
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        val balance = totalIncome - totalExpense

        // Audit #7: insight terpersonalisasi dari data nyata — dipakai di prompt
        // AI DAN di fallback offline, supaya rekomendasi menyebut pos/angka milik user.
        val insights = com.startupmini.nyachat.data.analytics.FinancialInsightsEngine.compute(transactions)
        val insightLines = com.startupmini.nyachat.data.analytics.FinancialInsightsEngine.describeForPrompt(insights)

        val transSummary = transactions.take(20).joinToString("\n") {
            "- [${it.type}] ${it.category}: Rp ${it.amount.toLong()} (${it.description}) oleh ${it.loggedBy}"
        }

        val prompt = """
            Kamu adalah konsultan dan analis keuangan profesional untuk Nyachat.
            Berikut adalah rekap ringkas pengeluaran dan pemasukan grup/kelompok/keluarga periode ini:
            
            Total Pemasukan: Rp ${totalIncome.toLong()}
            Total Pengeluaran: Rp ${totalExpense.toLong()}
            Sisa Saldo: Rp ${balance.toLong()}
            
            Insight yang sudah dihitung dari data mereka:
            $insightLines
            
            Daftar Transaksi Terakhir:
            $transSummary
            
            Berikan evaluasi kesehatan keuangan ini dalam Bahasa Indonesia yang profesional, obyektif, dan solutif.
            JANGAN menjawab generik — rujuk POSITIF KONKRET di atas: sebut kategori terbesar,
            pengeluaran tunggal terbesar, pengguna yang paling banyak membelanjakan, dan arah
            tren pengeluaran (naik/turun/stagnan) dengan angka yang sesuai.
            Format tanggapanmu secara terstruktur:
            
            📌 **Evaluasi & Analisis Arus Kas**
            (Analisis jujur dan tajam mengenai rasio pengeluaran vs pemasukan, serta pos belanja paling menonjol)
            
            💡 **Rekomendasi Strategis**
            1. (Saran efisiensi pos pengeluaran operasional/harian)
            2. (Saran alokasi dana cadangan atau perencanaan anggaran ke depan)
            
            Gunakan nada bicara yang profesional, jelas, dan mengedukasi.
        """.trimIndent()

        // Seluruh jalur AI dibatasi waktu total (B6) — lewat batas, langsung
        // laporan offline supaya dialog AI tidak freeze.
        val aiReport = withTimeoutOrNull(AI_CALL_TIMEOUT_MS) {
            // 1) OpenRouter (BYOK) — model gratis dengan rotasi otomatis
            if (OpenRouterService.activeApiKey() != null) {
                try {
                    val text = OpenRouterService.completeChat(prompt)
                    if (!text.isNullOrBlank()) {
                        return@withTimeoutOrNull text
                    }
                } catch (e: Exception) {
                    Log.w("GeminiService", "OpenRouter/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 2) Gemini API
            if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                try {
                    val jsonResponse = callGeminiApi(prompt, apiKey)
                    val text = extractTextFromGeminiResponse(jsonResponse)
                    if (!text.isNullOrBlank()) {
                        return@withTimeoutOrNull text
                    }
                } catch (e: Exception) {
                    Log.w("GeminiService", "Gemini/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 3) Relay server (FASE 4) — laporan audit via key milik server.
            val relayText = relayComplete(prompt)
            if (!relayText.isNullOrBlank()) return@withTimeoutOrNull relayText
            null
        }
        if (aiReport != null) return@withContext aiReport

        // 4) Offline Fallback Report — berbasis data nyata (bukan template kaku).
        return@withContext buildOfflineAuditReport(insights, balance)
    }

    /** Laporan offline yang merujuk insight data nyata (audit #7). */
    private fun buildOfflineAuditReport(
        ins: com.startupmini.nyachat.data.analytics.FinancialInsights,
        balance: Double
    ): String {
        val sb = StringBuilder()
        sb.appendLine("📌 **Evaluasi & Analisis Arus Kas**")
        sb.appendLine("• **Arus Kas**: Pemasukan Rp ${ins.totalIncome.toLong()} vs Pengeluaran Rp ${ins.totalExpense.toLong()} (Saldo: Rp ${balance.toLong()}).")
        when {
            ins.totalExpense > ins.totalIncome && ins.totalIncome > 0 ->
                sb.appendLine("• **Tinjauan**: Pengeluaran melampaui pemasukan (rasio ${(ins.expenseRate * 100).toInt()}%). Perlu pengetatan. 🚨")
            ins.savingsRate > 0.2 ->
                sb.appendLine("• **Tinjauan**: Rasio sehat; menabung ${(ins.savingsRate * 100).toInt()}% dari pemasukan. Pertahankan!")
            else ->
                sb.appendLine("• **Tinjauan**: Arus kas cukup pas (tabungan ~${(ins.savingsRate * 100).toInt()}%). Waspada perubahan tak terduga.")
        }
        ins.topExpenseCategory?.let { cat ->
            sb.appendLine("• **Pos terbesar**: $cat (Rp ${ins.topExpenseAmount.toLong()}, ${(ins.topExpensePct * 100).toInt()}% pengeluaran).")
        }
        ins.biggestSingleDesc?.let { desc ->
            sb.appendLine("• **Transaksi tunggal terbesar**: \"$desc\" (Rp ${ins.biggestSingleAmount.toLong()}).")
        }
        sb.appendLine("• **Tren**: Pengeluaran ${com.startupmini.nyachat.data.analytics.FinancialInsightsEngine.trendText(ins.expenseChangePct)} dalam 30 hari.")
        if (ins.topSpender != null) {
            sb.appendLine("• **Pengeluaran terbesar**: ${ins.topSpender} (Rp ${ins.topSpenderAmount.toLong()}).")
        }

        sb.appendLine("")
        sb.appendLine("💡 **Rekomendasi Strategis**")
        sb.appendLine("1. **Kendalikan pos terbesar (${ins.topExpenseCategory ?: "operasional"})**: pasang plafon mingguan ±Rp ${((ins.topExpenseAmount * 0.9) / 4).toLong()} agar pengeluaran turun.")
        val savingsTarget = if (ins.savingsRate > 0) {
            ((ins.savingsRate + 0.05) * 100).toInt().coerceAtMost(30)
        } else {
            10
        }
        sb.appendLine("2. **Dana cadangan**: sisihkan ±$savingsTarget% dari pemasukan tiap bulan ke kas cadangan.")
        return sb.toString().trim()
    }

    /**
     * Analisis finansial lanjutan: rekap per bulan + tren + rekomendasi penghematan.
     * Data bulanan dihitung lokal ([MonthlyAnalytics]) lalu diserahkan ke AI
     * (OpenRouter → Gemini → laporan offline). Konsisten dengan alur 3 lapis.
     */
    suspend fun generateMonthlyAnalysisReport(
        transactions: List<FinancialTransaction>
    ): String = withContext(Dispatchers.IO) {
        val monthly = com.startupmini.nyachat.data.analytics.MonthlyAnalytics.groupByMonth(transactions)
        val apiKey = getApiKey()

        if (monthly.isEmpty()) {
            return@withContext "Belum ada transaksi untuk dianalisis. Catat transaksi dulu lewat obrolan!"
        }

        val monthLines = monthly.joinToString("\n") { m ->
            val top = com.startupmini.nyachat.data.analytics.MonthlyAnalytics
                .topExpenseCategory(m, transactions)
            "- ${m.label}: Pemasukan Rp ${m.income.toLong()}, Pengeluaran Rp ${m.expense.toLong()}, Saldo Rp ${m.balance.toLong()}" +
                (if (top != null) " (pos terbesar: $top)" else "")
        }

        val prompt = """
            Kamu adalah konsultan keuangan profesional untuk Nyachat (keluarga/grup di Indonesia).
            Berikut rekap KEUANGAN PER BULAN (dari yang terbaru):

            $monthLines

            Buat analisis bulanan dalam Bahasa Indonesia yang jelas dan praktis:
            1. **Ringkasan Bulanan**: tren pemasukan vs pengeluaran antar bulan (naik/turun), pos terbesar tiap bulan, dan bulan yang paling sehat / paling boros.
            2. **Rekomendasi Penghematan**: 3–5 saran konkret berbasis data di atas (pos mana yang bisa ditekan, berapa targetnya).
            Gunakan markdown sederhana dengan emoji, tetap profesional dan tidak bertele-tele.
        """.trimIndent()

        // Seluruh jalur AI dibatasi waktu total (B6) — lewat batas, langsung
        // laporan offline supaya dialog analisis bulanan tidak freeze.
        val aiReport = withTimeoutOrNull(AI_CALL_TIMEOUT_MS) {
            // 1) OpenRouter (BYOK) — model gratis dengan rotasi otomatis
            if (OpenRouterService.activeApiKey() != null) {
                try {
                    val text = OpenRouterService.completeChat(prompt)
                    if (!text.isNullOrBlank()) return@withTimeoutOrNull text
                } catch (e: Exception) {
                    Log.w("GeminiService", "OpenRouter/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 2) Gemini API
            if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                try {
                    val jsonResponse = callGeminiApi(prompt, apiKey)
                    val text = extractTextFromGeminiResponse(jsonResponse)
                    if (!text.isNullOrBlank()) return@withTimeoutOrNull text
                } catch (e: Exception) {
                    Log.w("GeminiService", "Gemini/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 3) Relay server (FASE 4) — analisis bulanan via key milik server.
            val relayText = relayComplete(prompt)
            if (!relayText.isNullOrBlank()) return@withTimeoutOrNull relayText
            null
        }
        if (aiReport != null) return@withContext aiReport

        // 4) Laporan offline (tanpa internet / tanpa key / habis waktu) — tetap informatif.
        return@withContext buildOfflineMonthlyReport(monthly, transactions)
    }

    private fun buildOfflineMonthlyReport(
        monthly: List<com.startupmini.nyachat.data.analytics.MonthlySummary>,
        transactions: List<FinancialTransaction>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("📅 **Rekap Keuangan Bulanan**")
        monthly.forEachIndexed { i, m ->
            val top = com.startupmini.nyachat.data.analytics.MonthlyAnalytics
                .topExpenseCategory(m, transactions)
            val prev = monthly.getOrNull(i + 1) // sudah urut terbaru dulu
            val trend = if (prev != null) {
                when {
                    m.expense < prev.expense -> " (pengeluaran ↓ lebih hemat dari bulan sebelumnya)"
                    m.expense > prev.expense -> " (pengeluaran ↑ naik dari bulan sebelumnya)"
                    else -> ""
                }
            } else ""
            sb.appendLine("- **${m.label}**: Pemasukan **Rp ${m.income.toLong()}**, Pengeluaran **Rp ${m.expense.toLong()}**, Saldo **Rp ${m.balance.toLong()}**$trend")
            if (top != null) sb.appendLine("  - Pos terbesar: $top")
        }
        val newest = monthly.first()
        sb.appendLine("")
        sb.appendLine("💡 **Rekomendasi**")
        if (newest.expense > newest.income && newest.income > 0) {
            sb.appendLine("1. Pengeluaran bulan terakhir melebihi pemasukan — fokus tekan pos terbesar di atas, mis. kurangi frekuensi belanja mingguan.")
            sb.appendLine("2. Buat plafon per pos (amplop digital) supaya kas tetap positif.")
        } else {
            sb.appendLine("1. Arus kas bulan terakhir sehat. Pertahankan dengan tetap mencatat rutin lewat obrolan.")
            sb.appendLine("2. Sisihkan minimal 10–15% pemasukan ke dana cadangan setiap bulan.")
        }
        return sb.toString().trim()
    }

    /** Jawaban AI bebas (untuk tombol ✨ Tanya AI) — memakai prompt percakapan,
     *  BUKAN parser transaksi. Prioritas: OpenRouter → Gemini → balasan offline. */
    suspend fun askAiChat(prompt: String): String = withContext(Dispatchers.IO) {
        val chatPrompt = """
            Kamu adalah asisten keuangan pribadi 'Nyachat' untuk pasangan/keluarga di Indonesia.
            Jawab pertanyaan berikut dengan bahasa Indonesia yang ramah, jelas, dan praktis.
            Jika pertanyaan menyangkut angka/keuangan, berikan saran yang realistis dan aman.

            Pertanyaan: $prompt
        """.trimIndent()

        // Seluruh jalur AI dibatasi waktu total (B6) — lewat batas, balasan offline.
        val aiReply = withTimeoutOrNull(AI_CALL_TIMEOUT_MS) {
            // 1) OpenRouter (BYOK) — model gratis dengan rotasi otomatis
            if (OpenRouterService.activeApiKey() != null) {
                try {
                    val text = OpenRouterService.completeChat(chatPrompt)
                    if (!text.isNullOrBlank()) return@withTimeoutOrNull text
                } catch (e: Exception) {
                    Log.w("GeminiService", "OpenRouter/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 2) Gemini API (BYOK atau key bawaan app)
            val apiKey = getApiKey()
            if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                try {
                    val jsonResponse = callGeminiApi(chatPrompt, apiKey)
                    val text = extractTextFromGeminiResponse(jsonResponse)
                    if (!text.isNullOrBlank()) return@withTimeoutOrNull text
                } catch (e: Exception) {
                    Log.w("GeminiService", "Gemini/parsing gagal, lanjut jalur berikutnya", e)
                }
            }

            // 3) Relay server (FASE 4) — jawaban AI via key milik server.
            val relayText = relayComplete(chatPrompt)
            if (!relayText.isNullOrBlank()) return@withTimeoutOrNull relayText
            null
        }

        // 4) Balasan offline (tanpa internet / tanpa key / habis waktu)
        return@withContext aiReply ?: offlineChatReply(prompt)
    }

    /**
     * Penanda balasan offline — SATU sumber kebenaran (audit repository 2026-08-14):
     * dipakai deteksi fallback di FinanceRepository lewat FinanceAiService. Jangan
     * duplikasi literal ini di tempat lain.
     */
    internal const val OFFLINE_REPLY_MARKER = "mode AI sedang offline"

    /** True kalau teks memuat penanda balasan offline (marker tunggal). */
    internal fun isOfflineFallbackReply(text: String): Boolean = text.contains(OFFLINE_REPLY_MARKER)

    internal fun offlineChatReply(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("hemat") || lower.contains("nabung") || lower.contains("tabung") ->
                "Tips hemat: (1) catat dulu semua pengeluaran kecil, (2) tetapkan plafon mingguan untuk makan & transportasi, (3) sisihkan 10–15% pemasukan ke dana cadangan di awal bulan, (4) pisahkan uang rutin per pos (amplop digital). Mau kubantu susun anggaran mingguanmu? 😊"
            lower.contains("gaji") || lower.contains("pemasukan") ->
                "Untuk mengatur gaji: alokasikan ±50% untuk kebutuhan pokok, 30% tabungan/cadangan, dan 20% keinginan. Mulai dengan mencatat semua transaksi lewat obrolan ini — nanti aku rekap & evaluasi otomatis. 💰"
            lower.contains("hutang") || lower.contains("utang") ->
                "Untuk melunasi utang: pilih metode snowball (lunasi yang terkecil dulu biar semangat) atau avalanche (lunasi yang bunganya terbesar dulu biar lebih hemat). Sisihkan minimal 20% pemasukan untuk cicilan. 💪"
            else ->
                "Aku adalah asisten keuangan Nyachat. Aku bisa mencatat transaksi dari obrolan, memberi rekap & analisis pengeluaran, serta tips keuangan. Saat ini $OFFLINE_REPLY_MARKER — sambungkan kunci OpenRouter/Gemini di menu Pengaturan agar aku bisa menjawab lebih pintar! 😊"
        }
    }

    /**
     * Daftar kategori valid dinamis dari Constants (tuning AI r1.2.4) — satu
     * sumber kebenaran, tidak boleh diverge dari daftar yang dipakai UI Rekap.
     */
    private fun categoryListForPrompt(): String {
        val expense = Constants.Categories.EXPENSE_ALL.joinToString("\n- ")
        val income = Constants.Categories.INCOME_ALL.joinToString("\n- ")
        return "PENGELUARAN:\n- $expense\n\nPEMASUKAN:\n- $income"
    }

    /**
     * Prompt khusus untuk foto nota/bukti belanja — AI diminta membaca isi foto
     * lalu mengeluarkan JSON transaksi (format array, tuning AI r1.2.4).
     */
    private fun buildReceiptPrompt(messageText: String, sender: String, recentContext: List<ChatMessage>): String {
        val contextBlock = contextBlock(recentContext)
        return """
            Kamu adalah 'Asisten Nyachat' yang bertugas membaca FOTO NOTA / BUKTI BELANJA / STRUK dari $sender.

            Konteks obrolan terbaru (untuk mencocokkan kategori/deskripsi yang konsisten):
            ${contextBlock.ifEmpty { "— (riwayat kosong)" }}

            Foto yang kamu terima adalah nota belanja. Analisis foto tersebut dan catat TOTAL pengeluarannya.
            Keterangan tambahan dari pengirim: "$messageText"

            KATEGORI VALID (hanya dari daftar ini, sesuaikan jenis barang di nota):
            ${categoryListForPrompt()}

            Nominal dalam RUPIAH PENUH tanpa desimal (contoh: 150000 untuk Rp 150.000).

            Keluarkan jawaban HANYA berupa JSON valid:
            {
              "containsTransaction": true,
              "transactions": [
                {
                  "type": "PENGELUARAN",
                  "category": "Groceries & Sembako",
                  "amount": 150000,
                  "description": "Nota belanja [nama toko di nota]",
                  "date": ""
                }
              ],
              "aiReply": "Nota belanja dicatat: Rp 150.000 (Groceries & Sembako)."
            }

            Jika foto bukan nota / tidak terbaca dengan jelas, kirimkan:
            {
              "containsTransaction": false,
              "aiReply": "Foto tersimpan, tapi tidak bisa kubaca sebagai nota. Coba foto ulang dengan cahaya cukup & seluruh nota terlihat."
            }
        """.trimIndent()
    }

    /**
     * Prompt parser transaksi (tuning AI r1.2.4): instruksi eksplisit untuk
     * konteks lanjutan, multi-transaksi, koreksi/pembatalan, ambiguitas, dan
     * larangan mencatat non-transaksi — sesuai prinsip utama "lebih baik tidak
     * mencatat daripada mencatat yang salah".
     */
    private fun buildParsePrompt(messageText: String, sender: String, recentContext: List<ChatMessage>): String {
        val contextBlock = contextBlock(recentContext)
        return """
            Kamu adalah 'Asisten Nyachat' yang bertugas memantau obrolan transaksi finansial pada grup, lembaga, atau rumah tangga.

            Konteks obrolan terbaru (untuk mencocokkan kategori/deskripsi yang konsisten):
            ${contextBlock.ifEmpty { "— (riwayat kosong)" }}

            Pesan masuk dari $sender: "$messageText"

            TUGAS & ATURAN:
            1. Deteksi TRANSAKSI KEUANGAN (pengeluaran, iuran, tagihan, pemasukan dana).
            2. SATU pesan bisa memuat BANYAK transaksi ("beli bakso 15 ribu, bensin 30 ribu sama rokok 20 ribu") — kembalikan SEMUA dalam array "transactions", masing-masing dengan nominal dan kategorinya.
            3. KONTEKS LANJUTAN: jika pesan melanjutkan transaksi sebelumnya tanpa menyebut jenis transaksi baru (mis. "sama es teh 5 ribu" setelah "beli bakso 15 ribu"), catat transaksi lanjutan yang wajar dengan nominal yang disebut. Jangan mencatat ulang transaksi yang sudah ada di konteks.
            4. KOREKSI / PEMBATALAN ("eh bukan 15 ribu, 25 ribu", "yang tadi salah", "batal", "hapus yang tadi"): JANGAN membuat transaksi baru. Kembalikan containsTransaction=false dengan aiReply singkat yang menjelaskan bahwa tidak ada transaksi baru dicatat dan user dapat mengedit pesan sebelumnya.
            5. AMBIGU: jika ada nominal tapi maksud tidak jelas / bukan transaksi yang pasti, JANGAN memaksakan. containsTransaction=false, aiReply meminta klarifikasi.
            6. JANGAN catat sebagai transaksi: pertanyaan keuangan, rencana, pengingat ("ingatkan saya beli bakso"), perintah non-transaksi.
            7. NOMINAL: tulis RUPIAH PENUH tanpa desimal (50000, 2500000, bukan 50 atau 2.5). Jangan sertakan titik ribuan atau satuan ("rb"/"jt") di nilai amount — harus angka murni.
            8. KATEGORI: HANYA dari daftar valid di bawah dan harus sesuai TIPE transaksi (pengeluaran tidak boleh kategori pemasukan, dst). Jangan mengarang kategori.
            9. TANGGAL: jika pesan menyebut waktu transaksi ("kemarin", "kemarin sore", "minggu lalu", "tanggal 12"), isi "date" dengan format YYYY-MM-DD. Jika tidak disebut, biarkan "date": "".
            10. "aiReply" dalam Bahasa Indonesia, ringkas, menyebut jumlah transaksi & nominal total yang dicatat.
            11. JANGAN MENGGABUNGKAN atau melakukan NETTING: satu nominal = SATU objek di array "transactions". Pesan "Gaji lembur 200000 Beli rokok 30000 Makan Malam 45000" = 3 objek (PEMASUKAN 200000, PENGELUARAN 30000, PENGELUARAN 45000). Nominal pemasukan dan pengeluaran TIDAK boleh saling dikurangi atau dijumlah jadi satu. "aiReply" tetap menyebut total SEMUA nominal (tanpa mengurangi).

            KATEGORI VALID:
            ${categoryListForPrompt()}

            Keluarkan jawaban HANYA berupa JSON valid, tanpa teks lain. Contoh untuk banyak transaksi (semua pengeluaran):
            {
              "containsTransaction": true,
              "transactions": [
                { "type": "PENGELUARAN", "category": "Makanan & Minuman", "amount": 15000, "description": "Beli bakso", "date": "" },
                { "type": "PENGELUARAN", "category": "Transportasi", "amount": 30000, "description": "Bensin", "date": "" }
              ],
              "aiReply": "2 transaksi dicatat: Rp 15.000 (Makanan & Minuman) + Rp 30.000 (Transportasi)."
            }

            Contoh campuran pemasukan + pengeluaran (JANGAN digabung):
            {
              "containsTransaction": true,
              "transactions": [
                { "type": "PEMASUKAN", "category": "Gaji & Pemasukan", "amount": 200000, "description": "Gaji lembur", "date": "" },
                { "type": "PENGELUARAN", "category": "Hiburan & Belanja", "amount": 30000, "description": "Beli rokok", "date": "" },
                { "type": "PENGELUARAN", "category": "Makanan & Minuman", "amount": 45000, "description": "Makan malam", "date": "" }
              ],
              "aiReply": "3 transaksi dicatat: +Rp 200.000 (Gaji & Pemasukan) -Rp 30.000 (Hiburan & Belanja) -Rp 45.000 (Makanan & Minuman)."
            }

            Contoh satu transaksi:
            {
              "containsTransaction": true,
              "transactions": [
                { "type": "PEMASUKAN", "category": "Gaji & Pemasukan", "amount": 5000000, "description": "Gajian", "date": "" }
              ],
              "aiReply": "Pemasukan Rp 5.000.000 (Gaji & Pemasukan) dicatat."
            }

            Jika tidak ada transaksi keuangan:
            {
              "containsTransaction": false,
              "aiReply": "Pesan ini tidak dicatat sebagai transaksi."
            }
        """.trimIndent()
    }

    /** Format riwayat obrolan terakhir untuk disisipkan ke prompt AI (M11).
     *  Denga konteks, AI bisa menebak kategori/deskripsi yang konsisten dengan
     *  transaksi sebelumnya. Pesan tanpa teks ditapis. */
    private fun contextBlock(recentContext: List<ChatMessage>): String {
        if (recentContext.isEmpty()) return ""
        return recentContext
            .filter { it.messageText.isNotBlank() }
            .takeLast(6)
            .joinToString("\n") { "${it.sender}: ${it.messageText.take(120)}" }
    }

    private fun callGeminiApi(prompt: String, apiKey: String, imagePath: String? = null): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"

        val contentsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", prompt)
                    })
                    // Foto nota dikirim sebagai inline_data (base64) agar model bisa membacanya.
                    if (imagePath != null) {
                        val b64 = ImageFileUtil.encodeBase64(imagePath)
                        if (b64 != null) {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", b64)
                                })
                            })
                        }
                    }
                })
            })
        }

        val jsonBody = JSONObject().apply {
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
            })
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP Error: ${response.code} - ${response.message}")
            }
            return response.body?.string() ?: ""
        }
    }

    /** Bungkus teks dari OpenAI/OpenRouter agar bisa diparse oleh parseJsonResponse. */
    // internal (bukan private) supaya jalur AI bisa diuji langsung
    // (r1.4.0 — uji akurasi AI: dulu hanya heuristik offline yang punya test).
    internal fun wrapOpenAiText(text: String): String {
        val quoted = JSONObject.quote(text)
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":$quoted}]}}]}"
    }

    private fun extractTextFromGeminiResponse(rawJson: String): String? {
        val root = JSONObject(rawJson)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val firstCand = candidates.getJSONObject(0)
        val content = firstCand.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null
        return parts.getJSONObject(0).optString("text")
    }

    /**
     * Parse respons AI (format baru ARRAY + format lama field tunggal).
     *
     * Format baru (r1.2.4): `transactions: [{type, category, amount, description, date}]`
     * — mendukung multi-transaksi & tanggal eksplisit. Format lama
     * `{type, category, amount, description}` tetap diterima (backward compat).
     * Kategori yang diarang AI dipaksa ke daftar valid (tuning AI: tidak boleh
     * sembarang kategori). Nominal <= 0 dibuang.
     */
    internal fun parseJsonResponse(rawGeminiJson: String, originalText: String, sender: String): AiChatParseResult? {
        val responseText = extractTextFromGeminiResponse(rawGeminiJson) ?: return null
        // Clean JSON formatting
        val cleanedJson = responseText.replace("```json", "").replace("```", "").trim()

        return try {
            val json = JSONObject(cleanedJson)
            val contains = json.optBoolean("containsTransaction", false)
            if (!contains) {
                return AiChatParseResult(
                    containsTransaction = false,
                    aiReply = json.optString("aiReply", "Pesan tercatat dalam obrolan.")
                )
            }

            // 1) Format baru: array "transactions"
            val arr = json.optJSONArray("transactions")
            val txList = mutableListOf<AiTransaction>()
            if (arr != null && arr.length() > 0) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    // r1.4.0: nominal AI bisa berupa Number ATAU string
                    // ("200000", "200.000", "Rp 1,5jt") — jangan dibuang hanya
                    // karena format off (sebelumnya transaksi hilang diam-diam).
                    val amount = parseAiAmount(o.opt("amount")) ?: continue
                    if (amount <= 0) continue
                    val type = if (o.optString("type", "") == Constants.TransactionTypes.INCOME) {
                        Constants.TransactionTypes.INCOME
                    } else {
                        Constants.TransactionTypes.EXPENSE
                    }
                    val category = normalizeCategory(type, o.optString("category", ""))
                    txList += AiTransaction(
                        type = type,
                        category = category,
                        amount = amount,
                        description = o.optString("description", originalText),
                        timestamp = parseDateString(o.optString("date", ""))
                    )
                }
            }
            if (txList.isNotEmpty()) {
                val total = txList.sumOf { it.amount }
                return AiChatParseResult(
                    containsTransaction = true,
                    type = txList.first().type,
                    category = txList.first().category,
                    amount = total,
                    description = txList.first().description,
                    aiReply = json.optString("aiReply").ifBlank { buildMultiAiReply(txList) },
                    detectedBy = "AI",
                    transactions = txList
                )
            }

            // 2) Format lama: field tunggal
            val amount = parseAiAmount(json.opt("amount"))
            if (amount == null || amount <= 0) {
                return AiChatParseResult(
                    containsTransaction = false,
                    aiReply = json.optString("aiReply", "Tidak ada transaksi dicatat.")
                )
            }
            val type = if (json.optString("type", "") == Constants.TransactionTypes.INCOME) {
                Constants.TransactionTypes.INCOME
            } else {
                Constants.TransactionTypes.EXPENSE
            }
            AiChatParseResult(
                containsTransaction = true,
                type = type,
                category = normalizeCategory(type, json.optString("category", "")),
                amount = amount,
                description = json.optString("description", originalText),
                aiReply = json.optString("aiReply", "Pesan telah dicatat sebagai transaksi."),
                // M7: hasil dari AI (Gemini/OpenRouter) — bukan heuristik lokal.
                detectedBy = "AI"
            )
        } catch (e: Exception) {
            Log.w("GeminiService", "Respons AI bukan JSON valid", e)
            null
        }
    }

    /**
     * Paksa kategori hasil AI ke daftar VALID (tuning AI): kategori yang diarang
     * tidak boleh masuk Rekap. Cocokkan persis dulu, lalu case-insensitive;
     * gagal → default sesuai tipe (pengeluaran → Lain-lain, pemasukan →
     * Gaji & Pemasukan) supaya data tidak pernah kategori sampah.
     */
    internal fun normalizeCategory(type: String, raw: String): String {
        val valid = if (type == Constants.TransactionTypes.INCOME) {
            Constants.Categories.INCOME_ALL
        } else {
            Constants.Categories.EXPENSE_ALL
        }
        if (raw.isBlank()) {
            return if (type == Constants.TransactionTypes.INCOME) {
                Constants.Categories.SALARY
            } else {
                Constants.Categories.MISC
            }
        }
        val exact = valid.firstOrNull { it == raw }
        if (exact != null) return exact
        return valid.firstOrNull { it.equals(raw, ignoreCase = true) }
            ?: if (type == Constants.TransactionTypes.INCOME) {
                Constants.Categories.SALARY
            } else {
                Constants.Categories.MISC
            }
    }

    /**
     * Parse nominal dari JSON AI (r1.4.0 — audit Finance AI): menerima Number
     * maupun String dengan format Indonesia ("200000", "200.000", "Rp 1.500.000",
     * "50rb", "2,5jt"). null bila tidak bisa / ≤ 0. Sebelumnya optDouble hanya
     * menerima Number — string membuat transaksi hilang diam-diam.
     */
    internal fun parseAiAmount(value: Any?): Double? {
        if (value == null || value == JSONObject.NULL) return null
        val amount: Double? = when (value) {
            is Number -> value.toDouble()
            is String -> {
                val cleaned = value.trim().replace(Regex("""(?i)rp\.?\s*"""), "").trim()
                if (cleaned.isEmpty()) return null
                // Reuse parser nominal Indonesia (prefix tak-berarti agar
                // ekstraksi angka pertama tetap aman; satuan "rb"/"jt" + spasi
                // sudah ditangani NUMBER_UNIT_PATTERN).
                extractAmountFromText("x $cleaned".lowercase())
            }
            else -> null
        }
        return amount?.takeIf { it > 0 }
    }

    /** Konversi "YYYY-MM-DD" (zona lokal) ke epoch ms. null bila kosong/tidak valid. */
    internal fun parseDateString(date: String): Long? {
        if (date.isBlank()) return null
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.isLenient = false
            sdf.parse(date.trim())?.time
        } catch (e: Exception) {
            null
        }
    }

    /** aiReply default untuk multi-transaksi bila AI tidak mengisinya. */
    private fun buildMultiAiReply(txList: List<AiTransaction>): String {
        if (txList.size == 1) {
            val t = txList.first()
            val label = if (t.type == Constants.TransactionTypes.INCOME) "Pemasukan" else "Pengeluaran"
            return "$label Rp ${formatSuggestionAmount(t.amount)} (${t.category}) dicatat."
        }
        val ringkas = txList.take(3).joinToString(" + ") {
            "Rp ${formatSuggestionAmount(it.amount)} (${it.category})"
        }
        val sisa = txList.size - 3
        return "${txList.size} transaksi dicatat: $ringkas${if (sisa > 0) " + $sisa lainnya" else ""}."
    }

    /** Pola angka + satuan opsional: "50rb", "50.000", "2,5jt", "5 juta", "10k".
     *  `(?![a-z])` mencegah huruf biasa terbaca sebagai satuan — mis. 'k' pada
     *  "2 kopi" bukan satuan ribu. `(?:[.,]\d+)*` menangkap SELURUH grup ribuan
     *  + desimal ("1.500.000", "2,5jt") — bukan cuma satu grup agar nominal
     *  ≥ 1 juta bertitik tidak terpotong (K1). */
    private val NUMBER_UNIT_PATTERN =
        Pattern.compile("(\\d+(?:[.,]\\d+)*)\\s*(?:(rb|ribu|k|jt|juta)(?![a-z]))?")

    /**
     * Ekstrak nominal Rupiah dari teks bebas. Angka BERSATUAN (rb/ribu/k/jt/juta)
     * diprioritaskan atas angka polos karena jauh lebih mungkin nominal transaksi:
     * "beli 2 kopi 20rb" mengambil 20rb (Rp 20.000), bukan 2 (Rp 2.000).
     * Tanpa angka bersatuan, fallback ke angka pertama.
     *
     * L2: angka polos dengan < 2 digit (1–9) TANPA satuan ditolak — mis. "makan
     * 2 kucing" / "beli 3 botol" sebenarnya jumlah item, bukan nominal. Konteks
     * ini mengurangi false-positive heuristik. Hanya nilai ≥ 10 (dianggap "ribuan"
     * lewat toRupiah) atau angka bersatuan yang diterima sebagai nominal.
     */
    /**
     * Deteksi "jam" dari angka HH.MM ("07.30", "14.00", "19.45") — bukan
     * nominal. "07.30" sebelumnya terbaca Rp 730.000 (r1.4.0 — audit Finance AI):
     * tanpa unit, titik dihapus → 0730 → ×1000. Pola: 1-2 digit, titik, PERSIS 2
     * digit (menit 00-59) → jam. "1.500.000" (3 grup) & "15.000" (3 digit di
     * belakang) tidak kena.
     */
    internal fun isClockTime(numStr: String): Boolean {
        if (!numStr.contains('.')) return false
        val parts = numStr.split('.')
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return parts[0].length in 1..2 && parts[1].length == 2 &&
            h in 0..23 && m in 0..59
    }

    /**
     * r1.4.0 (audit Finance AI): angka polos TANPA satuan dengan >= 10 digit
     * = nomor rekening/telepon/ID, BUKAN nominal — "transfer ke rekening
     * 1234567890 sebesar 200rb" tidak boleh jadi transaksi Rp 1,23 miliar.
     * (Nominal Rupiah di chat praktis < 10 digit; nominal besar selalu ditulis
     * dengan satuan "jt"/"M" atau titik ribuan yang jumlah digitnya tetap < 10
     * untuk < 1 miliar.) Dipakai konsisten oleh ekstraksi, pemisahan batas
     * nominal, dan penghitungan jumlah nominal.
     */
    internal fun isImplausiblePlainNumber(numStr: String): Boolean =
        numStr.count { it.isDigit() } >= 10

    internal fun extractAmountFromText(textLower: String): Double? {
        val matcher = NUMBER_UNIT_PATTERN.matcher(textLower)
        var fallbackNum: String? = null
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (!unit.isNullOrEmpty()) return toRupiah(numStr, unit)
            // L2: angka polos 1 digit (0–9) = kuantitas, bukan nominal.
            if (numStr.count { it.isDigit() } < 2) continue
            // r1.4.0: "07.30"/"14.00" = jam, bukan nominal — lewati.
            if (isClockTime(numStr)) continue
            // r1.4.0: angka polos panjang = rekening/telepon, bukan nominal.
            if (isImplausiblePlainNumber(numStr)) continue
            if (fallbackNum == null) fallbackNum = numStr
        }
        val num = fallbackNum ?: return null
        return toRupiah(num, null)
    }

    private fun toRupiah(numStr: String, unit: String?): Double? {
        // Audit r1.2.4: "3.5jt" (TITIK desimal) sebelumnya jadi "35" → 35jt (salah
        // 10x). Bila ada unit & angka memakai TITIK TUNGGAL dengan ≤2 digit di
        // belakangnya, titik itu adalah DESIMAL ("3.5", "1.75"), bukan ribuan.
        // Koma selalu desimal. Titik 3+ digit / titik ganda = pemisah ribuan
        // ("1.500.000", "15.000") — dihapus seperti biasa.
        val isDecimalDot = unit != null &&
            numStr.count { it == '.' } == 1 &&
            numStr.substringAfter('.').length in 1..2 &&
            numStr.substringBefore('.').isNotBlank()
        val normalized = if (isDecimalDot) {
            numStr.replace(",", ".")
        } else {
            numStr.replace(".", "").replace(",", ".")
        }
        val rawNum = normalized.toDoubleOrNull() ?: return null
        return when (unit) {
            "rb", "ribu", "k" -> rawNum * 1000
            "jt", "juta" -> rawNum * 1000000
            else -> if (rawNum in 1.0..999.0) rawNum * 1000 else rawNum
        }
    }

    // ---- r1.2.4 (tuning AI): mesin heuristik offline multi-transaksi ----

    /**
     * Deteksi tanggal dari frasa waktu bahasa Indonesia (tuning AI): "kemarin",
     * "tadi", "minggu lalu", "tanggal N". Return OFFSET ms relatif sekarang
     * (negatif = masa lalu); null bila tidak ada indikasi waktu. Dipakai untuk
     * mengisi timestamp transaksi agar Rekap tidak selalu memakai waktu proses.
     */
    internal fun detectDateOffset(textLower: String): Long? {
        val dayMs = 86_400_000L
        return when {
            // "kemarin lusa" harus dicek DULU — "kemarin lusa" mengandung "kemarin"
            // sehingga cabang "kemarin" akan menang lebih dulu (review r1.2.4).
            textLower.contains("kemarin lusa") -> -2 * dayMs
            textLower.contains("kemarin") || textLower.contains("tadi malam") ||
                textLower.contains("tadi sore") -> -dayMs
            textLower.contains("tadi") -> -2 * 3_600_000L // "tadi pagi/siang" ≈ beberapa jam lalu
            textLower.contains("minggu lalu") || textLower.contains("pekan lalu") -> -7 * dayMs
            textLower.contains("bulan lalu") -> -30 * dayMs
            textLower.contains("tanggal") || textLower.contains("tgl") -> {
                // "tanggal 12" → tanggal 12 bulan ini; BILA sudah lewat (offset positif
                // = masa depan), geser ke bulan SEBELUMNYA supaya timestamp tidak
                // pernah di masa depan (review r1.2.4).
                val m = Regex("""(?:tanggal|tgl)\s*(\d{1,2})""").find(textLower)
                val day = m?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
                if (cal.timeInMillis > now) {
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                cal.timeInMillis - now
            }
            else -> null
        }
    }

    /**
     * Pisahkan teks menjadi segmen kandidat multi-transaksi (r1.4.0 — audit
     * Finance AI: root cause "satu pesan berisi beberapa transaksi tidak
     * terdeteksi").
     *
     * Strategi 1 — separator eksplisit: koma, titik koma, " dan ", " sama ",
     * " atau " (kata utuh). Dipakai bila SETIAP segmen berangka ter-parse
     * ("beli bakso 15rb, bensin 30rb sama rokok 20rb").
     *
     * Strategi 2 — batas nominal: pesan multi-transaksi TANPA separator
     * ("Gaji lembur 200.000 Beli rokok 30.000 Makan Malam 45.000") — setiap
     * nominal menandai AKHIR satu transaksi; teks antar-nominal = deskripsinya.
     *
     * ATURAN AMAN (tuning AI): separator split dipakai HANYA jika tidak ada
     * segmen berangka yang gagal jadi transaksi — mis. "beli sayur dan buah
     * 20rb" maksudnya SATU transaksi; split " dan " memecahnya jadi "beli
     * sayur" (tanpa angka) + "buah 20rb" (angka tanpa trigger). Kasus ini
     * jatuh ke strategi 2 → 1 nominal → parse utuh → 1 transaksi benar.
     * Prinsip: jangan pernah salah catat.
     */
    internal fun splitTransactionSegments(text: String): List<String> {
        // Strategi 1: separator eksplisit.
        val sepSplit = text.trim()
            .split(Regex(""",\s*|;\s*|\s+dan\s+|\s+sama\s+|\s+atau\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sepSplit.size > 1) {
            val allValid = sepSplit.all { seg ->
                val segLower = seg.lowercase()
                val hasNumber = NUMBER_UNIT_PATTERN.matcher(segLower).find()
                !hasNumber || parseSegment(segLower, seg) != null
            }
            if (allValid) {
                // r1.4.0 (stress test): segmen hasil separator BISA masih memuat
                // ≥2 nominal ("bensin 30rb jajan 20rb") — pecah ulang per batas
                // nominal supaya transaksi kedua tidak hilang. Segmen 1 nominal
                // tetap utuh.
                return sepSplit.flatMap { seg ->
                    val sub = splitByAmountBoundaries(seg)
                    if (sub.size > 1) sub else listOf(seg)
                }
            }
        }

        // Strategi 2: batas nominal (multi-transaksi tanpa separator).
        val amountSegs = splitByAmountBoundaries(text)
        if (amountSegs.size > 1) return amountSegs
        return listOf(text.trim())
    }

    /**
     * Pecah teks per batas nominal (r1.4.0): setiap angka yang berpotensi
     * nominal menandai akhir satu segmen. Nominal jam (HH.MM), angka polos
     * 1 digit (kuantitas) dilewati. Kurang dari 2 nominal → utuh (1 segmen).
     */
    internal fun splitByAmountBoundaries(text: String): List<String> {
        val lower = text.lowercase()
        val matches = mutableListOf<Pair<Int, Int>>() // start..end tiap nominal
        val matcher = NUMBER_UNIT_PATTERN.matcher(lower)
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (unit.isNullOrEmpty()) {
                if (numStr.count { it.isDigit() } < 2) continue // kuantitas
                if (isClockTime(numStr)) continue // jam
                if (isImplausiblePlainNumber(numStr)) continue // rekening/telepon
            }
            matches += matcher.start() to matcher.end()
        }
        if (matches.size < 2) return listOf(text.trim())

        val segs = mutableListOf<String>()
        var prevEnd = 0
        matches.forEach { (_, end) ->
            segs += text.substring(prevEnd, end).trim()
            prevEnd = end
        }
        // Teks sisa setelah nominal terakhir tetap milik segmen terakhir.
        if (prevEnd < text.length) {
            val last = segs.removeAt(segs.size - 1)
            segs += (last + " " + text.substring(prevEnd)).trim()
        }
        return segs.filter { it.isNotEmpty() }
    }

    /**
     * Hitung jumlah nominal dalam teks (r1.4.0) — dipakai [shouldHeuristicBackup]:
     * pesan dengan ≥2 nominal adalah kandidat multi-transaksi yang paling rawan
     * salah ditangani AI.
     */
    internal fun countAmounts(text: String): Int {
        val matcher = NUMBER_UNIT_PATTERN.matcher(text.lowercase())
        var count = 0
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (unit.isNullOrEmpty()) {
                if (numStr.count { it.isDigit() } < 2) continue
                if (isClockTime(numStr)) continue
                if (isImplausiblePlainNumber(numStr)) continue
            }
            count++
        }
        return count
    }

    /**
     * Kapan hasil AI yang mengatakan "tidak ada transaksi" perlu diverifikasi
     * ulang heuristik (r1.4.0): hanya untuk pesan dengan ≥2 nominal (paling
     * rawan salah), dan TIDAK untuk pesan koreksi/pembatalan ("eh bukan 15rb,
     * 25rb" — AI sengaja tidak mencatat) atau pertanyaan keuangan.
     */
    internal fun shouldHeuristicBackup(text: String): Boolean {
        val lower = text.lowercase()
        if (listOf("batal", "bukan", "salah", "hapus", "yang tadi", "jangan", "revisi", "eh ", "eh, ").any { lower.contains(it) }) {
            return false
        }
        if (isFinancialQuestion(text)) return false
        if (lower.contains("ingatkan") || lower.contains("reminder") || lower.contains("rencana")) return false
        return countAmounts(text) >= 2
    }

    /**
     * Parse SATU segmen menjadi transaksi (inti logika heuristik lama, di-refactor
     * agar bisa dipanggil per segmen untuk multi-transaksi). Return null bila
     * segmen tidak memuat transaksi yang jelas. [segText] asli dipakai untuk
     * deskripsi; [textLower] versi lowercase untuk pencocokan kata kunci.
     */
    private fun parseSegment(textLower: String, segText: String): AiTransaction? {
        val amount = extractAmountFromText(textLower)
        if (amount == null || amount <= 0) return null

        val isIncome = listOf(
            "terima gaji", "dapat gaji", "menerima gaji", "gaji masuk", "gaji cair", "cair gaji", "gaji",
            "terima bonus", "dapat bonus", "menerima bonus", "bonus masuk", "bonus cair", "cair bonus",
            "terima komisi", "dapat komisi", "menerima komisi", "komisi masuk", "komisi cair", "cair komisi",
            "bonus", // r1.2.4: mandiri (paritas dgn "gaji"/"dividen") — "gaji 5jt dan bonus 2jt"
            "terima dividen", "dapat dividen", "menerima dividen", "dividen masuk", "dividen cair", "cair dividen", "dividen",
            "terima arisan", "dapat arisan", "menerima arisan", "arisan masuk", "arisan cair", "cair arisan", "menang arisan",
            "terima rejeki", "dapat rejeki", "menerima rejeki", "rejeki nomplok", "rejeki",
            "terima uang", "dapat uang", "menerima uang", "uang masuk", "uang jajan masuk",
            "terima hadiah", "dapat hadiah", "menerima hadiah", "menang undian", "dapat undian", "undian",
            "hasil jualan", "hasil dagang", "hasil usaha", "omzet", "omset", "penjualan",
            "jualan", // r1.4.0 (stress test): "jualan online 300rb" = pemasukan usaha
            "laku", "terjual", "dapat hasil", "terima hasil",
            "cashback", "refund", "pengembalian dana", "uang kembali",
            "terima thr", "dapat thr", "thr masuk", "thr cair",
            "terima insentif", "dapat insentif", "insentif masuk",
            "terima tips", "dapat tips", "menerima tips",
            "bunga bank", "bunga deposito", "cair deposito", "kupon obligasi",
            "transfer masuk", "pemasukan", "pencairan", "bagi hasil", "warisan", "hibah"
        ).any { textLower.contains(it) }

        // r1.2.4 (review): "bonus/komisi/tips/insentif/thr/gaji" mandiri bisa
        // berarti MENGELUARKAN ("bagi bonus 500rb", "kasih tips 20rb", "bayar
        // gaji 5jt", "potong gaji") — blokir deteksi income bila ada verba
        // pengeluaran yang menempel. "bagi hasil" (investasi) TIDAK kena karena
        // "hasil" bukan kata yang diblokir. Audit r1.2.4: "bayar gaji" sebelumnya
        // salah tercatat PEMASUKAN karena "gaji" mandiri menang.
        val incomeBlocker = Regex(
            "(bagi|kasih|setor|kirim|beri|bayar|potong|kurang)\\s+(bonus|komisi|tips|insentif|thr|gaji)"
        ).containsMatchIn(textLower)

        val isExpenseTrigger = (
            textLower.contains("beli") || textLower.contains("bayar") ||
                textLower.contains("pengeluaran") || textLower.contains("habis") ||
                textLower.contains("belanja") || textLower.contains("ongkir") ||
                textLower.contains("sewa") || textLower.contains("pulsa") ||
                textLower.contains("listrik") || textLower.contains("air") ||
                textLower.contains("popok") || textLower.contains("susu") ||
                textLower.contains("makan") || textLower.contains("transaksi") ||
                // r1.4.0 (stress test): kata Indonesia umum yang selama ini lolos
                // dari heuristik ("kopi 15 ribu", "jajan 20rb", "renovasi 75jt",
                // "upgrade ram 0,5jt") — tanpa trigger, transaksi nyata hilang.
                // r1.4.0 (audit campuran income+expense): "uang keluar 3jt" /
                // "keluar 3jt" = frasa pengeluaran umum yang selama ini LOLOS
                // (hanya income "uang masuk" yang terekam → transaksi hilang).
                // "uang keluar" dicek dulu supaya tidak tertangkap "uang masuk".
                textLower.contains("uang keluar") || textLower.contains("keluar") ||
                textLower.contains("kopi") || textLower.contains("jajan") ||
                textLower.contains("renovasi") || textLower.contains("upgrade") ||
                textLower.contains("bensin") || textLower.contains("taxi") ||
                textLower.contains("ojek") || textLower.contains("grab") ||
                textLower.contains("gojek") || textLower.contains("tol") ||
                textLower.contains("parkir") || textLower.contains("isi") ||
                textLower.contains("cicilan") || textLower.contains("kredit") ||
                textLower.contains("angsuran") || textLower.contains("hutang") ||
                textLower.contains("utang") || textLower.contains("pinjaman") ||
                textLower.contains("spp") || textLower.contains("kuliah") ||
                textLower.contains("les") || textLower.contains("kursus") ||
                textLower.contains("sedekah") || textLower.contains("zakat") ||
                textLower.contains("infaq") || textLower.contains("infak") ||
                textLower.contains("donasi") || textLower.contains("sumbangan") ||
                textLower.contains("asuransi") || textLower.contains("premi") ||
                textLower.contains("pajak") || textLower.contains("stnk") ||
                textLower.contains("bpjs") || textLower.contains("topup") || textLower.contains("top up") ||
                textLower.contains("rokok") || textLower.contains("tembakau") // r1.2.4: barang konsumsi
            )

        if (isIncome && !incomeBlocker) {
            val category = when {
                textLower.contains("jual") || textLower.contains("dagang") || textLower.contains("omzet") ||
                    textLower.contains("omset") || textLower.contains("orderan") || textLower.contains("usaha") ||
                    textLower.contains("laku") || textLower.contains("terjual") || textLower.contains("penjualan") ->
                    Constants.Categories.BUSINESS
                textLower.contains("dividen") || textLower.contains("bunga") || textLower.contains("bagi hasil") ||
                    textLower.contains("saham") || textLower.contains("reksadana") || textLower.contains("investasi") ||
                    textLower.contains("deposito") || textLower.contains("capital gain") ->
                    Constants.Categories.INVESTMENT
                textLower.contains("arisan") || textLower.contains("hadiah") || textLower.contains("undian") ||
                    textLower.contains("rejeki") || textLower.contains("warisan") || textLower.contains("hibah") ->
                    Constants.Categories.GIFT
                textLower.contains("cashback") || textLower.contains("refund") ||
                    textLower.contains("pengembalian") || textLower.contains("uang kembali") ->
                    Constants.Categories.CASHBACK
                textLower.contains("bonus") || textLower.contains("komisi") ||
                    textLower.contains("thr") || textLower.contains("insentif") || textLower.contains("tips") ->
                    Constants.Categories.BONUS
                else -> Constants.Categories.SALARY
            }
            return AiTransaction(
                type = Constants.TransactionTypes.INCOME,
                category = category,
                amount = amount,
                // r1.4.0: deskripsi tanpa nominal ("Gaji lembur 200.000" →
                // "Gaji lembur") — konsisten dengan hasil AI & Rekap bersih.
                description = cleanSuggestionDescription(segText),
                timestamp = nowPlus(detectDateOffset(textLower))
            )
        }

        if (isExpenseTrigger) {
            val category = when {
                textLower.contains("beras") || textLower.contains("minyak") || textLower.contains("sayur") || textLower.contains("sembako") || textLower.contains("pasar") || textLower.contains("supermarket") || textLower.contains("market") -> "Groceries & Sembako"
                textLower.contains("makan") || textLower.contains("minum") || textLower.contains("kopi") || textLower.contains("jajan") || textLower.contains("bakso") || textLower.contains("snack") || textLower.contains("nasi") -> "Makanan & Minuman"
                textLower.contains("listrik") || textLower.contains("air") || textLower.contains("wifi") || textLower.contains("pulsa") || textLower.contains("kontrakan") || textLower.contains("token") -> "Tagihan & Utilitas"
                textLower.contains("spp") || textLower.contains("kuliah") ||
                    (textLower.contains("les") && !textLower.contains("lesehan")) ||
                    textLower.contains("kursus") || textLower.contains("bimbel") || textLower.contains("uang gedung") ||
                    textLower.contains("ujian") || textLower.contains("pendidikan") -> "Pendidikan"
                textLower.contains("popok") || textLower.contains("susu") || textLower.contains("sekolah") || textLower.contains("mainan") || textLower.contains("anak") -> "Kebutuhan Anak"
                textLower.contains("bensin") || textLower.contains("ojek") || textLower.contains("grab") || textLower.contains("gojek") || textLower.contains("tol") || textLower.contains("parkir") || textLower.contains("taxi") -> "Transportasi"
                textLower.contains("skincare") || textLower.contains("obat") || textLower.contains("dokter") || textLower.contains("sabun") || textLower.contains("shampoo") -> "Kesehatan & Skincare"
                textLower.contains("baju") || textLower.contains("sepatu") || textLower.contains("nonton") || textLower.contains("tas") || textLower.contains("shopee") || textLower.contains("tokped") || textLower.contains("belanja") ||
                    textLower.contains("rokok") || textLower.contains("tembakau") -> "Hiburan & Belanja"
                textLower.contains("cicilan") || textLower.contains("kredit") || textLower.contains("angsuran") ||
                    textLower.contains("kpr") || textLower.contains("kkb") || textLower.contains("hutang") ||
                    textLower.contains("utang") || textLower.contains("pinjaman") || textLower.contains("nyicil") -> "Cicilan & Pinjaman"
                textLower.contains("sedekah") || textLower.contains("zakat") || textLower.contains("infaq") ||
                    textLower.contains("infak") || textLower.contains("donasi") || textLower.contains("sumbangan") ||
                    textLower.contains("amal") || textLower.contains("kotak amal") -> "Sosial & Donasi"
                textLower.contains("asuransi") || (textLower.contains("premi") && !textLower.contains("premium")) ||
                    textLower.contains("pajak") ||
                    textLower.contains("stnk") || textLower.contains("pbb") || textLower.contains("bpjs") ||
                    textLower.contains("retribusi") -> "Asuransi & Pajak"
                textLower.contains("renovasi") || textLower.contains("upgrade") ||
                    textLower.contains("perbaikan") -> "Lain-lain"
                else -> "Lain-lain"
            }
            return AiTransaction(
                type = Constants.TransactionTypes.EXPENSE,
                category = category,
                amount = amount,
                // r1.4.0: deskripsi tanpa nominal (lihat cabang income).
                description = cleanSuggestionDescription(segText),
                timestamp = nowPlus(detectDateOffset(textLower))
            )
        }

        return null
    }

    /** now + offset (null → now). */
    private fun nowPlus(offset: Long?): Long =
        System.currentTimeMillis() + (offset ?: 0L)

    /**
     * Deteksi PERTANYAAN KEUANGAN di chat (tuning AI r1.2.4) — pesan yang
     * bukan transaksi tapi menanyakan kondisi keuangan ("hari ini sudah keluar
     * berapa?") dijawab berbasis data DB, bukan dibiarkan "tercatat saja".
     * Gate ketat: harus ada kata tanya nominal (berapa/total/saldo) ATAU tanda
     * tanya, DAN kata kunci finansial — supaya "besok makan dimana?" tidak
     * memicu jawaban data.
     */
    internal fun isFinancialQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.length < 4 || lower.length > 150) return false
        val moneyQuestion = listOf(
            "berapa", "total", "saldo", "berapa banyak", "berapa sisa", "habis berapa", "sisa uang"
        ).any { lower.contains(it) }
        // "pengeluaran terbesar bulan ini apa" tanpa tanda tanya — kata tanya umum
        // juga dihitung ("apa", "mana", "kapan"). Tetap butuh kata finansial.
        val generalQuestion = listOf(" apa", " mana", " kapan", " berapa").any { lower.contains(it) }
        val hasQuestionMark = lower.contains("?")
        val financial = listOf(
            "uang", "keluar", "masuk", "pengeluaran", "pemasukan", "saldo", "transaksi",
            "rekap", "belanja", "beli", "bayar", "gaji", "arisan", "bensin", "listrik",
            "pulsa", "tabungan", "nabung", "cicilan", "utang", "hutang", "anggaran", "budget"
        ).any { lower.contains(it) }
        return (moneyQuestion || hasQuestionMark || generalQuestion) && financial
    }

    internal fun offlineHeuristicParse(messageText: String, sender: String): AiChatParseResult {
        // Guard false-positive (tuning AI): reminder/rencana BUKAN transaksi —
        // "ingatkan saya beli bakso 15rb" tidak boleh tercatat.
        val textLower = messageText.lowercase()
        if (textLower.contains("ingatkan") || textLower.contains("reminder") ||
            textLower.contains("tolong catat nanti") || textLower.contains("rencana beli")
        ) {
            return AiChatParseResult(
                containsTransaction = false,
                aiReply = "Pesan ini terlihat sebagai pengingat/rencana, jadi tidak dicatat sebagai transaksi."
            )
        }

        val segments = splitTransactionSegments(messageText)
        val transactions = segments.mapNotNull { seg ->
            parseSegment(seg.lowercase(), seg)
        }
        if (transactions.isEmpty()) {
            return AiChatParseResult(
                containsTransaction = false,
                aiReply = "Tercatat dalam ruang obrolan Nyachat."
            )
        }

        // Field tunggal = ringkasan (pertama + total) untuk kompatibilitas UI.
        val first = transactions.first()
        val total = transactions.sumOf { it.amount }
        val reply = if (transactions.size == 1) {
            val label = if (first.type == Constants.TransactionTypes.INCOME) "PEMASUKAN" else "Pengeluaran"
            "$label Rp ${first.amount.toLong()} (${first.category}: ${first.description}) dicatat oleh $sender."
        } else {
            val ringkas = transactions.take(3).joinToString(" + ") {
                "${it.category} Rp ${it.amount.toLong()}"
            }
            "${transactions.size} transaksi dicatat oleh $sender: $ringkas."
        }
        return AiChatParseResult(
            containsTransaction = true,
            type = first.type,
            category = first.category,
            amount = total,
            description = first.description,
            aiReply = reply,
            detectedBy = "HEURISTIK",
            transactions = transactions
        )
    }
}
