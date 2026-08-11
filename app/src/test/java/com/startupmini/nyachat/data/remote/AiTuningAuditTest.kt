package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit & tuning AI r1.2.4 — mesin heuristik offline (fallback tanpa key AI).
 * Memverifikasi hasil perbaikan pipeline:
 * - multi-transaksi (1 pesan → N transaksi)
 * - koreksi/pembatalan tidak menciptakan transaksi keliru
 * - reminder/rencana tidak tercatat
 * - tanggal eksplisit ("kemarin", "minggu lalu") dipakai
 * - kategori hasil AI dipaksa ke daftar valid
 * - pertanyaan keuangan terdeteksi (untuk dijawab berbasis data DB)
 */
class AiTuningAuditTest {

    // ---- 4. MULTI-TRANSAKSI ----

    @Test
    fun multiTransaksiPengeluaranTigaItem() {
        // Sebelum tuning: hanya 1 transaksi yang tercatat (sisanya hilang).
        val r = GeminiService.offlineHeuristicParse(
            "beli bakso 15 ribu, bensin 30 ribu sama rokok 20 ribu", "Suami"
        )
        assertTrue(r.containsTransaction)
        assertEquals(3, r.transactions.size)
        assertEquals(65000.0, r.amount!!, 0.001) // total badge
        assertEquals("Makanan & Minuman", r.transactions[0].category)
        assertEquals("Transportasi", r.transactions[1].category)
        assertEquals(15000.0, r.transactions[0].amount, 0.001)
        assertEquals(30000.0, r.transactions[1].amount, 0.001)
        assertEquals(20000.0, r.transactions[2].amount, 0.001)
    }

