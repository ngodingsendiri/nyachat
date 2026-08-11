package com.startupmini.nyachat.data.analytics

import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.FinancialTransaction
import java.util.Calendar

/**
 * Detail keuangan terpersonalisasi yang diturunkan murni dari data transaksi
 * nyata (audit #7). Dipakai dua kali:
 *  - sebagai konteks tambahan di prompt AI (agar rekomendasi menyebut pos &
 *    angka konkret milik user),
 *  - sebagai isi laporan OFFLINE-fallback (agar meskipun tanpa AI, keluaran
 *    tetap berbasis data, bukan template kaku).
 *
 * Semua fungsi murni — mudah di-unit-test.
 */
data class FinancialInsights(
    /** Total pemasukan (seluruh riwayat). */
    val totalIncome: Double = 0.0,
    /** Total pengeluaran (seluruh riwayat). */
    val totalExpense: Double = 0.0,
    /** Perubahan pengeluaran (%) segmen 30 hari berjalan vs 30 hari sebelumnya. */
    val expenseChangePct: Double = 0.0,
    /** Kategori pengeluaran terbesar. */
    val topExpenseCategory: String? = null,
    /** Nominal kategori pengeluaran terbesar. */
    val topExpenseAmount: Double = 0.0,
    /** Porsi kategori terbesar terhadap total pengeluaran (0..1). */
    val topExpensePct: Double = 0.0,
    /** Deskripsi pengeluaran tunggal terbesar. */
    val biggestSingleDesc: String? = null,
    /** Nominal pengeluaran tunggal terbesar. */
    val biggestSingleAmount: Double = 0.0,
    /** Nama pengeluaran terbesar (riwayat). */
    val topSpender: String? = null,
    /** Nominal pengeluaran terbesar. */
    val topSpenderAmount: Double = 0.0,
    /** Jumlah transaksi terdata. */
    val transactionCount: Int = 0
) {
    /** Tingkat tabungan (0..1): cash kiri dibagi pemasukan; negatif bila defisit. */
    val savingsRate: Double
        get() = if (totalIncome > 0) (totalIncome - totalExpense) / totalIncome else 0.0

    /** Rasio pengeluaran terhadap pemasukan (0..1+); 0 bila belum ada pemasukan. */
    val expenseRate: Double
        get() = if (totalIncome > 0) totalExpense / totalIncome else 0.0
}

object FinancialInsightsEngine {

    /** Hitung insight dari daftar transaksi. [nowMillis] parameterizable (uji). */
    fun compute(
        transactions: List<FinancialTransaction>,
        nowMillis: Long = System.currentTimeMillis()
    ): FinancialInsights {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        cal.add(Calendar.DAY_OF_MONTH, -30)
        val windowStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, -30)
        val prevStart = cal.timeInMillis

        val expenses = transactions.filter { it.type == Constants.TransactionTypes.EXPENSE }
        val income = transactions.filter { it.type == Constants.TransactionTypes.INCOME }
            .sumOf { it.amount }
        val expense = expenses.sumOf { it.amount }

        // Pengeluaran segmen 30 hari berjalan vs 30 hari sebelumnya.
        val current = expenses.filter { it.timestamp >= windowStart }.sumOf { it.amount }
        val previous = expenses.filter { it.timestamp in prevStart until windowStart }.sumOf { it.amount }
        val changePct = if (previous > 0) (current - previous) / previous else 0.0

        // Kategori pengeluaran terbesar.
        val byCat = expenses.groupBy { it.category }
        val topCatEntry = byCat.entries.maxByOrNull { it.value.sumOf { t -> t.amount } }
        val topCategory = topCatEntry?.key
        val topCategoryTotal = topCatEntry?.value?.sumOf { it.amount } ?: 0.0
        val topCatPct = if (expense > 0) topCategoryTotal / expense else 0.0

        // Pengeluaran tunggal terbesar.
        val biggest = expenses.maxByOrNull { it.amount }

        // Pengeluaran terbesar per pengguna.
        val bySpender = expenses.groupBy { it.loggedBy }
        val topSpenderEntry = bySpender.entries.maxByOrNull { it.value.sumOf { t -> t.amount } }

        return FinancialInsights(
            totalIncome = income,
            totalExpense = expense,
            expenseChangePct = changePct,
            topExpenseCategory = topCategory,
            topExpenseAmount = topCategoryTotal,
            topExpensePct = topCatPct,
            biggestSingleDesc = biggest?.description,
            biggestSingleAmount = biggest?.amount ?: 0.0,
            topSpender = topSpenderEntry?.key,
            topSpenderAmount = topSpenderEntry?.value?.sumOf { it.amount } ?: 0.0,
            transactionCount = transactions.size
        )
    }

    /** Baris ringkas untuk disisipkan ke prompt AI. */
    fun describeForPrompt(ins: FinancialInsights): String = buildString {
        appendLine("- Kategori pengeluaran terbesar: ${ins.topExpenseCategory ?: "tidak ada"} (Rp ${ins.topExpenseAmount.toLong()}, ${(ins.topExpensePct * 100).toInt()}% dari total).")
        appendLine("- Pengeluaran tunggal terbesar: ${ins.biggestSingleDesc ?: "-"} (Rp ${ins.biggestSingleAmount.toLong()}).")
        if (ins.topSpender != null) {
            appendLine("- Pengeluaran terbesar: ${ins.topSpender} (Rp ${ins.topSpenderAmount.toLong()}).")
        }
        appendLine("- Perubahan pengeluaran 30 hari terakhir vs sebelumnya: ${trendText(ins.expenseChangePct)}.")
        appendLine("- Jumlah transaksi terdata: ${ins.transactionCount}.")
    }

    /** Label tren +/- %. */
    fun trendText(pct: Double): String = when {
        pct > 0.001 -> "naik ${(pct * 100).toInt()}%"
        pct < -0.001 -> "turun ${(Math.abs(pct) * 100).toInt()}%"
        else -> "stagnan (0%)"
    }
}