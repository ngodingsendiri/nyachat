package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes #2 & #3 (r1.7.1):
 * - [sendMode]: keputusan mode kirim — anti-degradasi plaintext senyap saat
 *   E2EE aktif tapi kunci lokal belum siap (BLOCK, bukan PLAINTEXT).
 * - [receiptStats]: turunan status baca/diterima untuk titik abu/hijau/pelangi —
 *   menghitung anggota LAIN saja (exclude diri sendiri & non-anggota).
 *
 * Keduanya fungsi murni → dapat diuji tanpa Firebase/Room.
 */
class ReceiptsAndSendModeTest {

    // ===== #2: sendMode =====

    @Test
    fun `sendMode - marker E2EE belum ada berarti plaintext legacy`() {
        // Workspace memang belum dienkripsi → legacy plaintext (kompatibilitas).
        assertEquals(SendMode.PLAINTEXT, sendMode(active = false, ready = false))
        assertEquals(SendMode.PLAINTEXT, sendMode(active = false, ready = true))
    }

    @Test
    fun `sendMode - E2EE aktif dan kunci siap berarti encrypt`() {
        assertEquals(SendMode.ENCRYPT, sendMode(active = true, ready = true))
    }

    @Test
    fun `sendMode - E2EE aktif tapi kunci belum siap berarti block bukan plaintext`() {
        // Inti anti-degradasi: reinstall/device baru TIDAK boleh jatuh ke
        // plaintext diam-diam — kirim ditunda sampai kunci pulih.
        assertEquals(SendMode.BLOCK, sendMode(active = true, ready = false))
    }

    // ===== #3: receiptStats =====

    @Test
    fun `receiptStats - tanpa receipt berarti belum ada yang menerima`() {
        val stats = receiptStats(receipt = null, otherUids = setOf("a", "b", "c"))
        assertEquals(3, stats.totalOthers)
        assertEquals(0, stats.delivered)
        assertEquals(0, stats.read)
        assertFalse(stats.allRead)
    }

    @Test
    fun `receiptStats - menghitung anggota lain saja dan meng-exclude diri sendiri`() {
        // Pengirim = uid "me". Receipt memuat "me" (diri sendiri, ditulis
        // penulis ACK yang keliru) + "a" (orang lain) → "me" harus diabaikan.
        val receipt = ReceiptInfo(
            cloudId = "c1",
            deliveredBy = setOf("me", "a"),
            readBy = setOf("me")
        )
        val stats = receiptStats(receipt, otherUids = setOf("a", "b"))
        assertEquals(2, stats.totalOthers)
        assertEquals(1, stats.delivered)
        assertEquals(0, stats.read)
        assertFalse(stats.allRead)
    }

    @Test
    fun `receiptStats - non-anggota yang sudah pergi ikut di-exclude`() {
        // "ghost" masih ada di receipt (perangkat lama) tapi bukan anggota lagi.
        val receipt = ReceiptInfo(
            cloudId = "c1",
            deliveredBy = setOf("a", "ghost"),
            readBy = setOf("ghost")
        )
        val stats = receiptStats(receipt, otherUids = setOf("a", "b"))
        assertEquals(2, stats.totalOthers)
        assertEquals(1, stats.delivered)
        assertEquals(0, stats.read)
        assertFalse(stats.allRead)
    }

    @Test
    fun `receiptStats - semua anggota lain membaca berarti allRead dan rainbow`() {
        val receipt = ReceiptInfo(
            cloudId = "c1",
            deliveredBy = setOf("a", "b"),
            readBy = setOf("a", "b")
        )
        val stats = receiptStats(receipt, otherUids = setOf("a", "b"))
        assertEquals(2, stats.delivered)
        assertEquals(2, stats.read)
        assertTrue(stats.allRead)
    }

    @Test
    fun `receiptStats - readBy difilter ke anggota lain - self dan non-member di-exclude`() {
        // Server (rules + arrayUnion grow-only) menjamin readBy ⊆ deliveredBy;
        // komputasi di sini cukup memotong ke anggota LAIN saja (self "me" dan
        // "ghost" yang sudah keluar grup tidak boleh dihitung).
        val receipt = ReceiptInfo(
            cloudId = "c1",
            deliveredBy = setOf("a", "b"),
            readBy = setOf("b", "ghost", "me")
        )
        val stats = receiptStats(receipt, otherUids = setOf("a", "b", "c"))
        assertEquals(3, stats.totalOthers)
        assertEquals(2, stats.delivered)
        assertEquals(1, stats.read)
        assertFalse(stats.allRead)
    }

    @Test
    fun `receiptStats - tidak ada anggota lain berarti tanpa titik dan bukan allRead`() {
        // Grup hanya berisi pengirim → tidak ada titik sama sekali.
        val receipt = ReceiptInfo(cloudId = "c1", deliveredBy = setOf("me"), readBy = setOf("me"))
        val stats = receiptStats(receipt, otherUids = emptySet())
        assertEquals(0, stats.totalOthers)
        assertEquals(0, stats.delivered)
        assertEquals(0, stats.read)
        assertFalse(stats.allRead)
    }
}