    @Test
    fun multiTransaksiPemasukanDuaItem() {
        val r = GeminiService.offlineHeuristicParse("gaji 5 juta dan bonus 2 juta", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals(2, r.transactions.size)
        assertEquals("PEMASUKAN", r.transactions[0].type)
        assertEquals("PEMASUKAN", r.transactions[1].type)
        assertEquals(5000000.0, r.transactions[0].amount, 0.001)
        assertEquals(2000000.0, r.transactions[1].amount, 0.001)
        assertEquals(7000000.0, r.amount!!, 0.001)
    }

    @Test
    fun multiTransaksiCampuranIncomeExpense() {
        val r = GeminiService.offlineHeuristicParse(
            "dapat arisan 50jt, bayar listrik 250rb", "Istri"
        )
        assertTrue(r.containsTransaction)
        assertEquals(2, r.transactions.size)
        assertEquals("PEMASUKAN", r.transactions[0].type)
        assertEquals("Hadiah & Arisan", r.transactions[0].category)
        assertEquals("PENGELUARAN", r.transactions[1].type)
        assertEquals("Tagihan & Utilitas", r.transactions[1].category)
    }

    @Test
    fun splitTidakMemecahTransaksiTunggalDenganDan() {
        // "beli sayur dan buah 20rb" = SATU transaksi. Split " dan " berbahaya —
        // aturan aman harus fallback ke parse utuh, bukan menghasilkan 0 transaksi.
        val r = GeminiService.offlineHeuristicParse("beli sayur dan buah 20rb", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals(1, r.transactions.size)
        assertEquals(20000.0, r.transactions[0].amount, 0.001)
        assertEquals("Groceries & Sembako", r.transactions[0].category)
    }

    @Test
    fun splitTransactionSegmentsVerifikasi() {
        assertEquals(2, GeminiService.splitTransactionSegments("beli bakso 15rb, bensin 30rb").size)
        // "dan" yang memisahkan dua transaksi lengkap → 2 segmen valid.
        assertEquals(2, GeminiService.splitTransactionSegments("gaji 5jt dan bonus 2jt").size)
        // "dan" dalam satu transaksi → fallback ke 1 segmen (parse utuh).
        assertEquals(1, GeminiService.splitTransactionSegments("beli sayur dan buah 20rb").size)
    }

    // ---- 7. KOREKSI & PEMBATALAN ----

    @Test
    fun koreksiNominalTanpaVerbaTidakMencatat() {
        // "eh bukan 15 ribu, 25 ribu" — tanpa verba beli/bayar, heuristik TIDAK
        // boleh mencatat transaksi baru yang keliru.
        val r = GeminiService.offlineHeuristicParse("eh bukan 15 ribu, 25 ribu", "Suami")
        assertFalse(r.containsTransaction)
    }

    @Test
    fun pembatalanTidakMencatat() {
        val r = GeminiService.offlineHeuristicParse("batal", "Suami")
        assertFalse(r.containsTransaction)
        val r2 = GeminiService.offlineHeuristicParse("yang tadi salah, hapus", "Istri")
        assertFalse(r2.containsTransaction)
    }

    // ---- 8. NON-TRANSAKSI & REMINDER ----

    @Test
    fun reminderTidakMencatat() {
        // Sebelum tuning: "ingatkan saya beli bakso 15rb" tercatat sebagai transaksi.
        val r = GeminiService.offlineHeuristicParse("ingatkan saya beli bakso 15rb", "Suami")
        assertFalse(r.containsTransaction)
    }

    @Test
    fun pertanyaanBiasaBukanTransaksi() {
        assertFalse(GeminiService.offlineHeuristicParse("halo", "Suami").containsTransaction)
        assertFalse(GeminiService.offlineHeuristicParse("lagi apa?", "Istri").containsTransaction)
        assertFalse(GeminiService.offlineHeuristicParse("besok makan dimana?", "Suami").containsTransaction)
    }

    // ---- 2. NOMINAL ----

    @Test
    fun nominalBerbagaiFormat() {
        assertEquals(15000.0, GeminiService.extractAmountFromText("beli bakso 15.000")!!, 0.001)
        assertEquals(15000.0, GeminiService.extractAmountFromText("beli bakso 15 ribu")!!, 0.001)
        assertEquals(15000.0, GeminiService.extractAmountFromText("beli bakso 15k")!!, 0.001)
        assertEquals(15000.0, GeminiService.extractAmountFromText("beli bakso 15rb")!!, 0.001)
        assertEquals(1500000.0, GeminiService.extractAmountFromText("dapat gaji 1,5 juta")!!, 0.001)
        assertEquals(1500000.0, GeminiService.extractAmountFromText("dapat gaji 1.500.000")!!, 0.001)
        assertEquals(2000000.0, GeminiService.extractAmountFromText("dapat gaji 2jt")!!, 0.001)
        assertEquals(2500000.0, GeminiService.extractAmountFromText("dapat gaji 2,5jt")!!, 0.001)
        assertEquals(250000.0, GeminiService.extractAmountFromText("bayar listrik 250rb")!!, 0.001)
    }

    // ---- 6. TANGGAL ----

    @Test
    fun tanggalKemarinDipakaiTimestamp() {
        val r = GeminiService.offlineHeuristicParse("bayar listrik kemarin 250rb", "Suami")
        assertTrue(r.containsTransaction)
        val expected = System.currentTimeMillis() - 86_400_000L
        val t = r.transactions.first().timestamp!!
        assertTrue("timestamp harus ~kemarin (${t} vs ${expected})", kotlin.math.abs(t - expected) < 60_000)
    }

    @Test
    fun tanggalMingguLaluDipakaiTimestamp() {
        val r = GeminiService.offlineHeuristicParse("bayar listrik minggu lalu 250rb", "Suami")
        assertTrue(r.containsTransaction)
        val expected = System.currentTimeMillis() - 7 * 86_400_000L
        val t = r.transactions.first().timestamp!!
        assertTrue(kotlin.math.abs(t - expected) < 60_000)
    }

    @Test
    fun tanpaKataWaktuTimestampSekarang() {
        val r = GeminiService.offlineHeuristicParse("bayar listrik 250rb", "Suami")
        assertTrue(r.containsTransaction)
        val t = r.transactions.first().timestamp!!
        assertTrue(kotlin.math.abs(t - System.currentTimeMillis()) < 60_000)
    }

    @Test
    fun detectDateOffsetBerbagaiFrasa() {
        assertEquals(-86_400_000L, GeminiService.detectDateOffset("beli kemarin sore"))
        assertEquals(-7 * 86_400_000L, GeminiService.detectDateOffset("minggu lalu"))
        assertNull(GeminiService.detectDateOffset("beli bakso 15rb"))
    }

    @Test
    fun kemarinLusaDuaHariSebelum() {
        // Review r1.2.4: "kemarin lusa" mengandung "kemarin" — harus -2 hari,
        // bukan -1 hari.
        assertEquals(-2 * 86_400_000L, GeminiService.detectDateOffset("bayar kemarin lusa"))
    }

    @Test
    fun tanggalMasaDepanDipakaiBulanLalu() {
        // Review r1.2.4: hari ini tanggal 5, user bilang "tanggal 20" → offset
        // tidak boleh POSITIF (masa depan); harus mundur ke bulan sebelumnya.
        val offset = GeminiService.detectDateOffset("bayar tagihan tanggal 20")
        assertNotNull(offset)
        assertTrue("offset harus negatif (masa lalu), dapat: $offset", offset!! < 0)
    }

    @Test
    fun bagiBonusTidakDihitungPemasukan() {
        // Review r1.2.4: "bonus" mandiri bisa berarti MENGELUARKAN — "bagi bonus"
        // tidak boleh tercatat sebagai PEMASUKAN.
        val r = GeminiService.offlineHeuristicParse("bagi bonus 500rb", "Suami")
        assertFalse(
            "bagi bonus tidak boleh jadi pemasukan: ${r.transactions}",
            r.transactions.any { it.type == "PEMASUKAN" }
        )
    }

    @Test
    fun bagiHasilTetapPemasukanInvestasi() {
        // "bagi hasil" (investasi) TIDAK boleh terblokir incomeBlocker.
        val r = GeminiService.offlineHeuristicParse("bagi hasil investasi 1jt", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PEMASUKAN", r.transactions.first().type)
        assertEquals("Investasi & Dividen", r.transactions.first().category)
    }

    // ---- Audit r1.2.4: bug yang ditemukan saat audit mendalam ----

    @Test
    fun bayarGajiAdalahPengeluaran() {
        // Audit: "bayar gaji karyawan 5jt" sebelumnya salah PEMASUKAN (karena
        // "gaji" mandiri menang) — sekarang harus PENGELUARAN.
        val r = GeminiService.offlineHeuristicParse("bayar gaji karyawan 5jt", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PENGELUARAN", r.transactions.first().type)
    }

    @Test
    fun potongGajiTidakJadiPemasukan() {
        // "potong gaji" tidak punya verba pengeluaran yang jelas → tidak boleh
        // tercatat PEMASUKAN (hasil null = aman, tidak salah catat).
        val r = GeminiService.offlineHeuristicParse("potong gaji 500rb", "Suami")
        assertFalse(r.containsTransaction)
    }

    @Test
    fun terimaGajiTetapPemasukan() {
        // Regresi: verba menerima TIDAK boleh terblokir.
        val r = GeminiService.offlineHeuristicParse("terima gaji 5jt", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PEMASUKAN", r.transactions.first().type)
    }

    @Test
    fun titikDesimalDenganUnitDibacaDesimal() {
        // Audit: "3.5jt" (titik desimal) sebelumnya jadi 35jt (salah 10x).
        assertEquals(3_500_000.0, GeminiService.extractAmountFromText("terima dividen 3.5jt")!!, 0.001)
        assertEquals(1_500_000.0, GeminiService.extractAmountFromText("gaji 1.5jt")!!, 0.001)
    }

    @Test
    fun titikRibuanTetapRibuan() {
        // Regresi: titik ribuan tetap dihapus — "1.500.000" & "15.000" tidak berubah.
        assertEquals(1_500_000.0, GeminiService.extractAmountFromText("gaji 1.500.000")!!, 0.001)
        assertEquals(15_000.0, GeminiService.extractAmountFromText("beli bakso 15.000")!!, 0.001)
        assertEquals(2_500_000.0, GeminiService.extractAmountFromText("transfer 2,5jt")!!, 0.001)
    }

    // ---- 5. KATEGORI VALID ----

    @Test
    fun kategoriValidDipakaiPersis() {
        assertEquals(
            "Makanan & Minuman",
            GeminiService.normalizeCategory(Constants.TransactionTypes.EXPENSE, "Makanan & Minuman")
        )
    }

    @Test
    fun kategoriCaseInsensitiveDiterima() {
        assertEquals(
            "Makanan & Minuman",
            GeminiService.normalizeCategory(Constants.TransactionTypes.EXPENSE, "makanan & minuman")
        )
    }

    @Test
    fun kategoriDiarangAIJatuhKeDefault() {
        // AI mengarang "Gadget & Elektronik" → tidak ada di daftar → Lain-lain.
        assertEquals(
            "Lain-lain",
            GeminiService.normalizeCategory(Constants.TransactionTypes.EXPENSE, "Gadget & Elektronik")
        )
        // Pemasukan dengan kategori pengeluaran → dipaksa Gaji & Pemasukan.
        assertEquals(
            "Gaji & Pemasukan",
            GeminiService.normalizeCategory(Constants.TransactionTypes.INCOME, "Makanan & Minuman")
        )
    }

    @Test
    fun kategoriKosongJatuhKeDefault() {
        assertEquals(
            "Lain-lain",
            GeminiService.normalizeCategory(Constants.TransactionTypes.EXPENSE, "")
        )
        assertEquals(
            "Gaji & Pemasukan",
            GeminiService.normalizeCategory(Constants.TransactionTypes.INCOME, "")
        )
    }

    @Test
    fun parseDateStringValidDanInvalid() {
        assertNotNull(GeminiService.parseDateString("2026-08-10"))
        assertNull(GeminiService.parseDateString(""))
        assertNull(GeminiService.parseDateString("bukan-tanggal"))
    }

    // ---- 9. PERTANYAAN KEUANGAN ----

    @Test
    fun deteksiPertanyaanKeuangan() {
        assertTrue(GeminiService.isFinancialQuestion("hari ini sudah keluar berapa?"))
        assertTrue(GeminiService.isFinancialQuestion("berapa total pemasukan bulan ini?"))
        assertTrue(GeminiService.isFinancialQuestion("pengeluaran terbesar bulan ini apa"))
        assertTrue(GeminiService.isFinancialQuestion("tadi saya beli apa saja?"))
        assertTrue(GeminiService.isFinancialQuestion("sisa uang kita berapa"))
        assertTrue(GeminiService.isFinancialQuestion("berapa total belanja kemarin"))
    }

    @Test
    fun nonPertanyaanTidakTerdeteksi() {
        assertFalse(GeminiService.isFinancialQuestion("halo"))
        assertFalse(GeminiService.isFinancialQuestion("besok makan dimana?"))
        assertFalse(GeminiService.isFinancialQuestion("hari ini kita ke mana?"))
        assertFalse(GeminiService.isFinancialQuestion("kapan pulang?"))
    }

    // ---- BACKWARD COMPAT: format tunggal tetap berfungsi ----

    @Test
    fun formatTunggalTetapBerfungsi() {
        val r = GeminiService.offlineHeuristicParse("beli bakso 15000", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PENGELUARAN", r.type)
        assertEquals(15000.0, r.amount!!, 0.001)
        assertEquals(1, r.transactions.size)
    }

    @Test
    fun allMenyediakanListDariFormatLama() {
        val r = GeminiService.offlineHeuristicParse("gaji 5 juta", "Suami")
        assertEquals(1, r.all.size)
        assertEquals("PEMASUKAN", r.all[0].type)
        assertEquals(5000000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun allKosongSaatBukanTransaksi() {
        val r = GeminiService.offlineHeuristicParse("halo", "Suami")
        assertFalse(r.containsTransaction)
        assertTrue(r.all.isEmpty())
    }
}
