package com.startupmini.nyachat.data.analytics

import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.FinancialTransaction
import java.util.Calendar
import kotlin.math.roundToInt

/** Rekap satu minggu (Senin–Minggu) untuk analisis mingguan. */
data class WeeklySummary(
    val weekStart: Long, // epoch millis Senin 00:00 waktu lokal
    val income: Double,
    val expense: Double
) {
    val balance: Double get() = income - expense
    /** Rasio tabungan: sisa pemasukan setelah pengeluaran (0 bila tanpa pemasukan). */
    val savingsRate: Double get() = if (income > 0) balance / income else 0.0
}

/**
 * Insight otomatis mingguan/bulanan (Sprint-4) — fitur diferensiasi: user tidak
 * perlu minta AI untuk mendapat gambaran keuangannya. Semua perhitungan murni
 * (tanpa Android/network/AI) sehingga deterministik dan mudah di-unit-test;
 * waktu "sekarang" di-inject supaya hasil test stabil.
 */
object WeeklyInsights {

    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    /** Awal hari Senin (00:00 lokal) dari minggu yang memuat [timestamp]. */
    fun weekStartOf(timestamp: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Mundur ke Senin — independen dari firstDayOfWeek bawaan locale.
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return cal.timeInMillis
    }

    /** Kelompokkan transaksi per minggu, urut minggu terbaru dulu. */
    fun groupByWeek(transactions: List<FinancialTransaction>): List<WeeklySummary> {
        return transactions
            .groupBy { weekStartOf(it.timestamp) }
            .map { (weekStart, list) ->
                WeeklySummary(
                    weekStart = weekStart,
                    income = list.filter { it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount },
                    expense = list.filter { it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }
                )
            }
            .sortedByDescending { it.weekStart }
    }

    /**
     * Deret insight singkat (maks 5) untuk kartu "Insight Otomatis" di Rekap.
     * [now] di-inject supaya deterministik di unit test.
     */
    fun generateInsights(
        transactions: List<FinancialTransaction>,
        now: Long = System.currentTimeMillis()
    ): List<String> {
        if (transactions.isEmpty()) return emptyList()
        val insights = mutableListOf<String>()

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val monthStart = startOfMonth(now)
        val monthEnd = Calendar.getInstance().apply {
            timeInMillis = monthStart
            add(Calendar.MONTH, 1)
        }.timeInMillis
        val monthTxs = transactions.filter { it.timestamp in monthStart until monthEnd }

        // 1) Perbandingan pengeluaran minggu ini vs minggu lalu.
        val thisWeekStart = weekStartOf(now)
        val lastWeekStart = Calendar.getInstance().apply {
            timeInMillis = thisWeekStart
            add(Calendar.DAY_OF_YEAR, -7)
        }.timeInMillis
        val thisWeekExpense = expenseBetween(transactions, thisWeekStart, thisWeekStart + 7 * MS_PER_DAY)
        val lastWeekExpense = expenseBetween(transactions, lastWeekStart, lastWeekStart + 7 * MS_PER_DAY)
        if (thisWeekExpense > 0 && lastWeekExpense > 0) {
            val pct = percentChange(thisWeekExpense, lastWeekExpense)
            // Audit UI/UX Rekap: kenaikan ekstrem (baseline minggu lalu kecil,
            // mis. "naik 4739%") membingungkan dan terkesan bug — pakai kata
            // sederhana. Penurunan tidak perlu di-cap: dengan nominal positif,
            // persentase turun selalu di rentang (-100%, 0).
            insights += when {
                pct >= 1000 -> "Pengeluaran minggu ini ${formatRupiah(thisWeekExpense)}, naik tajam dibanding minggu lalu."
                pct > 0 -> "Pengeluaran minggu ini ${formatRupiah(thisWeekExpense)}, naik $pct% dibanding minggu lalu."
                pct < 0 -> "Pengeluaran minggu ini ${formatRupiah(thisWeekExpense)}, turun ${-pct}% dibanding minggu lalu. Pertahankan!"
                else -> "Pengeluaran minggu ini ${formatRupiah(thisWeekExpense)}, sama dengan minggu lalu."
            }
        }

        val monthIncome = monthTxs.filter { it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }
        val monthExpense = monthTxs.filter { it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }

        // 2) Rasio tabungan bulan berjalan.
        if (monthIncome > 0) {
            val rate = ((monthIncome - monthExpense) / monthIncome * 100).roundToInt()
            insights += "Rasio tabungan bulan ini $rate% dari pemasukan ${formatRupiah(monthIncome)}."
        }

        // 3) Kategori pengeluaran terbesar bulan berjalan.
        val byCategory = monthTxs
            .filter { it.type == Constants.TransactionTypes.EXPENSE }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
        val top = byCategory.maxByOrNull { it.value }
        if (top != null && monthExpense > 0) {
            val share = (top.value / monthExpense * 100).roundToInt()
            insights += "Kategori terboros bulan ini: ${top.key} (${formatRupiah(top.value)}, $share% dari pengeluaran)."
        }

        // 4) Pengeluaran tunggal terbesar bulan berjalan.
        val largest = monthTxs
            .filter { it.type == Constants.TransactionTypes.EXPENSE }
            .maxByOrNull { it.amount }
        if (largest != null) {
            insights += "Pengeluaran terbesar bulan ini: ${largest.description} (${formatRupiah(largest.amount)})."
        }

        // 5) Rata-rata pengeluaran harian bulan berjalan.
        if (monthExpense > 0) {
            insights += "Rata-rata pengeluaran harian bulan ini ${formatRupiah(monthExpense / dayOfMonth)}."
        }

        return insights.take(5)
    }

    /** Persentase perubahan (baru vs lama), dibulatkan ke integer terdekat. */
    private fun percentChange(current: Double, previous: Double): Int =
        ((current - previous) / previous * 100).roundToInt()

    private fun startOfMonth(timestamp: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun expenseBetween(
        transactions: List<FinancialTransaction>,
        startInclusive: Long,
        endExclusive: Long
    ): Double = transactions
        .filter { it.type == Constants.TransactionTypes.EXPENSE && it.timestamp in startInclusive until endExclusive }
        .sumOf { it.amount }

    /** Format Rupiah dengan pemisah ribuan titik — deterministik (tanpa locale). */
    internal fun formatRupiah(amount: Double): String {
        val digits = amount.toLong().toString()
        val grouped = StringBuilder()
        var count = 0
        for (i in digits.length - 1 downTo 0) {
            grouped.insert(0, digits[i])
            count++
            if (count % 3 == 0 && i > 0) grouped.insert(0, '.')
        }
        return "Rp $grouped"
    }
}
