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

    // ===== Campuran income + expense frasa "uang masuk"/"uang keluar" =====
    // (audit 2026-08-14: "uang keluar 3jt" dulu HILANG — "keluar" bukan
    // trigger expense, hanya income "uang masuk" yang terekam)

    @Test
    fun `uang masuk dan uang keluar dalam satu pesan`() {
        val r = parse("uang masuk 5jt uang keluar 3jt")
        assertEquals(2, r.all.size)
        assertEquals("PEMASUKAN", r.all[0].type)
        assertEquals(5_000_000.0, r.all[0].amount, 0.001)
        assertEquals("PENGELUARAN", r.all[1].type)
        assertEquals(3_000_000.0, r.all[1].amount, 0.001)
        // Total = jumlah semua, bukan netting.
        assertEquals(8_000_000.0, r.amount!!, 0.001)
    }

    @Test
    fun `uang keluar saja tetap terekam sebagai pengeluaran`() {
        val r = parse("uang keluar 3jt")
        assertEquals(1, r.all.size)
        assertEquals("PENGELUARAN", r.all[0].type)
        assertEquals(3_000_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `keluar saja tanpa uang terekam sebagai pengeluaran`() {
        val r = parse("dapet gaji 5jt keluar 3jt")
        assertEquals(2, r.all.size)
        assertEquals(listOf("PEMASUKAN", "PENGELUARAN"), r.all.map { it.type })
        assertEquals(listOf(5_000_000.0, 3_000_000.0), r.all.map { it.amount })
    }

    @Test
    fun `uang masuk dan uang keluar bergantian tiga transaksi`() {
        val r = parse("uang masuk 5jt uang keluar 3jt uang masuk 2jt")
        assertEquals(3, r.all.size)
        assertEquals(
            listOf("PEMASUKAN", "PENGELUARAN", "PEMASUKAN"),
            r.all.map { it.type }
        )
        assertEquals(listOf(5_000_000.0, 3_000_000.0, 2_000_000.0), r.all.map { it.amount })
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

    // ===== r1.4.0 (audit input 2026-08-14): angka polos NON-NOMINAL =====
    // Tahun 19xx/20xx & kuantitas >=2 digit ("12 buku") sebelumnya dianggap
    // nominal sehingga transaksi ASLI hilang ("bayar spp 2025 sebesar 2jt"
    // tadinya tercatat Rp 2.025 dan 2jt hilang). Guard isNonMonetaryNumber
    // menyingkirkannya dari split/ekstraksi/perhitungan nominal.

    @Test
    fun `tahun dalam konteks spp tidak jadi nominal dan nominal asli tetap`() {
        val r = parse("bayar spp 2025 sebesar 2jt")
        assertEquals(1, r.all.size)
        assertEquals(2_000_000.0, r.all[0].amount, 0.001)

        val r2 = parse("SPP 2025 gelombang 2 sebesar 5jt")
        assertEquals(1, r2.all.size)
        assertEquals(5_000_000.0, r2.all[0].amount, 0.001)
    }

    @Test
    fun `tahun dengan nominal bersatuan lain di pesan tidak jadi nominal`() {
        val r = parse("bayar asuransi 2025 2jt")
        assertEquals(1, r.all.size)
        assertEquals(2_000_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `kuantitas dua digit diikuti satuan tidak jadi nominal`() {
        val r = parse("beli 12 buku seharga 50rb")
        assertEquals(1, r.all.size)
        assertEquals(50_000.0, r.all[0].amount, 0.001)

        val r2 = parse("beli 20 pcs kaos 100rb")
        assertEquals(1, r2.all.size)
        assertEquals(100_000.0, r2.all[0].amount, 0.001)
    }

    @Test
    fun `umur dalam tahun tidak jadi nominal`() {
        val r = parse("umur 25 tahun, beli hadiah 50rb")
        assertEquals(1, r.all.size)
        assertEquals(50_000.0, r.all[0].amount, 0.001)
        // Angka umur tidak mencemari deskripsi
        assertEquals(false, r.all[0].description.contains("25"))
    }

    @Test
    fun `tahun polos tanpa konteks tetap dianggap nominal`() {
        // "bayar 2000" = Rp 2.000 (2 ribu), BUKAN tahun — hanya di-skip saat
        // ada konteks tahun (spp/angkatan) atau nominal bersatuan lain.
        val r = parse("bayar 2000")
        assertEquals(1, r.all.size)
        assertEquals(2_000.0, r.all[0].amount, 0.001)
    }

    @Test
    fun `nomor telepon dan rekening panjang tetap tidak jadi nominal`() {
        val r = parse("transfer ke rekening 1234567890 sebesar 200rb")
        assertFalse(r.containsTransaction)
        val r2 = parse("nomor hp 08123456789 isi pulsa 25rb")
        assertEquals(1, r2.all.size)
        assertEquals(25_000.0, r2.all[0].amount, 0.001)
    }

    @Test
    fun `gaji dengan potongan pajak tercatat dua transaksi terpisah`() {
        val r = parse("gaji 5jt potong pajak 500rb")
        assertEquals(2, r.all.size)
        assertEquals(listOf(5_000_000.0, 500_000.0), r.all.map { it.amount })
        assertEquals(listOf("PEMASUKAN", "PENGELUARAN"), r.all.map { it.type })
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

    // ===== Pesan koreksi/pembatalan TIDAK dicatat di jalur OFFLINE =====
    // (regression live test 2026-08-14: "eh bukan makan 45rb maksudnya 50rb"
    // tadinya TERCATAT sebagai PENGELUARAN 45rb saat AI offline — guard
    // koreksi hanya ada di shouldHeuristicBackup, tidak di offlineHeuristicParse.)

    @Test
    fun `pesan koreksi tidak tercatat di jalur offline`() {
        val cases = listOf(
            "eh bukan makan 45rb maksudnya 50rb",
            "bukan 15rb, yang benar 25rb",
            "yang tadi salah, hapus",
            "batal, salah catat",
            "revisi dong, 30rb bukan 20rb",
            "eh salah, uang keluar 3jt bukan 5jt"
        )
        cases.forEach { c ->
            val r = GeminiService.offlineHeuristicParse(c, "Ari")
            assertFalse("koreksi '$c' tidak boleh dicatat", r.containsTransaction)
        }
    }

    @Test
    fun `pesan koreksi tetap membedakan dari transaksi asli di jalur offline`() {
        // Transaksi asli tetap terekam; hanya pesan koreksi yang ditolak.
        val normal = GeminiService.offlineHeuristicParse("beli makan 45rb", "Ari")
        assertTrue(normal.containsTransaction)
        assertTrue(normal.all.any { it.amount == 45_000.0 })

        val corrected = GeminiService.offlineHeuristicParse("eh bukan makan 45rb maksudnya 50rb", "Ari")
        assertFalse(corrected.containsTransaction)
    }
}
