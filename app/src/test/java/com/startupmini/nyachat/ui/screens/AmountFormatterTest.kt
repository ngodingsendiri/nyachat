package com.startupmini.nyachat.ui.screens

import com.startupmini.nyachat.ui.util.idrCurrencyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test formatter nominal input transaksi (audit UI/UX P1.4) — fungsi murni
 * di AddTransactionDialog.kt, tanpa Robolectric.
 */
class AmountFormatterTest {

    @Test
    fun `amountDigitsOnly membuang karakter non-digit`() {
        assertEquals("50000", amountDigitsOnly("50.000"))
        assertEquals("150000", amountDigitsOnly("Rp 150.000"))
        assertEquals("123", amountDigitsOnly("1a2b3"))
        assertEquals("", amountDigitsOnly(""))
    }

    @Test
    fun `formatAmountDisplay memberi grouping ribuan id-ID`() {
        assertEquals("", formatAmountDisplay(""))
        assertEquals("12", formatAmountDisplay("12"))
        assertEquals("500", formatAmountDisplay("500"))
        assertEquals("1.234", formatAmountDisplay("1234"))
        assertEquals("50.000", formatAmountDisplay("50000"))
        assertEquals("1.500.000", formatAmountDisplay("1500000"))
    }

    @Test
    fun `parseAmount men-strip separator sebelum konversi`() {
        assertEquals(50_000.0, parseAmount("50.000")!!, 0.0001)
        assertEquals(50_000.0, parseAmount("50000")!!, 0.0001)
        assertEquals(1_500_000.0, parseAmount("1.500.000")!!, 0.0001)
    }

    @Test
    fun `parseAmount null untuk input kosong atau tidak valid`() {
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
    }

    // ---- Formatter Rupiah satu sumber kebenaran (audit screens/ 2026-08-14) ----

    @Test
    fun `idrCurrencyFormat memakai locale id-ID tanpa desimal`() {
        val fmt = idrCurrencyFormat()
        // Format aktual getCurrencyInstance(id-ID) di JVM: simbol tanpa spasi.
        assertEquals("Rp1.234", fmt.format(1234))
        assertEquals("Rp50.000", fmt.format(50_000))
        assertEquals("Rp1.500.000", fmt.format(1_500_000))
        // Tanpa desimal — nilai pecahan dibulatkan.
        assertEquals("Rp1.235", fmt.format(1234.6))
    }

    @Test
    fun `idrCurrencyFormat mengembalikan instance baru tiap panggilan`() {
        // NumberFormat tidak thread-safe — setiap pemanggil harus pegang
        // instance sendiri (pola remember{} di Composable tetap benar).
        assertTrue(idrCurrencyFormat() !== idrCurrencyFormat())
    }
}
