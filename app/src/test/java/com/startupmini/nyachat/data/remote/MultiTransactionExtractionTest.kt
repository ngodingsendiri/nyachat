package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit Finance AI r1.4.0 — regression test ekstraksi transaksi multi-pesan.
 *
 * ROOT CAUSE yang diperbaiki: `splitTransactionSegments` lama hanya memecah pada
 * separator eksplisit (koma/;\"dan\"/\"sama\"/\"atau\") — pesan multi-transaksi
 * tanpa separator (\"Gaji lembur 200.000 Beli rokok 30.000 Makan Malam 45.000\")
 * dianggap SATU segmen dan hanya transaksi pertama yang direkap. Fix: strategi
 * kedua — pemecahan per BATAS NOMINAL, tiap nominal = akhir satu transaksi.
 */
class MultiTransactionExtractionTest {

    private fun parse(text: String) = GeminiService.offlineHeuristicParse(text, "Ari")

    // ===== Kasus WAJIB dari laporan user =====

    @Test
    fun `contoh wajib - tiga transaksi terpisah campuran income dan expense`() {
        val r = parse("Gaji lembur 200.000 Beli rokok 30.000 Makan Malam 45.000")
        assertTrue(r.containsTransaction)
        assertEquals("3 transaksi harus direkap", 3, r.all.size)

        val income = r.all[0]
        assertEquals("PEMASUKAN", income.type)
        assertEquals(200_000.0, income.amount, 0.001)
        assertEquals("Gaji lembur", income.description)
        assertEquals("Gaji & Pemasukan", income.category)

        val rokok = r.all[1]
        assertEquals("PENGELUARAN", rokok.type)
        assertEquals(30_000.0, rokok.amount, 0.001)
        assertEquals("Beli rokok", rokok.description)

        val makan = r.all[2]
        assertEquals("PENGELUARAN", makan.type)
        assertEquals(45_000.0, makan.amount, 0.001)
        assertEquals("Makan Malam", makan.description)
        assertEquals("Makanan & Minuman", makan.category)

        // Total = PENJUMLAHAN semua nominal (275.000) — bukan netting (125.000).
        assertEquals(275_000.0, r.amount!!, 0.001)
    }

    @Test
    fun `multi pengeluaran tanpa separator`() {
        val r = parse("beli bakso 15rb bensin 30rb rokok 20rb")
        assertEquals(3, r.all.size)
        assertEquals(listOf(15_000.0, 30_000.0, 20_000.0), r.all.map { it.amount })
        assertTrue(r.all.all { it.type == "PENGELUARAN" })
        assertEquals("Beli bakso", r.all[0].description)
        assertEquals("Bensin", r.all[1].description)
        assertEquals("Rokok", r.all[2].description)
    }

    @Test
    fun `variasi nominal indonesia dalam satu pesan`() {
        val r = parse("belanja 1,5jt beli kopi 50k makan 20rb")
        assertEquals(3, r.all.size)
        assertEquals(1_500_000.0, r.all[0].amount, 0.001)
        assertEquals(50_000.0, r.all[1].amount, 0.001)
        assertEquals(20_000.0, r.all[2].amount, 0.001)
    }

    @Test
    fun `multi transaksi dengan separator campuran`() {
        val r = parse("gaji 5jt, beli rokok 20rb sama makan 45rb")
        assertEquals(3, r.all.size)
        assertEquals("PEMASUKAN", r.all[0].type)
        assertEquals(5_000_000.0, r.all[0].amount, 0.001)
        assertEquals(20_000.0, r.all[1].amount, 0.001)
        assertEquals(45_000.0, r.all[2].amount, 0.001)
    }

    // ===== ATURAN AMAN: jangan pecah yang bukan multi-transaksi =====

    @Test
    fun `beli sayur dan buah satu transaksi`() {
        // \"dan\" adalah perangkai dalam SATU item — jangan dipecah.
        val r = parse("beli sayur dan buah 20rb")
        assertEquals(1, r.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
        assertEquals("PENGELUARAN", r.all[0].type)
    }

    @Test
    fun `beli 2 kopi 20rb - kuantitas tidak jadi transaksi`() {
        val r = parse("beli 2 kopi 20rb")
        assertEquals(1, r.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `nominal tunggal tetap satu transaksi`() {
        val r = parse("beli bakso 15000")
        assertEquals(1, r.all.size)
        assertEquals(15_000.0, r.all[0].amount, 0.001)
    }

    // ===== Jam vs nominal (false-positive) =====

    @Test
    fun `jam tidak terbaca sebagai nominal`() {
        assertTrue(GeminiService.isClockTime("07.30"))
        assertTrue(GeminiService.isClockTime("14.00"))
        assertTrue(GeminiService.isClockTime("19.45"))
        assertFalse(GeminiService.isClockTime("15.000"))
        assertFalse(GeminiService.isClockTime("1.500.000"))
        assertFalse(GeminiService.isClockTime("2,5"))
        assertFalse(GeminiService.isClockTime("250"))
    }

    @Test
    fun `pesan dengan jam dan satu transaksi`() {
        val r = parse("pukul 07.30 beli nasi 20rb")
        assertEquals(1, r.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
    }

    // ===== Pemisahan per batas nominal =====

    @Test
    fun `splitByAmountBoundaries memisah per nominal`() {
        val segs = GeminiService.splitByAmountBoundaries(
            "Gaji lembur 200.000 Beli rokok 30.000 Makan Malam 45.000"
        )
        assertEquals(3, segs.size)
        assertEquals("Gaji lembur 200.000", segs[0])
        assertEquals("Beli rokok 30.000", segs[1])
        assertEquals("Makan Malam 45.000", segs[2])
    }

    @Test
    fun `splitByAmountBoundaries satu nominal - utuh`() {
        val segs = GeminiService.splitByAmountBoundaries("beli bakso 15000")
        assertEquals(1, segs.size)
        assertEquals("beli bakso 15000", segs[0])
    }

    @Test
    fun `countAmounts menghitung nominal bukan jam`() {
        assertEquals(3, GeminiService.countAmounts("Gaji 200rb Beli rokok 30rb Makan 45rb"))
        // \"07.30\" jam dilewati; hanya 20rb yang dihitung.
        assertEquals(1, GeminiService.countAmounts("pukul 07.30 beli nasi 20rb"))
        assertEquals(0, GeminiService.countAmounts("halo apa kabar"))
    }

    // ===== parseAiAmount: nominal AI bisa string / format Indonesia =====

    @Test
    fun `parseAiAmount menerima number dan string`() {
        assertEquals(200_000.0, GeminiService.parseAiAmount(200_000)!!, 0.001)
        assertEquals(200_000.0, GeminiService.parseAiAmount("200000")!!, 0.001)
        assertEquals(200_000.0, GeminiService.parseAiAmount("200.000")!!, 0.001)
        assertEquals(1_500_000.0, GeminiService.parseAiAmount("Rp 1.500.000")!!, 0.001)
        assertEquals(50_000.0, GeminiService.parseAiAmount("50rb")!!, 0.001)
        assertEquals(2_500_000.0, GeminiService.parseAiAmount("2,5jt")!!, 0.001)
        assertEquals(5_000_000.0, GeminiService.parseAiAmount("Rp5jt")!!, 0.001)
        assertNull(GeminiService.parseAiAmount(null))
        assertNull(GeminiService.parseAiAmount(""))
        assertNull(GeminiService.parseAiAmount("tidak ada"))
        assertNull(GeminiService.parseAiAmount(-5))
    }

    // ===== shouldHeuristicBackup: backup AI yang salah bilang \"tidak ada\" =====

    @Test
    fun `backup heuristik untuk pesan multi nominal`() {
        assertTrue(GeminiService.shouldHeuristicBackup("Gaji lembur 200.000 Beli rokok 30.000"))
    }

    @Test
    fun `tanpa backup untuk pesan koreksi`() {
        // \"eh bukan 15rb, 25rb\" — AI sengaja tidak mencatat (koreksi).
        assertFalse(GeminiService.shouldHeuristicBackup("eh bukan 15rb, 25rb"))
        assertFalse(GeminiService.shouldHeuristicBackup("batal beli bakso 15rb"))
    }

    @Test
    fun `tanpa backup untuk pertanyaan keuangan`() {
        assertFalse(GeminiService.shouldHeuristicBackup("hari ini sudah keluar berapa?"))
    }

    @Test
    fun `tanpa backup untuk pesan biasa`() {
        assertFalse(GeminiService.shouldHeuristicBackup("halo apa kabar?"))
    }

    // ===== Deskripsi bersih tanpa nominal =====

    @Test
    fun `deskripsi segmen tanpa nominal`() {
        val r = parse("beli bakso 15000")
        assertEquals("Beli bakso", r.all[0].description)
    }

    // ===== Regresi: perilaku lama yang TIDAK boleh rusak =====

    @Test
    fun `regresi - gaji sebesar 5 juta tetap satu pemasukan`() {
        val r = parse("gaji sebesar 5 juta")
        assertEquals(1, r.all.size)
        assertEquals("PEMASUKAN", r.all[0].type)
        assertEquals(5_000_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `regresi - bayar arisan tetap pengeluaran`() {
        val r = parse("bayar arisan 500rb")
        assertEquals(1, r.all.size)
        assertEquals("PENGELUARAN", r.all[0].type)
        assertEquals(500_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `regresi - pesan biasa bukan transaksi`() {
        assertFalse(parse("halo, apa kabar hari ini?").containsTransaction)
        assertFalse(parse("makan 2 kucing").containsTransaction)
    }

    // ===== Angka panjang polos (rekening/telepon/tahun) BUKAN nominal =====
    // (regresi ditemukan saat audit r1.4.0: splitByAmountBoundaries memecah di
    // "1234567890" → transaksi palsu Rp 1,23 miliar. Fix: isImplausiblePlainNumber.)

    @Test
    fun `rekening panjang tidak jadi transaksi palsu miliaran`() {
        val r = parse("transfer ke rekening 1234567890 sebesar 200rb")
        assertTrue(
            "nomor rekening tidak boleh jadi nominal — got ${r.all.map { it.amount }}",
            r.all.none { it.amount > 1_000_000_000 }
        )
    }

    @Test
    fun `nomor telepon tidak mengganggu transaksi asli`() {
        val r = parse("bayar ojek 20rb ke 085712345678")
        assertEquals(1, r.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `tahun tidak jadi transaksi`() {
        val r = parse("tahun 2024 total belanja 500rb")
        assertEquals(1, r.all.size)
        assertEquals(500_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `splitByAmountBoundaries tidak memecah di angka polos panjang`() {
        val segs = GeminiService.splitByAmountBoundaries(
            "transfer ke rekening 1234567890 sebesar 200rb"
        )
        assertEquals(1, segs.size)
        // Nominal riil tetap terdeteksi sebagai batas.
        val segs2 = GeminiService.splitByAmountBoundaries(
            "Gaji lembur 200.000 Beli rokok 30.000 Makan Malam 45.000"
        )
        assertEquals(3, segs2.size)
    }
}
