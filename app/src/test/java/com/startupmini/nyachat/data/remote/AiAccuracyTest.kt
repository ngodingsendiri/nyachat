package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UJI AKURASI AI (r1.4.0 — audit: jalur AI `parseJsonResponse` tidak punya
 * test langsung sama sekali; semua test lama hanya menutupi heuristik offline).
 * Menguji parsing respons AI dari Gemini/OpenRouter: format baru (array
 * multi-transaksi), format lama (field tunggal), nominal String Indonesia,
 * kategori valid, tanggal, dan JSON rusak.
 */
@RunWith(RobolectricTestRunner::class)
class AiAccuracyTest {

    private fun parse(text: String) =
        GeminiService.parseJsonResponse(GeminiService.wrapOpenAiText(text), "pesan asli", "Ari")

    // ===== Format baru: array transactions =====

    @Test
    fun `format baru - tiga transaksi campuran`() {
        val r = parse(
            """{"containsTransaction":true,"transactions":[
                {"type":"PEMASUKAN","category":"Gaji & Pemasukan","amount":200000,"description":"Gaji lembur","date":""},
                {"type":"PENGELUARAN","category":"Hiburan & Belanja","amount":30000,"description":"Beli rokok","date":""},
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":45000,"description":"Makan malam","date":""}
            ],"aiReply":"3 transaksi dicatat"}"""
        )
        assertNotNull(r)
        assertTrue(r!!.containsTransaction)
        assertEquals(3, r.all.size)
        assertEquals("PEMASUKAN", r.all[0].type)
        assertEquals(200_000.0, r.all[0].amount, 0.001)
        assertEquals(30_000.0, r.all[1].amount, 0.001)
        assertEquals(45_000.0, r.all[2].amount, 0.001)
        // Total = penjumlahan (tidak netting).
        assertEquals(275_000.0, r.amount!!, 0.001)
        assertEquals("AI", r.detectedBy)
    }

    @Test
    fun `format baru - nominal string Indonesia`() {
        // AI kadang menulis "Rp 200.000" / "200rb" / "1,5jt" — tidak boleh dibuang.
        val r = parse(
            """{"containsTransaction":true,"transactions":[
                {"type":"PEMASUKAN","category":"Gaji & Pemasukan","amount":"Rp 200.000","description":"Gaji","date":""},
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":"50rb","description":"Kopi","date":""},
                {"type":"PENGELUARAN","category":"Lain-lain","amount":"1,5jt","description":"Upgrade","date":""}
            ]}"""
        )
        assertNotNull(r)
        assertEquals(3, r!!.all.size)
        assertEquals(200_000.0, r.all[0].amount, 0.001)
        assertEquals(50_000.0, r.all[1].amount, 0.001)
        assertEquals(1_500_000.0, r.all[2].amount, 0.001)
    }

    @Test
    fun `format baru - tanggal eksplisit diparse`() {
        val r = parse(
            """{"containsTransaction":true,"transactions":[
                {"type":"PENGELUARAN","category":"Tagihan & Utilitas","amount":250000,"description":"Listrik","date":"2026-08-13"}
            ]}"""
        )
        assertNotNull(r)
        assertNotNull(r!!.all[0].timestamp)
    }

    @Test
    fun `format baru - nominal nol atau negatif dibuang`() {
        val r = parse(
            """{"containsTransaction":true,"transactions":[
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":0,"description":"Nol","date":""},
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":-5,"description":"Negatif","date":""},
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":20000,"description":"Valid","date":""}
            ]}"""
        )
        assertNotNull(r)
        assertEquals(1, r!!.all.size)
        assertEquals(20_000.0, r.all[0].amount, 0.001)
        assertEquals("Valid", r.all[0].description)
    }

    // ===== Format lama: field tunggal =====

    @Test
    fun `format lama - field tunggal tetap diterima`() {
        val r = parse(
            """{"containsTransaction":true,"type":"PENGELUARAN","category":"Transportasi","amount":50000,"description":"Bensin"}"""
        )
        assertNotNull(r)
        assertTrue(r!!.containsTransaction)
        assertEquals(1, r.all.size)
        assertEquals("PENGELUARAN", r.type)
        assertEquals(50_000.0, r.amount!!, 0.001)
        assertEquals("AI", r.detectedBy)
    }

    @Test
    fun `format lama - nominal string`() {
        val r = parse(
            """{"containsTransaction":true,"type":"PEMASUKAN","category":"Gaji & Pemasukan","amount":"2.500.000","description":"Gaji"}"""
        )
        assertNotNull(r)
        assertEquals(2_500_000.0, r!!.amount!!, 0.001)
    }

