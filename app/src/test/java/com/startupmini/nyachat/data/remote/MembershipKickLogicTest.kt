package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji keputusan murni "kapan listener members dianggap di-kick" (audit workspace
 * 2026-08-12). PERMISSION_DENIED saja belum cukup: kalau listener belum pernah
 * menerima snapshot sukses (bootstrap/race saat member doc baru dibuat), tolak
 * bukan berarti di-kick.
 */
class MembershipKickLogicTest {

    @Test
    fun `snapshot pernah sukses dan PERMISSION_DENIED = kick`() {
        assertTrue(MembershipManager.shouldTriggerKick(hadSnapshot = true, permissionDenied = true))
    }

    @Test
    fun `snapshot belum pernah sukses dan PERMISSION_DENIED = bukan kick (bootstrap)`() {
        assertFalse(MembershipManager.shouldTriggerKick(hadSnapshot = false, permissionDenied = true))
    }

    @Test
    fun `snapshot pernah sukses tapi error bukan PERMISSION_DENIED = bukan kick`() {
        // Error jaringan/kehilangan koneksi bukan berarti di-kick.
        assertFalse(MembershipManager.shouldTriggerKick(hadSnapshot = true, permissionDenied = false))
    }

    @Test
    fun `belum pernah snapshot dan bukan PERMISSION_DENIED = bukan kick`() {
        assertFalse(MembershipManager.shouldTriggerKick(hadSnapshot = false, permissionDenied = false))
    }
}
