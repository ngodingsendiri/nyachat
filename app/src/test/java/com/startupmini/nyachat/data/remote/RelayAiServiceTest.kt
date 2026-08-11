package com.startupmini.nyachat.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test RelayAiService (FASE 4) — relay AI server dengan key milik server.
 *
 * Perilaku yang dijamin (deterministik di lingkungan unit test):
 * 1. completeChat TIDAK pernah melempar — jika relay tidak bisa dipakai
 *    (tanpa FirebaseApp aktif / fungsi tidak terdeploy / offline) ia
 *    mengembalikan null, sehingga kaskade GeminiService bisa jatuh ke
 *    heuristik offline.
 * 2. isAvailable() mengecek flag jaringan: saat jelas offline (setNetworkOnline
 *    false), relay tidak dicoba — mencegah regresi latensi pesan saat offline.
 * 3. resetUsable() mengembalikan semua flag ke kondisi awal.
 */
class RelayAiServiceTest {

    @After
    fun tearDown() {
        RelayAiService.resetUsable()
    }

    @Test
    fun completeChatTanpaFirebaseKembaliNullDanTidakMelempar() = runBlocking {
        // Di unit test, FirebaseApp tidak terjamin aktif — apapun hasilnya,
        // completeChat harus null (bukan exception) supaya kaskade lanjut.
        val result = RelayAiService.completeChat("beli bakso 15000")
        assertNull("Relay tanpa Firebase harus null, bukan exception", result)
    }

    @Test
    fun isAvailableDefaultTrueDanResetMengembalikanTrue() {
        // Relay layak dicoba sejak awal (belum tahu status jaringan) — dipakai
        // isAiAvailable() di GeminiService supaya jalur AI dicoba sebelum
        // fallback offline.
        assertTrue(RelayAiService.isAvailable())
        RelayAiService.resetUsable()
        assertTrue(RelayAiService.isAvailable())
    }

    @Test
    fun isAvailableFalseSaatJaringanMati() {
        // BUG-06/regresi latensi: offline murni → relay tidak boleh dicoba
        // (langsung heuristik, tanpa nunggu timeout jaringan).
        RelayAiService.setNetworkOnline(false)
        assertFalse("Relay tidak boleh dicoba saat offline", RelayAiService.isAvailable())
        RelayAiService.resetUsable()
        assertTrue(RelayAiService.isAvailable())
    }

    @Test
    fun isAvailableTrueSaatJaringanHidup() {
        RelayAiService.setNetworkOnline(true)
        assertTrue(RelayAiService.isAvailable())
    }

    @Test
    fun completeChatSaatOfflineLangsungNull() = runBlocking {
        RelayAiService.setNetworkOnline(false)
        val result = RelayAiService.completeChat("dapat arisan 50jt")
        assertNull("Offline murni → relay harus null (langsung heuristik)", result)
    }
}
