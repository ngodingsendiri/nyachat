package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction

/**
 * Lapisan AI — hasil dekomposisi `FinanceRepository` (P3-1).
 *
 * Sebelumnya repository memanggil `GeminiService` langsung sehingga mencampur
 * tiga tanggung jawab: persisten lokal, sinkronisasi cloud, dan AI. Dengan
 * layanan ini, repository cukup bergantung pada satu dependency AI yang bisa
 * di-mock di unit test (produksi tetap memakai Gemini/OpenRouter BYOK lewat
 * [GeminiService]).
 */
open class FinanceAiService {

    /** Parse pesan chat → transaksi (teks biasa / foto nota). */
    open suspend fun parseMessage(
        messageText: String,
        sender: String,
        recentContext: List<ChatMessage>,
        imagePath: String? = null
    ): AiChatParseResult =
        GeminiService.parseChatMessage(messageText, sender, recentContext, imagePath)

    /** Jawaban AI bebas untuk tombol ✨ Tanya AI (bukan parser transaksi). */
    open suspend fun askInChat(prompt: String): String =
        GeminiService.askAiChat(prompt)

    /** Saran prompt cepat berdasarkan riwayat transaksi. */
    open suspend fun frequentSuggestions(transactions: List<FinancialTransaction>): List<String> =
        GeminiService.generateFrequentTransactionSuggestions(transactions)

    /** Laporan audit keuangan. */
    open suspend fun auditReport(
        transactions: List<FinancialTransaction>,
        income: Double,
        expense: Double
    ): String =
        GeminiService.generateFinancialAuditReport(transactions, income, expense)

    /** Analisis bulanan (rekap per bulan + tren + rekomendasi). */
    open suspend fun monthlyAnalysis(transactions: List<FinancialTransaction>): String =
        GeminiService.generateMonthlyAnalysisReport(transactions)

    /** True kalau setidaknya satu jalur AI tersedia (gate L6 — bukan panggilan AI). */
    open fun isAiAvailable(): Boolean = GeminiService.isAiAvailable()

    /** Deteksi pertanyaan keuangan di chat (gate murni — bukan panggilan AI). */
    open fun isFinancialQuestion(text: String): Boolean = GeminiService.isFinancialQuestion(text)

    /** Deteksi balasan fallback offline (marker tunggal — satu sumber kebenaran). */
    open fun isOfflineFallbackReply(text: String): Boolean = GeminiService.isOfflineFallbackReply(text)
}
