package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keputusan re-wrap grup key E2EE (r1.7.1): wrap `e2eeKeys/{uid}` harus ditulis
 * ulang setiap kali versi pubkey member lebih baru dari versi yang tersimpan di
 * wrap. Ini menutup kasus reinstall/perangkat baru — device baru membuat pubkey
 * baru (member doc versi naik), lalu perangkat lain yang memegang grup key
 * me-rewrap untuknya.
 */
class E2eeRewrapDecisionTest {

    @Test
    fun `wrap belum ada dianggap perlu dibuat`() {
        // wrapVersion null (wrap tidak ada) + member sudah punya versi → rewrap.
        assertTrue(E2eeSyncManager.needsRewrap(wrapVersion = null, memberVersion = 1L))
    }

    @Test
    fun `versi cocok tidak perlu rewrap`() {
        assertFalse(E2eeSyncManager.needsRewrap(wrapVersion = 3L, memberVersion = 3L))
    }

    @Test
    fun `member ganti perangkat versi naik perlu rewrap`() {
        // Reinstall: pubkey baru → member doc versi 4, wrap lama masih versi 3.
        assertTrue(E2eeSyncManager.needsRewrap(wrapVersion = 3L, memberVersion = 4L))
    }

    @Test
    fun `wrap legacy tanpa versi perlu rewrap`() {
        // Wrap lama (r1.7.0) tidak punya field versi → null dianggap 0, sedangkan
        // member doc selalu ≥1 setelah pubkey di-sync → ditulis ulang sekali.
        assertTrue(E2eeSyncManager.needsRewrap(wrapVersion = null, memberVersion = 1L))
        assertTrue(E2eeSyncManager.needsRewrap(wrapVersion = 0L, memberVersion = 1L))
    }

    @Test
    fun `keduanya tanpa versi dianggap sama`() {
        assertFalse(E2eeSyncManager.needsRewrap(wrapVersion = null, memberVersion = null))
    }
}