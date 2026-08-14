package com.startupmini.nyachat.data.analytics

import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.FinancialTransaction
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint-4: unit test insight mingguan/bulanan. Waktu "sekarang" di-inject
 * (Rabu, 15 Juli 2026) supaya hasil deterministik di zona waktu mana pun.
 */
class WeeklyInsightsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    /** Rabu, 15 Juli 2026, 12:00. */
    private val now = at(2026, 7, 15)

    private fun expense(amount: Double, timestamp: Long, category: String = Constants.Categories.FOOD, desc: String = "belanja") =
        FinancialTransaction(
            type = Constants.TransactionTypes.EXPENSE,
            category = category,
            amount = amount,
            description = desc,
            loggedBy = "Suami",
            timestamp = timestamp
        )

    @Test
    fun formatRupiahMemakaiPemisahTitikDeterministik() {
        assertEquals("Rp 1.234.567", WeeklyInsights.formatRupiah(1_234_567.0))
        assertEquals("Rp 500", WeeklyInsights.formatRupiah(500.0))
        assertEquals("Rp 0", WeeklyInsights.formatRupiah(0.0))
        assertEquals("Rp 25.000", WeeklyInsights.formatRupiah(25_000.99)) // desimal dibuang
    }

    @Test
    fun weekStartOfMengembalikanSeninNolNol() {
        val monday = WeeklyInsights.weekStartOf(now) // Rabu 15 Juli → Senin 13 Juli
        val cal = Calendar.getInstance().apply { timeInMillis = monday }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(at(2026, 7, 13, 0), monday)
    }

    @Test
    fun weekStartOfHariSeninAdalahHariItuSendiri() {
        assertEquals(at(2026, 7, 13, 0), WeeklyInsights.weekStartOf(at(2026, 7, 13, 7)))
    }

    @Test
    fun tanpaTransaksiTidakAdaInsight() {
        assertTrue(WeeklyInsights.generateInsights(emptyList(), now).isEmpty())
    }

    @Test
    fun skenarioLengkapMenghasilkanLimaInsight() {
        val txs = listOf(
            FinancialTransaction( // pemasukan awal bulan
                type = Constants.TransactionTypes.INCOME,
                category = Constants.Categories.SALARY,
                amount = 5_000_000.0, description = "gaji", loggedBy = "Istri",
                timestamp = at(2026, 7, 1)
            ),
            expense(200_000.0, at(2026, 7, 14), desc = "nasi padang"),        // minggu ini
            expense(50_000.0, at(2026, 7, 15), category = Constants.Categories.TRANSPORT), // minggu ini
            expense(100_000.0, at(2026, 7, 8))                                // minggu lalu
        )
        val insights = WeeklyInsights.generateInsights(txs, now)

        assertEquals(5, insights.size)
        // Minggu ini 250rb vs minggu lalu 100rb → naik 150%.
        assertTrue(insights[0].contains("naik 150%"))
        // Tabungan (5jt - 350rb, termasuk pengeluaran 8 Juli) / 5jt = 93%.
        assertTrue(insights[1].contains("93%"))
        assertTrue(insights[1].contains("Rp 5.000.000"))
        // Kategori terboros = Makanan (200rb + 100rb = 300rb dari 350rb = 86%).
        assertTrue(insights[2].contains(Constants.Categories.FOOD))
        assertTrue(insights[2].contains("86%"))
        // Pengeluaran terbesar.
        assertTrue(insights[3].contains("nasi padang"))
        assertTrue(insights[3].contains("Rp 200.000"))
        // Rata-rata harian = 350rb / 15 hari = 23.333.
        assertTrue(insights[4].contains("Rp 23.333"))
    }

    @Test
    fun tanpaPemasukanTidakAdaInsightRasioTabungan() {
        val txs = listOf(expense(50_000.0, at(2026, 7, 14)))
        val insights = WeeklyInsights.generateInsights(txs, now)
        assertTrue(insights.none { it.startsWith("Rasio tabungan") })
    }

    @Test
    fun pengeluaranTurunMemberiInsightPositif() {
        val txs = listOf(
            expense(50_000.0, at(2026, 7, 14)),  // minggu ini
            expense(100_000.0, at(2026, 7, 8))   // minggu lalu
        )
        val insights = WeeklyInsights.generateInsights(txs, now)
        assertTrue(insights.first().contains("turun 50%"))
    }

    // Audit UI/UX Rekap: persentase ekstrem (baseline kecil) membingungkan —
    // "naik 4739%" diganti kata sederhana supaya tidak terkesan bug.
    @Test
    fun persentaseEkstremDipakaiKataSederhana() {
        val txs = listOf(
            expense(41_305_500.0, at(2026, 7, 14)), // minggu ini (besar)
            expense(850_000.0, at(2026, 7, 8))      // minggu lalu (kecil)
        )
        val insights = WeeklyInsights.generateInsights(txs, now)
        assertTrue(insights.first().contains("naik tajam"))
        // Guard regresi: tidak boleh ada persentase ekstrem yang membingungkan.
        assertTrue(!insights.first().contains("%"))
    }

}
