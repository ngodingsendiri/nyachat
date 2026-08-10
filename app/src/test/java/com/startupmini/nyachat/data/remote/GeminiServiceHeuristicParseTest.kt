package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.data.local.FinancialTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test mesin parsing heuristik offline (tanpa AI) — dipakai saat tidak ada
 * API key / tidak ada internet. Memverifikasi deteksi nominal, tipe, dan kategori.
 */
class GeminiServiceHeuristicParseTest {

    @Test
    fun deteksiPengeluaranMakanan() {
        val r = GeminiService.offlineHeuristicParse("beli bakso 15000", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PENGELUARAN", r.type)
        assertEquals("Makanan & Minuman", r.category)
        assertEquals(15000.0, r.amount!!, 0.001)
    }

    @Test
    fun deteksiPengeluaranListrikRibuan() {
        val r = GeminiService.offlineHeuristicParse("bayar listrik 250rb", "Istri")
        assertTrue(r.containsTransaction)
        assertEquals("PENGELUARAN", r.type)
        assertEquals("Tagihan & Utilitas", r.category)
        assertEquals(250000.0, r.amount!!, 0.001)
    }

    @Test
    fun deteksiGajiJuta() {
        val r = GeminiService.offlineHeuristicParse("gaji sebesar 5 juta", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PEMASUKAN", r.type)
        assertEquals(5000000.0, r.amount!!, 0.001)
    }

    @Test
    fun deteksiAngkaDesimalKoma() {
        // "2,5jt" → 2.5 juta
        val r = GeminiService.offlineHeuristicParse("transfer masuk 2,5jt", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PEMASUKAN", r.type)
        assertEquals(2500000.0, r.amount!!, 0.001)
    }

    @Test
    fun deteksiRibuanTanpaUnit() {
        // Nominal tanpa unit, 50.000 dianggap ribuan → 50.000
        val r = GeminiService.offlineHeuristicParse("belanja di pasar 50.000", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals(50000.0, r.amount!!, 0.001)
    }

    // ---- K1: nominal ribuan bertitik bertingkat (≥ 1 juta) ----

    @Test
    fun deteksiNominalJutaBertitikGanda() {
        // Bug lama: "(\\d+(?:[.,]\\d+)?)" hanya mengekstrak grup pertama "1.500",
        // menyisakan ".000" → dianggap Rp 1.500 (salah total). Sekarang harus 1.500.000.
        val r = GeminiService.offlineHeuristicParse("gaji tanggal 1.500.000", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PEMASUKAN", r.type)
        assertEquals(1500000.0, r.amount!!, 0.001)
    }

    @Test
    fun extractAmountMenerimaJutaBertitik() {
        assertEquals(1_500_000.0, GeminiService.extractAmountFromText("gaji 1.500.000")!!, 0.001)
        assertEquals(15_000_000.0, GeminiService.extractAmountFromText("beli motor 15.000.000")!!, 0.001)
        assertEquals(1_200.0, GeminiService.extractAmountFromText("bayar 1.200")!!, 0.001)
        assertEquals(2_500_000.0, GeminiService.extractAmountFromText("transfer 2,5jt")!!, 0.001)
    }

    @Test
    fun pesanBiasaBukanTransaksi() {
        val r = GeminiService.offlineHeuristicParse("halo, apa kabar hari ini?", "Suami")
        assertFalse(r.containsTransaction)
    }

    @Test
    fun deteksiPopokKebutuhanAnak() {
        val r = GeminiService.offlineHeuristicParse("beli popok bayi 45rb", "Istri")
        assertTrue(r.containsTransaction)
        assertEquals("Kebutuhan Anak", r.category)
        assertEquals(45000.0, r.amount!!, 0.001)
    }

    // ---- Sprint-1 fix: angka bersatuan dimenangkan atas angka polos pertama ----

    @Test
    fun angkaBersatuanDimenangkanAtasKuantitasDiDepannya() {
        // Bug lama: angka pertama "2" diambil -> Rp 2.000. Sekarang 20rb -> Rp 20.000.
        val r = GeminiService.offlineHeuristicParse("beli 2 kopi 20rb", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals("PENGELUARAN", r.type)
        assertEquals("Makanan & Minuman", r.category)
        assertEquals(20000.0, r.amount!!, 0.001)
    }

    @Test
    fun angkaBersatuanKDimenangkanAtasKuantitas() {
        val r = GeminiService.offlineHeuristicParse("beli 3 botol minum 10k", "Istri")
        assertTrue(r.containsTransaction)
        assertEquals(10000.0, r.amount!!, 0.001)
    }

    @Test
    fun angkaBersatuanPertamaYangMenangBilaAdaBeberapa() {
        val r = GeminiService.offlineHeuristicParse("bayar 2 juta lalu beli kopi 20rb", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals(2000000.0, r.amount!!, 0.001)
    }

    @Test
    fun hurufKataBukanSatuanRibuan() {
        // 'k' pada "kopi" tidak boleh terbaca sebagai satuan ribu. Karena "2"
        // adalah angka polos 1 digit (kuantitas, L2), fallback → null.
        assertEquals(null, GeminiService.extractAmountFromText("beli 2 kopi"))
    }

    // ---- L2: angka polos 1 digit tanpa satuan = kuantitas, bukan nominal ----

    @Test
    fun angkaPolosSatuDigitTanpaSatuanDianggapKuantitas() {
        // "makan 2 kucing" → "2" adalah jumlah item, bukan Rp 2.000.
        assertEquals(null, GeminiService.extractAmountFromText("makan 2 kucing"))
        val r = GeminiService.offlineHeuristicParse("makan 2 kucing", "Suami")
        assertFalse(r.containsTransaction)
    }

    @Test
    fun fallbackAngkaPolosKecilTetapDianggapRibuan() {
        val r = GeminiService.offlineHeuristicParse("beli bakso 15", "Suami")
        assertTrue(r.containsTransaction)
        assertEquals(15000.0, r.amount!!, 0.001)
    }

    @Test
    fun tanpaAngkaTidakAdaNominal() {
        assertNull(GeminiService.extractAmountFromText("halo apa kabar"))
    }

    // ---- Sprint-1/2 fix B6: wrapper timeout menyelubungi kaskade AI ----

    // ---- Saran cepat (quick-add chips): deskripsi bersih, tanpa angka dobel ----

    private fun tx(description: String, amount: Double) = FinancialTransaction(
        type = "PENGELUARAN",
        category = "Lain-lain",
        amount = amount,
        description = description,
        loggedBy = "Suami"
    )

    @Test
    fun cleanSuggestionDescriptionMembuangAngkaMentah() {
        // Description dari parse chat menyimpan teks asli "beli mie ayam 20000"
        // — angka harus dibuang supaya saran tidak dobel ("20000 20000").
        assertEquals("Beli mie ayam", GeminiService.cleanSuggestionDescription("beli mie ayam 20000"))
        assertEquals("Bensin", GeminiService.cleanSuggestionDescription("bensin 20rb"))
        assertEquals("Beli kopi", GeminiService.cleanSuggestionDescription("beli kopi 2,5jt"))
        assertEquals("Makan siang", GeminiService.cleanSuggestionDescription("makan siang 25000"))
    }

    @Test
    fun cleanSuggestionDescriptionTitikRibuanJugaDibuang() {
        assertEquals("Beli mie ayam", GeminiService.cleanSuggestionDescription("beli mie ayam 20.000"))
        assertEquals("Bayar listrik", GeminiService.cleanSuggestionDescription("bayar listrik 250.000"))
    }

    @Test
    fun cleanSuggestionDescriptionTanpaAngkaTetap() {
        assertEquals("Beli sayur", GeminiService.cleanSuggestionDescription("beli sayur"))
    }

    @Test
    fun cleanSuggestionDescriptionKosongTetapKosong() {
        assertEquals("", GeminiService.cleanSuggestionDescription("20000"))
        assertEquals("", GeminiService.cleanSuggestionDescription(""))
    }

    @Test
    fun formatSuggestionAmountPakaiTitikRibuan() {
        assertEquals("20.000", GeminiService.formatSuggestionAmount(20000.0))
        assertEquals("25.000", GeminiService.formatSuggestionAmount(25000.0))
        assertEquals("5.000.000", GeminiService.formatSuggestionAmount(5000000.0))
        assertEquals("500", GeminiService.formatSuggestionAmount(500.0))
    }

    @Test
    fun buildOfflineSuggestionsTanpaAngkaDobel() {
        val transactions = listOf(
            tx("beli mie ayam 20000", 20000.0),
            tx("beli nasi 30000", 30000.0),
            tx("beli kopi 2,5jt", 2500000.0),
        )
        val suggestions = GeminiService.buildOfflineSuggestions(transactions)
        assertEquals(3, suggestions.size)
        // Tidak boleh ada angka dobel seperti "beli mie ayam 20000 20000".
        assertEquals("Beli mie ayam 20.000", suggestions[0])
        assertEquals("Beli nasi 30.000", suggestions[1])
        assertEquals("Beli kopi 2.500.000", suggestions[2])
        suggestions.forEach { s ->
            // Nominal bertitik ("20.000") dianggap SATU angka — regex menangkap
            // digit + titik ribuan sebagai satu token; angka dobel ("20000 20000")
            // akan menghasilkan 2 token.
            val numbers = Regex("\\d[\\d.]*").findAll(s).toList()
            assertTrue("Saran tidak boleh angka dobel: $s", numbers.size <= 1)
        }
    }

    @Test
    fun buildOfflineSuggestionsDedupDanBatasEmpat() {
        val transactions = listOf(
            tx("beli mie ayam 20000", 20000.0),
            tx("beli mie ayam 20000", 20000.0), // duplikat
            tx("beli nasi 30000", 30000.0),
            tx("bensin 20000", 20000.0),
            tx("bensin 20000", 20000.0), // duplikat
            tx("bayar listrik 100000", 100000.0),
            tx("beli sayur 50000", 50000.0),
        )
        val suggestions = GeminiService.buildOfflineSuggestions(transactions)
        assertEquals(4, suggestions.size)
        assertEquals(suggestions.size, suggestions.distinct().size)
        assertTrue(suggestions.contains("Beli mie ayam 20.000"))
        assertTrue(suggestions.contains("Beli nasi 30.000"))
        assertTrue(suggestions.contains("Bensin 20.000"))
    }

    @Test
    fun buildOfflineSuggestionsKosongFallbackDefault() {
        assertEquals(
            GeminiService.DEFAULT_SUGGESTIONS,
            GeminiService.buildOfflineSuggestions(emptyList())
        )
    }

    @Test
    fun buildOfflineSuggestionsHanyaPengeluaran() {
        // Saran pengeluaran tidak boleh memakai transaksi PEMASUKAN (gaji).
        val transactions = listOf(
            tx("beli mie ayam 20000", 20000.0),
            FinancialTransaction(
                type = "PEMASUKAN",
                category = "Gaji & Pemasukan",
                amount = 5000000.0,
                description = "gaji masuk 5 juta",
                loggedBy = "Suami"
            ),
        )
        val suggestions = GeminiService.buildOfflineSuggestions(transactions)
        assertTrue(suggestions.contains("Beli mie ayam 20.000"))
        assertFalse(suggestions.any { it.contains("gaji") || it.contains("5.000.000") })
    }

    @Test
    fun sanitizeSuggestionPerbaikiAngkaDobelDariAI() {
        // Jaring pengaman: kalau AI mengembalikan angka dobel, tetap dirapikan.
        assertEquals(
            "Beli mie ayam 20.000",
            GeminiService.sanitizeSuggestion("Beli mie ayam 20000 20000")
        )
        assertEquals(
            "Bensin 20.000",
            GeminiService.sanitizeSuggestion("Bensin 20.000")
        )
        // Saran kreatif tanpa angka dibiarkan.
        assertEquals("Bayar tagihan bulan ini", GeminiService.sanitizeSuggestion("Bayar tagihan bulan ini"))
        // Kosong tetap kosong.
        assertEquals("", GeminiService.sanitizeSuggestion(""))
    }

    @Test
    fun parseChatMessageTanpaKeyJatuhKeHeuristikLewatWrapperTimeout() = runBlocking {
        // Tanpa API key apa pun, kaskade di dalam withTimeoutOrNull langsung
        // null → fallback heuristik. Memastikan wrapper tidak memutus jalur
        // offline maupun menggantung.
        val prevGemini = GeminiService.userApiKey
        val prevOpenRouter = OpenRouterService.userApiKey
        try {
            GeminiService.userApiKey = null
            OpenRouterService.userApiKey = null

            val r = GeminiService.parseChatMessage("beli bakso 15000", "Suami", emptyList())
            assertTrue(r.containsTransaction)
            assertEquals("PENGELUARAN", r.type)
            assertEquals(15000.0, r.amount!!, 0.001)
        } finally {
            GeminiService.userApiKey = prevGemini
            OpenRouterService.userApiKey = prevOpenRouter
        }
    }
}