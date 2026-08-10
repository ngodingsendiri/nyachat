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

data class AiChatParseResult(
    val containsTransaction: Boolean,
    val type: String? = null, // "PENGELUARAN" or "PEMASUKAN"
    val category: String? = null,
    val amount: Double? = null,
    val description: String? = null,
    val aiReply: String,
    // M7: asal deteksi — "AI" (Gemini/OpenRouter) atau "HEURISTIK" (fallback
    // offline). Disimpan di ChatMessage.detectedBy untuk indikator badge UI.
    val detectedBy: String? = null
)

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
            null
        }

        // 3) Fallback: teks biasa pakai mesin offline; foto nota tanpa AI hanya tersimpan
        //    (tidak bisa dibaca tanpa kunci AI vision). Juga dipakai saat habis waktu.
        return@withContext aiParsed ?: offlineHeuristicParse(messageText, sender)
    }


    /** L6: true kalau setidaknya satu jalur AI tersedia (OpenRouter atau Gemini BYOK). */
    fun isAiAvailable(): Boolean {
        val key = getApiKey()
        return OpenRouterService.activeApiKey() != null ||
            key.isNotBlank() && key != "MY_GEMINI_API_KEY"
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
            null
        }
        if (aiSuggestions != null) return@withContext aiSuggestions

        // 3) Fallback offline heuristic (L6): deskripsi dibersihkan dari angka
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
            null
        }
        if (aiReport != null) return@withContext aiReport

        // 3) Offline Fallback Report — berbasis data nyata (bukan template kaku).
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
            null
        }
        if (aiReport != null) return@withContext aiReport

        // 3) Laporan offline (tanpa internet / tanpa key / habis waktu) — tetap informatif.
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
            null
        }

        // 3) Balasan offline (tanpa internet / tanpa key / habis waktu)
        return@withContext aiReply ?: offlineChatReply(prompt)
    }

    private fun offlineChatReply(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("hemat") || lower.contains("nabung") || lower.contains("tabung") ->
                "Tips hemat: (1) catat dulu semua pengeluaran kecil, (2) tetapkan plafon mingguan untuk makan & transportasi, (3) sisihkan 10–15% pemasukan ke dana cadangan di awal bulan, (4) pisahkan uang rutin per pos (amplop digital). Mau kubantu susun anggaran mingguanmu? 😊"
            lower.contains("gaji") || lower.contains("pemasukan") ->
                "Untuk mengatur gaji: alokasikan ±50% untuk kebutuhan pokok, 30% tabungan/cadangan, dan 20% keinginan. Mulai dengan mencatat semua transaksi lewat obrolan ini — nanti aku rekap & evaluasi otomatis. 💰"
            lower.contains("hutang") || lower.contains("utang") ->
                "Untuk melunasi utang: pilih metode snowball (lunasi yang terkecil dulu biar semangat) atau avalanche (lunasi yang bunganya terbesar dulu biar lebih hemat). Sisihkan minimal 20% pemasukan untuk cicilan. 💪"
            else ->
                "Aku adalah asisten keuangan Nyachat. Aku bisa mencatat transaksi dari obrolan, memberi rekap & analisis pengeluaran, serta tips keuangan. Saat ini mode AI sedang offline — sambungkan kunci OpenRouter/Gemini di menu Pengaturan agar aku bisa menjawab lebih pintar! 😊"
        }
    }

    /** Prompt khusus untuk foto nota/bukti belanja — AI diminta membaca isi foto
     *  lalu mengeluarkan JSON transaksi yang sama dengan parser teks. */
    private fun buildReceiptPrompt(messageText: String, sender: String, recentContext: List<ChatMessage>): String {
        val contextBlock = contextBlock(recentContext)
        return """
            Kamu adalah 'Asisten Nyachat' yang bertugas membaca FOTO NOTA / BUKTI BELANJA / STRUK dari $sender.
            
            Konteks obrolan terbaru (untuk mencocokkan kategori/deskripsi yang konsisten):
            ${contextBlock.ifEmpty { "— (riwayat kosong)" }}
            
            Foto yang kamu terima adalah nota belanja. Analisis foto tersebut dan catat TOTAL pengeluarannya.
            Keterangan tambahan dari pengirim: "$messageText"
            
            PILIHAN KATEGORI VALID:
            - Groceries & Sembako
            - Makanan & Minuman
            - Tagihan & Utilitas
            - Kebutuhan Anak
            - Transportasi
            - Kesehatan & Skincare
            - Hiburan & Belanja
            - Lain-lain
            - Gaji & Pemasukan
            
            Keluarkan jawaban HANYA berupa JSON valid dalam format persis seperti ini:
            {
              "containsTransaction": true,
              "type": "PENGELUARAN",
              "category": "Groceries & Sembako",
              "amount": 150000,
              "description": "Nota belanja [nama toko di nota]",
              "aiReply": "Nota belanja dicatat: Rp 150.000 (Groceries & Sembako)."
            }
            
            Jika foto bukan nota / tidak terbaca dengan jelas, kirimkan:
            {
              "containsTransaction": false,
              "aiReply": "Foto tersimpan, tapi tidak bisa kubaca sebagai nota. Coba foto ulang dengan cahaya cukup & seluruh nota terlihat."
            }
        """.trimIndent()
    }

    private fun buildParsePrompt(messageText: String, sender: String, recentContext: List<ChatMessage>): String {
        val contextBlock = contextBlock(recentContext)
        return """
            Kamu adalah 'Asisten Nyachat' yang bertugas memantau obrolan transaksi finansial pada grup, lembaga, atau rumah tangga.
            
            Konteks obrolan terbaru (untuk mencocokkan kategori/deskripsi yang konsisten):
            ${contextBlock.ifEmpty { "— (riwayat kosong)" }}
            
            Pesan masuk dari $sender: "$messageText"
            
            Analisis apakah pesan di atas mengandung catatan transaksi, pengeluaran, iuran, tagihan, atau pemasukan dana.
            
            PILIHAN KATEGORI VALID:
            - Groceries & Sembako
            - Makanan & Minuman
            - Tagihan & Utilitas
            - Kebutuhan Anak
            - Transportasi
            - Kesehatan & Skincare
            - Hiburan & Belanja
            - Lain-lain
            - Gaji & Pemasukan
            
            Keluarkan jawaban HANYA berupa JSON valid dalam format persis seperti ini:
            {
              "containsTransaction": true,
              "type": "PENGELUARAN" atau "PEMASUKAN",
              "category": "Makanan & Minuman",
              "amount": 50000,
              "description": "Beli nasi padang",
              "aiReply": "Transaksi 'Beli nasi padang' sebesar Rp 50.000 telah dicatat otomatis."
            }
            
            Jika tidak mengandung transaksi keuangan, kirimkan:
            {
              "containsTransaction": false,
              "aiReply": "Catatan pesan tersimpan dalam ruang obrolan."
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
    private fun wrapOpenAiText(text: String): String {
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

    private fun parseJsonResponse(rawGeminiJson: String, originalText: String, sender: String): AiChatParseResult? {
        val responseText = extractTextFromGeminiResponse(rawGeminiJson) ?: return null
        // Clean JSON formatting
        val cleanedJson = responseText.replace("```json", "").replace("```", "").trim()
        
        return try {
            val json = JSONObject(cleanedJson)
            val contains = json.optBoolean("containsTransaction", false)
            if (contains) {
                AiChatParseResult(
                    containsTransaction = true,
                    type = json.optString("type", Constants.TransactionTypes.EXPENSE),
                    category = json.optString("category", "Lain-lain"),
                    amount = json.optDouble("amount", 0.0),
                    description = json.optString("description", originalText),
                    aiReply = json.optString("aiReply", "Pesan telah dicatat sebagai transaksi."),
                    // M7: hasil dari AI (Gemini/OpenRouter) — bukan heuristik lokal.
                    detectedBy = "AI"
                )
            } else {
                AiChatParseResult(
                    containsTransaction = false,
                    aiReply = json.optString("aiReply", "Pesan tercatat dalam obrolan.")
                )
            }
        } catch (e: Exception) {
            Log.w("GeminiService", "Respons AI bukan JSON valid", e)
            null
        }
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
    internal fun extractAmountFromText(textLower: String): Double? {
        val matcher = NUMBER_UNIT_PATTERN.matcher(textLower)
        var fallbackNum: String? = null
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (!unit.isNullOrEmpty()) return toRupiah(numStr, unit)
            // L2: angka polos 1 digit (0–9) = kuantitas, bukan nominal.
            if (numStr.count { it.isDigit() } < 2) continue
            if (fallbackNum == null) fallbackNum = numStr
        }
        val num = fallbackNum ?: return null
        return toRupiah(num, null)
    }

    private fun toRupiah(numStr: String, unit: String?): Double? {
        // Normalisasi: hapus SEMUA titik ribuan (pemisah ribuan), koma jadi desimal kalau valid
        val normalized = numStr
            .replace(".", "")                    // hapus semua titik ribuan
            .replace(",", ".")                   // koma jadi desimal
        val rawNum = normalized.toDoubleOrNull() ?: return null
        return when (unit) {
            "rb", "ribu", "k" -> rawNum * 1000
            "jt", "juta" -> rawNum * 1000000
            else -> if (rawNum in 1.0..999.0) rawNum * 1000 else rawNum
        }
    }

    internal fun offlineHeuristicParse(messageText: String, sender: String): AiChatParseResult {
        val textLower = messageText.lowercase()

        // Amount detection: angka bersatuan (rb/jt/k) dimenangkan atas angka polos
        // pertama — lihat extractAmountFromText.
        val amount = extractAmountFromText(textLower)

        val isIncome = textLower.contains("gaji") || textLower.contains("pemasukan") ||
                textLower.contains("transfer masuk") || textLower.contains("dapat bonus") ||
                textLower.contains("dapat komisi") || textLower.contains("uang jajan masuk")

        val isExpenseTrigger = amount != null && (
                textLower.contains("beli") || textLower.contains("bayar") ||
                textLower.contains("pengeluaran") || textLower.contains("habis") ||
                textLower.contains("belanja") || textLower.contains("ongkir") ||
                textLower.contains("sewa") || textLower.contains("pulsa") ||
                textLower.contains("listrik") || textLower.contains("air") ||
                textLower.contains("popok") || textLower.contains("susu") ||
                textLower.contains("makan") || textLower.contains("transaksi") ||
                textLower.contains("bensin") || textLower.contains("taxi") ||
                textLower.contains("ojek") || textLower.contains("grab") ||
                textLower.contains("gojek") || textLower.contains("tol") ||
                textLower.contains("parkir") || textLower.contains("isi")
        )

        if (isIncome && amount != null && amount > 0) {
            return AiChatParseResult(
                containsTransaction = true,
                type = Constants.TransactionTypes.INCOME,
                category = "Gaji & Pemasukan",
                amount = amount,
                description = messageText,
                // M7: dihasilkan mesin aturan lokal (fallback offline) — bukan AI.
                detectedBy = "HEURISTIK",
                aiReply = "Mantap! Aku catat PEMASUKAN sebesar Rp ${amount.toLong()} (${messageText}). Saldo bertambah! 💰"
            )
        } else if (isExpenseTrigger && amount > 0) {
            val category = when {
                textLower.contains("beras") || textLower.contains("minyak") || textLower.contains("sayur") || textLower.contains("sembako") || textLower.contains("pasar") || textLower.contains("supermarket") || textLower.contains("market") -> "Groceries & Sembako"
                textLower.contains("makan") || textLower.contains("minum") || textLower.contains("kopi") || textLower.contains("bakso") || textLower.contains("snack") || textLower.contains("nasi") -> "Makanan & Minuman"
                textLower.contains("listrik") || textLower.contains("air") || textLower.contains("wifi") || textLower.contains("pulsa") || textLower.contains("kontrakan") || textLower.contains("pbb") -> "Tagihan & Utilitas"
                textLower.contains("popok") || textLower.contains("susu") || textLower.contains("sekolah") || textLower.contains("mainan") || textLower.contains("anak") -> "Kebutuhan Anak"
                textLower.contains("bensin") || textLower.contains("ojek") || textLower.contains("grab") || textLower.contains("gojek") || textLower.contains("tol") || textLower.contains("parkir") || textLower.contains("taxi") -> "Transportasi"
                textLower.contains("skincare") || textLower.contains("obat") || textLower.contains("dokter") || textLower.contains("sabun") || textLower.contains("shampoo") -> "Kesehatan & Skincare"
                textLower.contains("baju") || textLower.contains("sepatu") || textLower.contains("nonton") || textLower.contains("tas") || textLower.contains("shopee") || textLower.contains("tokped") || textLower.contains("belanja") -> "Hiburan & Belanja"
                else -> "Lain-lain"
            }

            return AiChatParseResult(
                containsTransaction = true,
                type = Constants.TransactionTypes.EXPENSE,
                category = category,
                amount = amount,
                description = messageText,
                // M7: hasil mesin heuristik offline.gv
                detectedBy = "HEURISTIK",
                aiReply = "Pengeluaran Rp ${amount.toLong()} ($category: $messageText) dicatat oleh $sender."
            )
        }

        return AiChatParseResult(
            containsTransaction = false,
            aiReply = "Tercatat dalam ruang obrolan Nyachat."
        )
    }
}
