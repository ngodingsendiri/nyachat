package com.startupmini.nyachat.ui.screens

import androidx.compose.runtime.saveable.SaverScope
import com.startupmini.nyachat.data.local.FinancialTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Test state holder filter Rekap (celah audit lapisan test 2026-08-13) —
 * murni, tanpa Robolectric: default, Saver round-trip, validasi restore,
 * dan `pendingDelete` yang sengaja TIDAK disimpan (dialog transien harus
 * bersih saat layar dibuka kembali).
 */
class RekapScreenStateTest {

    /** Receiver wajib untuk memanggil `Saver.save` (member extension). */
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun tx(id: Long) = FinancialTransaction(
        id = id,
        type = "PENGELUARAN",
        category = "Makanan & Minuman",
        amount = 5_000.0,
        description = "tx$id",
        loggedBy = "ISTRI",
        timestamp = 1_700_000_000_000L + id
    )

    @Test
    fun `default state kosong`() {
        val s = RekapScreenState()
        assertEquals(0, s.selectedFilterTab)
        assertNull(s.pendingDelete)
        assertNull(s.selectedMonth)
        assertNull(s.selectedCategory)
    }

    @Test
    fun `save dan restore round-trip mempertahankan tab bulan kategori`() {
        val s = RekapScreenState().apply {
            selectedFilterTab = 2
            selectedMonth = 2026 to 8
            selectedCategory = "Transportasi"
        }
        val saved = RekapScreenState.Saver.run { saverScope.save(s) }
        val restored = RekapScreenState.Saver.restore(saved!!)

        assertNotNull(restored)
        assertEquals(2, restored!!.selectedFilterTab)
        assertEquals(2026 to 8, restored.selectedMonth)
        assertEquals("Transportasi", restored.selectedCategory)
    }

    @Test
    fun `pendingDelete tidak disimpan di Saver`() {
        val s = RekapScreenState().apply { pendingDelete = tx(1) }
        val restored = RekapScreenState.Saver.restore(RekapScreenState.Saver.run { saverScope.save(s) }!!)

        assertNotNull(restored)
        assertNull(
            "pendingDelete dialog transien harus bersih setelah restore",
            restored!!.pendingDelete
        )
    }

    @Test
    fun `restore menolak tab di luar rentang valid`() {
        val restored = RekapScreenState.Saver.restore(listOf(5, -1, -1, ""))!!

        assertEquals("tab invalid harus kembali ke 0 (Semua)", 0, restored.selectedFilterTab)
        assertNull(restored.selectedMonth)
        assertNull(restored.selectedCategory)
    }

    @Test
    fun `restore menolak bulan invalid dan kategori kosong`() {
        val restored = RekapScreenState.Saver.restore(listOf(1, 0, 13, ""))!!

        assertEquals(1, restored.selectedFilterTab)
        assertNull("tahun 0 / bulan 13 (di luar 1..12) harus ditolak", restored.selectedMonth)
        assertNull("kategori kosong harus ditolak", restored.selectedCategory)
    }

    @Test
    fun `restore list kosong atau pendek tidak crash dan isi yang hilang jadi default`() {
        // Kosong total → semua default.
        val empty = RekapScreenState.Saver.restore(listOf<Any>())!!
        assertEquals(0, empty.selectedFilterTab)
        assertNull(empty.selectedMonth)
        assertNull(empty.selectedCategory)

        // Hanya tab (valid) → tab dipertahankan, bulan & kategori default.
        val onlyTab = RekapScreenState.Saver.restore(listOf<Any>(1))!!
        assertEquals(1, onlyTab.selectedFilterTab)
        assertNull("bulan yang hilang jadi default", onlyTab.selectedMonth)
        assertNull("kategori yang hilang jadi default", onlyTab.selectedCategory)

        // Tab + tahun tanpa bulan valid → bulan default, kategori default.
        val noMonth = RekapScreenState.Saver.restore(listOf<Any>(1, 2026))!!
        assertEquals(1, noMonth.selectedFilterTab)
        assertNull("bulan -1 (tidak ada) harus ditolak", noMonth.selectedMonth)
        assertNull(noMonth.selectedCategory)
    }
}
