package com.startupmini.nyachat.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupDialogsTest {

    @Test
    fun `formatBackupTime - ISO biasa`() {
        val result = formatBackupTime("2026-08-12T07:42:23.259Z")
        // 07:42 UTC = 14:42 WIB (UTC+7). Pastikan format lokal id-ID.
        assertEquals("12 Agu 2026 · 14:42", result)
    }

    @Test
    fun `formatBackupTime - tengah malam UTC tetap di tanggal sama (WIB naik hari)`() {
        // 23:30 UTC = 06:30 WIB hari berikutnya.
        val result = formatBackupTime("2026-08-11T23:30:00.000Z")
        assertEquals("12 Agu 2026 · 06:30", result)
    }

    @Test
    fun `formatBackupTime - string tidak valid dikembalikan mentah`() {
        val raw = "bukan-timestamp"
        assertEquals(raw, formatBackupTime(raw))
    }

    @Test
    fun `formatBackupTime - string kosong`() {
        assertEquals("", formatBackupTime(""))
    }
}