    // ===== containsTransaction = false =====

    @Test
    fun `AI bilang tidak ada transaksi`() {
        val r = parse("""{"containsTransaction":false,"aiReply":"Tidak ada transaksi dicatat."}""")
        assertNotNull(r)
        assertFalse(r!!.containsTransaction)
        assertEquals("Tidak ada transaksi dicatat.", r.aiReply)
    }

    // ===== Kategori =====

    @Test
    fun `kategori tidak valid dinormalisasi ke default`() {
        val r = parse(
            """{"containsTransaction":true,"transactions":[
                {"type":"PENGELUARAN","category":"Makanan Ringan Favorit","amount":15000,"description":"Snack","date":""}
            ]}"""
        )
        assertNotNull(r)
        // Kategori diarang → default pengeluaran = Lain-lain.
        assertEquals("Lain-lain", r!!.all[0].category)
    }

    @Test
    fun `kategori valid dipertahankan`() {
        val r = parse(
            """{"containsTransaction":true,"transactions":[
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":15000,"description":"Snack","date":""}
            ]}"""
        )
        assertNotNull(r)
        assertEquals("Makanan & Minuman", r!!.all[0].category)
    }

    // ===== JSON rusak / bukan format AI =====

    @Test
    fun `JSON rusak mengembalikan null`() {
        assertNull(parse("ini bukan json sama sekali {"))
        assertNull(parse(""))
    }

    @Test
    fun `AI balas JSON tanpa containsTransaction - tidak ada transaksi`() {
        // Teks AI yang berisi objek JSON tanpa penanda transaksi → false.
        val r = parse("""{"candidates":[]}""")
        assertNotNull(r)
        assertFalse(r!!.containsTransaction)
    }

    @Test
    fun `code fence markdown dibersihkan`() {
        val r = parse(
            """```json
            {"containsTransaction":true,"transactions":[
                {"type":"PENGELUARAN","category":"Makanan & Minuman","amount":15000,"description":"Bakso","date":""}
            ]}
            ```"""
        )
        assertNotNull(r)
        assertEquals(1, r!!.all.size)
        assertEquals(15_000.0, r.all[0].amount, 0.001)
    }

    // ===== wrapOpenAiText =====

    @Test
    fun `wrapOpenAiText menghasilkan JSON valid yang bisa diparse`() {
        val wrapped = GeminiService.wrapOpenAiText(
            """{"containsTransaction":true,"type":"PENGELUARAN","category":"Transportasi","amount":20000,"description":"Ojek"}"""
        )
        val r = GeminiService.parseJsonResponse(wrapped, "pesan", "Ari")
        assertNotNull(r)
        assertEquals(20_000.0, r!!.amount!!, 0.001)
        assertEquals("Ojek", r.description)
    }

    // ===== isOfflineFallbackReply =====

    @Test
    fun `deteksi balasan offline generik`() {
        assertTrue(GeminiService.isOfflineFallbackReply(GeminiService.offlineChatReply("apa kabar")))
        assertFalse(GeminiService.isOfflineFallbackReply("Jawaban AI yang normal"))
    }

    // ===== Keputusan online vs offline =====

    @Test
    fun `isAiAvailable false tanpa kunci`() {
        // Tanpa kunci OpenRouter/Gemini & relay dimatikan → offline murni.
        val prevKey = GeminiService.userApiKey
        val prevOpenRouterKey = OpenRouterService.userApiKey
        RelayAiService.resetUsable()
        try {
            GeminiService.userApiKey = null
            OpenRouterService.userApiKey = null
            RelayAiService.setNetworkOnline(false)
            assertFalse(GeminiService.isAiAvailable())
        } finally {
            GeminiService.userApiKey = prevKey
            OpenRouterService.userApiKey = prevOpenRouterKey
            RelayAiService.resetUsable()
        }
    }

    @Test
    fun `backup heuristik aktif saat AI salah bilang tidak ada transaksi`() {
        // Pesan multi-nominal yang AI (keliru) jawab containsTransaction=false
        // → heuristik offline diverifikasi (transaksi tidak hilang saat AI
        // rate-limit/offline).
        assertTrue(GeminiService.shouldHeuristicBackup("Gaji lembur 200.000 Beli rokok 30.000"))
        // Pesan tanpa nominal tidak butuh backup.
        assertFalse(GeminiService.shouldHeuristicBackup("halo apa kabar"))
    }
}
