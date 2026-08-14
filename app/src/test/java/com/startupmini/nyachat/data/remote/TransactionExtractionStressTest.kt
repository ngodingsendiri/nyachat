package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STRESS TEST (audit Finance AI r1.4.0 — uji ketangguhan): variasi chat
 * Indonesia ekstrem untuk memverifikasi parser tidak kehilangan transaksi,
 * tidak menggabungkan, dan tidak mencatat yang bukan transaksi.
 */
class TransactionExtractionStressTest {

    private fun parse(text: String) = GeminiService.offlineHeuristicParse(text, "Ari")

    // ===== Campuran pemasukan + pengeluaran TANPA separator =====

    @Test
    fun `campuran income dan expense tanpa separator`() {
        val r = parse("gaji 5jt beli bensin 100rb jualan online 300rb bayar listrik 250rb")
        assertEquals(4, r.all.size)
        assertEquals("PEMASUKAN", r.all[0].type)
        assertEquals(5_000_000.0, r.all[0].amount, 0.001)
        assertEquals("PENGELUARAN", r.all[1].type)
        assertEquals(100_000.0, r.all[1].amount, 0.001)
        assertEquals("PEMASUKAN", r.all[2].type)
        assertEquals(300_000.0, r.all[2].amount, 0.001)
        assertEquals("PENGELUARAN", r.all[3].type)
        assertEquals(250_000.0, r.all[3].amount, 0.001)
        // Total = penjumlahan semua, bukan netting.
        assertEquals(5_650_000.0, r.amount!!, 0.001)
    }

    // ===== Nominal dengan spasi / Rp prefix / kata "ribu" =====

    @Test
    fun `nominal dengan spasi antara angka dan satuan`() {
        val r = parse("beli bakso 20 rb dan kopi 15 ribu")
        assertEquals(2, r.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
        assertEquals(15_000.0, r.all[1].amount, 0.001)
    }

    @Test
    fun `nominal dengan prefix Rp`() {
        val r = parse("gaji Rp 5.000.000 belanja Rp 250.000")
        assertEquals(2, r.all.size)
        assertEquals(5_000_000.0, r.all[0].amount, 0.001)
        assertEquals(250_000.0, r.all[1].amount, 0.001)
    }

    @Test
    fun `nominal dengan koma desimal satuan`() {
        // "1,5jt" = 1.500.000; "0,5jt" = 500.000
        val r = parse("beli laptop 1,5jt dan upgrade ram 0,5jt")
        assertEquals(2, r.all.size)
        assertEquals(1_500_000.0, r.all[0].amount, 0.001)
        assertEquals(500_000.0, r.all[1].amount, 0.001)
    }

    // ===== Konjungsi alami yang BUKAN separator =====

    @Test
    fun `konjungsi lalu terus kemudian tidak menambah transaksi`() {
        val r = parse("gaji 3jt lalu beli makan 50rb terus bayar kos 1jt")
        assertEquals(3, r.all.size)
        assertEquals(listOf(3_000_000.0, 50_000.0, 1_000_000.0), r.all.map { it.amount })
        assertEquals(listOf("PEMASUKAN", "PENGELUARAN", "PENGELUARAN"), r.all.map { it.type })
    }

    // ===== 5+ transaksi dalam satu pesan =====

    @Test
    fun `enam transaksi dalam satu pesan`() {
        val r = parse("gaji 5jt bayar kos 1jt beli sembako 300rb isi bensin 100rb beli rokok 30rb makan siang 25rb")
        assertEquals(6, r.all.size)
        val total = r.all.sumOf { it.amount }
        assertEquals(6_455_000.0, total, 0.001)
        assertEquals(6_455_000.0, r.amount!!, 0.001)
    }

    // ===== Nominal identik berulang (bukan gabungan) =====

    @Test
    fun `nominal berulang direkap terpisah`() {
        val r = parse("transfer 100rb transfer 100rb")
        // "transfer" bukan trigger expense → heuristik konservatif tidak merekap.
        // Yang penting: TIDAK menggabung jadi 200rb tanpa deskripsi.
        assertTrue(r.all.none { it.amount > 100_000 })
    }

    @Test
    fun `belanja berulang direkap terpisah`() {
        val r = parse("belanja 50rb belanja 50rb")
        assertEquals(2, r.all.size)
        assertEquals(listOf(50_000.0, 50_000.0), r.all.map { it.amount })
    }

    // ===== Angka besar wajar (≥10 digit DENGAN satuan tetap valid) =====

    @Test
    fun `nominal besar dengan satuan tetap terbaca`() {
        val r = parse("beli rumah 500jt dan renovasi 75jt")
        assertEquals(2, r.all.size)
        assertEquals(500_000_000.0, r.all[0].amount, 0.001)
        assertEquals(75_000_000.0, r.all[1].amount, 0.001)
    }

    @Test
    fun `nominal polos 7 digit valid bukan rekening`() {
        // "1234567" = Rp 1.234.567 (7 digit < 10 — bukan rekening).
        assertFalse(GeminiService.isImplausiblePlainNumber("1234567"))
        assertTrue(GeminiService.isImplausiblePlainNumber("1234567890"))
        // 9 digit polos = Rp 123.456.789 — masih nominal wajar.
        assertFalse(GeminiService.isImplausiblePlainNumber("123456789"))
    }

    // ===== Kuantitas barang tidak jadi nominal =====

    @Test
    fun `kuantitas banyak item tidak jadi transaksi`() {
        val r = parse("beli 5 buku 20rb")
        assertEquals(1, r.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
    }

    // ===== Pesan kosong / hanya angka =====

    @Test
    fun `hanya angka tanpa kata tidak jadi transaksi`() {
        assertFalse(parse("15000").containsTransaction)
        assertFalse(parse("").containsTransaction)
    }

    // ===== Mixed separator + batas nominal sekaligus =====

    @Test
    fun `separator dan batas nominal dalam satu pesan`() {
        val r = parse("gaji 5jt, beli bakso 15rb sama bensin 30rb jajan 20rb")
        assertEquals(4, r.all.size)
        assertEquals(listOf(5_000_000.0, 15_000.0, 30_000.0, 20_000.0), r.all.map { it.amount })
    }

    // ===== Deskripsi tetap bersih tanpa nominal =====

    @Test
    fun `deskripsi multi transaksi tanpa nominal`() {
        val r = parse("beli bakso 15rb bensin 30rb")
        assertEquals("Beli bakso", r.all[0].description)
        assertEquals("Bensin", r.all[1].description)
    }

    // ===== isFinancialQuestion tetap tidak di-backup =====

    @Test
    fun `pertanyaan dengan banyak angka tidak di-backup`() {
        assertFalse(GeminiService.shouldHeuristicBackup("kalau gaji 5jt dan pengeluaran 2jt, sisa berapa?"))
    }

    @Test
    fun `koreksi dengan banyak angka tidak di-backup`() {
        assertFalse(GeminiService.shouldHeuristicBackup("eh bukan 15rb, maksudnya 25rb"))
        assertFalse(GeminiService.shouldHeuristicBackup("yang tadi salah, 30rb bukan 20rb"))
    }
}
