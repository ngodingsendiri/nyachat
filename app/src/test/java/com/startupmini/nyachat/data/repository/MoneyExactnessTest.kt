package com.startupmini.nyachat.data.repository

import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * INVARIANT UANG (audit 2026-08-14): nominal disimpan sebagai Double di Room &
 * Firestore. Aman untuk rupiah bilangan bulat < 2^53 (±9 kuadriliun) karena
 * semua sumber nominal (parse AI/heuristik) menghasilkan integer — TAPI invariant
 * ini harus di-pin supaya fitur masa depan (persen, pecahan, pembagian, kalkulasi
 * bulanan) tidak diam-diam memasukkan drift floating-point ke data keuangan.
 *
 * Kalau salah satu test ini gagal → ada jalur yang menghasilkan pecahan/drift —
 * segera selidiki sebelum menambah fitur numerik apa pun.
 */
class MoneyExactnessTest {

    // 1. Penjumlahan nominal besar tidak boleh menghasilkan drift kumulatif.
    @Test
    fun `penjumlahan seribu transaksi besar tetap eksak`() {
        val amounts = List(1000) { 123_456_789.0 }
        val sum = amounts.sum()
        // 123.456.789.000 — persis (bukan 1.23456789E11 dengan sisa epsilon).
        assertEquals(123_456_789_000.0, sum, 0.0)
        assertEquals(1000L * 123_456_789L, sum.roundToLong())
    }

    // 2. Round-trip JSON (format backup DataExporter) untuk nominal integer eksak.
    @Test
    fun `round-trip JSON backup tidak mengubah nominal integer`() {
        val amounts = listOf(
            1_000_000.0,          // 1jt
            2_075_000.0,          // campuran multi-transaksi
            18_611_000.0,         // saldo rekap
            999_999_999.0,        // mendekati miliar
            5_000_000_000_000.0   // 5 triliun (uji batas atas kewajaran)
        )
        for (a in amounts) {
            val back = JSONObject().put("amount", a).getDouble("amount")
            assertEquals(a, back, 0.0)
        }
    }

    // 3. normalizeAmount — asuransi di batas persist: rupiah tak punya sen,
    //    pecahan apa pun harus di-snap ke rupiah penuh (bukan dibiarkan masuk DB).
    @Test
    fun `normalizeAmount membulatkan pecahan ke rupiah penuh`() {
        assertEquals(2_000_000.0, normalizeAmount(2_000_000.0000001), 0.0)
        assertEquals(50_000.0, normalizeAmount(50_000.0), 0.0)
        assertEquals(1_500_001.0, normalizeAmount(1_500_000.5), 0.0)
        // Integer besar tidak berubah sama sekali (no-op untuk data valid).
        assertEquals(123_456_789.0, normalizeAmount(123_456_789.0), 0.0)
    }

    // 4. Badge multi-transaksi: total dari 500 transaksi tetap eksak di pesan.
    @Test
    fun `rebuildBadge menjumlahkan total secara eksak`() {
        val txs = (1..500).map { i ->
            FinancialTransaction(
                id = i.toLong(),
                type = "PEMASUKAN",
                category = "Gaji & Pemasukan",
                amount = 10_000_000.0,
                description = "tx $i",
                loggedBy = "Ari",
                timestamp = 0L,
                chatMessageId = 1L,
                cloudId = "c$i"
            )
        }
        val message = ChatMessage(
            id = 1L,
            sender = "Ari",
            messageText = "gaji masuk 500x",
            cloudId = "msg-1"
        )
        val rebuilt = message.rebuildBadge(txs)

        assertTrue(rebuilt.isFinancial)
        assertEquals(5_000_000_000.0, rebuilt.detectedAmount!!, 0.0)
        assertEquals(500, rebuilt.detectedCount)
        // Semua pemasukan → bukan campuran.
        assertEquals(false, rebuilt.hasMixedTypes)
    }
}
