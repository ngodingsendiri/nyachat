package com.startupmini.nyachat.data.analytics

import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.FinancialTransaction
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit #7: test insight keuangan terpersonalisasi yang menjadi dasar prompt AI
 * DAN laporan offline — supaya beralih dari template kaku ke data nyata terbukti.
 * Waktu "sekarang" di-inject (mid-2026) agar deterministik.
 */
class FinancialInsightsTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private val now = at(2026, 7, 15)

    private fun tx(
        type: String,
        amount: Double,
        category: String,
        loggedBy: String,
        timestamp: Long,
        desc: String = "belanja"
    ) = FinancialTransaction(
        type = type,
        category = category,
        amount = amount,
        description = desc,
        loggedBy = loggedBy,
        timestamp = timestamp
    )

    @Test
    fun posTerbesarDanPengeluaranTunggalTerbesarTerhitung() {
        // 30 hari berjalan vs sebelumnya dibedakan biar changePct teruji.
        val last30 = now - 10L * 24 * 3600 * 1000
        val prev30 = now - 45L * 24 * 3600 * 1000
        val txs = listOf(
            tx(Constants.TransactionTypes.EXPENSE, 200_000.0, Constants.Categories.FOOD, "Suami", last30, "makan siang"),
            tx(Constants.TransactionTypes.EXPENSE, 400_000.0, Constants.Categories.TRANSPORT, "Suami", last30, "ganti ban"),
            tx(Constants.TransactionTypes.EXPENSE, 50_000.0, Constants.Categories.FOOD, "Istri", prev30, "kopi"),
            tx(Constants.TransactionTypes.INCOME, 1_000_000.0, Constants.Categories.SALARY, "Suami", last30, "gaji")
        )
        val ins = FinancialInsightsEngine.compute(txs, now)

        assertEquals(1_000_000.0, ins.totalIncome, 0.01)
        assertEquals(650_000.0, ins.totalExpense, 0.01)
        // Transport (400k) > Food (250k) → kategori terbesar Transport.
        assertEquals(Constants.Categories.TRANSPORT, ins.topExpenseCategory)
        assertEquals(400_000.0, ins.topExpenseAmount, 0.01)
        // Porsi 400k / 650k.
        assertEquals(400_000.0 / 650_000.0, ins.topExpensePct, 0.01)
        // Pengeluaran tunggal terbesar.
        assertEquals("ganti ban", ins.biggestSingleDesc)
        assertEquals(400_000.0, ins.biggestSingleAmount, 0.01)
        // Berdasarkan data di atas, jumlah keseluruhan terbesar = Suami (200+400k).
        assertEquals("Suami", ins.topSpender)
        assertEquals(600_000.0, ins.topSpenderAmount, 0.01)
        assertEquals(4, ins.transactionCount)
    }

    @Test
    fun changePctNaikBilaPengeluaranTerakhirLebihBesar() {
        // Segmen 30 hari berjalan: 800k; sebelumnya: 200k → naik 300%.
        val prev30 = now - 45L * 24 * 3600 * 1000
        val last30 = now - 10L * 24 * 3600 * 1000
        val txs = listOf(
            tx(Constants.TransactionTypes.EXPENSE, 800_000.0, Constants.Categories.FOOD, "Suami", last30),
            tx(Constants.TransactionTypes.EXPENSE, 200_000.0, Constants.Categories.FOOD, "Suami", prev30)
        )
        val ins = FinancialInsightsEngine.compute(txs, now)
        assertEquals(3.0, ins.expenseChangePct, 0.01)
        assertEquals("naik 300%", FinancialInsightsEngine.trendText(ins.expenseChangePct))
    }

    @Test
    fun tanpaDataTotolSemuaNolDanSpars() {
        val ins = FinancialInsightsEngine.compute(emptyList(), now)
        assertEquals(0.0, ins.totalIncome, 0.01)
        assertEquals(0.0, ins.totalExpense, 0.01)
        assertNull(ins.topExpenseCategory)
        assertEquals(0.0, ins.savingsRate, 0.01)
        assertEquals(0.0, ins.expenseRate, 0.01)
    }

    @Test
    fun savingsRateMenghitungSisaKas() {
        val txs = listOf(
            tx(Constants.TransactionTypes.INCOME, 1_000_000.0, Constants.Categories.SALARY, "Suami", now),
            tx(Constants.TransactionTypes.EXPENSE, 700_000.0, Constants.Categories.FOOD, "Suami", now)
        )
        val ins = FinancialInsightsEngine.compute(txs, now)
        assertEquals(0.3, ins.savingsRate, 0.01)
        assertEquals(0.7, ins.expenseRate, 0.01)
        // describeForPrompt memuat pos terbesar (data nyata, bukan template).
        assertTrue(FinancialInsightsEngine.describeForPrompt(ins).contains(Constants.Categories.FOOD))
    }
}