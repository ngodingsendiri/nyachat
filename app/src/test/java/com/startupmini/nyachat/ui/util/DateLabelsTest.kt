package com.startupmini.nyachat.ui.util

import com.startupmini.nyachat.data.local.FinancialTransaction
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test helper label tanggal bersama (audit UI/UX P1.3) — fungsi murni,
 * tanpa Robolectric.
 */
class DateLabelsTest {

    /** Timestamp hari ini + [dayOffset] hari, jam [hour]. */
    private fun ts(dayOffset: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun tx(id: Long, timestamp: Long) = FinancialTransaction(
        id = id,
        type = "PENGELUARAN",
        category = "Makanan & Minuman",
        amount = 10_000.0,
        description = "tx$id",
        loggedBy = "ISTRI",
        timestamp = timestamp
    )

    @Test
    fun `isSameDay true untuk hari sama jam berbeda`() {
        assertTrue(isSameDay(ts(0, 1), ts(0, 23)))
    }

    @Test
    fun `isSameDay false untuk hari bersebelahan`() {
        assertFalse(isSameDay(ts(0), ts(-1)))
    }

    @Test
    fun `isSameDay false untuk tahun berbeda`() {
        val a = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1) }.timeInMillis
        val b = Calendar.getInstance().apply { set(2025, Calendar.JANUARY, 1) }.timeInMillis
        assertFalse(isSameDay(a, b))
    }

    @Test
    fun `dayLabel memakai label Hari Ini dan Kemarin`() {
        assertEquals("Hari Ini", dayLabel(ts(0), "Hari Ini", "Kemarin"))
        assertEquals("Kemarin", dayLabel(ts(-1), "Hari Ini", "Kemarin"))
    }

    @Test
    fun `dayLabel tanggal lama memakai format panjang id-ID`() {
        val label = dayLabel(ts(-30), "Hari Ini", "Kemarin")
        assertNotEquals("Hari Ini", label)
        assertNotEquals("Kemarin", label)
        // Format "EEEE, dd MMM yyyy" selalu memuat tahun & pemisah koma.
        assertTrue(label.contains(","))
        assertTrue(label.contains(Calendar.getInstance().get(Calendar.YEAR).toString()))
    }

    @Test
    fun `formatClockTime menampilkan jam menit dua digit`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 10, 9, 5, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("09:05", formatClockTime(cal.timeInMillis))
        // Jam 1 digit tetap di-pad (00-23), menit selalu 2 digit.
        cal.set(2026, Calendar.AUGUST, 10, 7, 0, 0)
        assertEquals("07:00", formatClockTime(cal.timeInMillis))
    }

    @Test
    fun `buildTransactionRows daftar kosong`() {
        assertEquals(0, buildTransactionRows(emptyList(), "Hari Ini", "Kemarin").size)
    }

    @Test
    fun `buildTransactionRows satu hari menghasilkan satu header`() {
        val rows = buildTransactionRows(
            listOf(tx(1, ts(0, 10)), tx(2, ts(0, 8))),
            "Hari Ini", "Kemarin"
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is TransactionRow.DayHeader)
        assertEquals("Hari Ini", (rows[0] as TransactionRow.DayHeader).label)
        assertTrue(rows[1] is TransactionRow.Item)
        assertTrue(rows[2] is TransactionRow.Item)
    }

    @Test
    fun `buildTransactionRows menyisipkan header tiap pergantian hari`() {
        val rows = buildTransactionRows(
            listOf(
                tx(1, ts(0, 10)),   // hari ini
                tx(2, ts(0, 8)),    // hari ini
                tx(3, ts(-1, 20)),  // kemarin
                tx(4, ts(-3, 9))    // tanggal lama
            ),
            "Hari Ini", "Kemarin"
        )
        val headers = rows.filterIsInstance<TransactionRow.DayHeader>()
        assertEquals(3, headers.size)
        assertEquals("Hari Ini", headers[0].label)
        assertEquals("Kemarin", headers[1].label)
        // Urutan: header, 2 item, header, item, header, item
        assertTrue(rows[0] is TransactionRow.DayHeader)
        assertEquals(7, rows.size)
        // Key unik (dipakai sebagai key LazyColumn).
        assertEquals(rows.size, rows.map { if (it is TransactionRow.DayHeader) it.key else "tx_${(it as TransactionRow.Item).transaction.id}" }.distinct().size)
    }

    @Test
    fun `buildTransactionRows mengikuti urutan input saat hari berulang`() {
        // Hari ini → kemarin → hari ini lagi: grouping mengikuti URUTAN input
        // (bukan kalender), jadi kembali ke hari lama memicu header baru.
        val rows = buildTransactionRows(
            listOf(tx(1, ts(0, 10)), tx(2, ts(-1, 9)), tx(3, ts(0, 7))),
            "Hari Ini", "Kemarin"
        )
        assertEquals(3, rows.filterIsInstance<TransactionRow.DayHeader>().size)
    }
}
