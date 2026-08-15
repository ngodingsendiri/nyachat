package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logika kapasitas anggota per plan (r1.6.0):
 * free = 2, pro = 6 (termasuk owner). Murni [MembershipManager.memberLimitFor]
 * dan [MembershipManager.canApproveMember] — tidak menyentuh Firestore.
 * Sinkron dengan Constants.Limits & Limits cloud function handleJoinRequest.
 */
class MembershipMemberLimitTest {

    @Test
    fun `kapasitas sesuai plan`() {
        assertEquals(2, MembershipManager.memberLimitFor(Constants.Plans.FREE))
        assertEquals(6, MembershipManager.memberLimitFor(Constants.Plans.PRO))
        assertEquals(Constants.Limits.FREE_MAX_MEMBERS,
            MembershipManager.memberLimitFor(Constants.Plans.FREE))
        assertEquals(Constants.Limits.PRO_MAX_MEMBERS,
            MembershipManager.memberLimitFor(Constants.Plans.PRO))
    }

    @Test
    fun `plan tidak dikenal diperlakukan sebagai free`() {
        // Konservatif: nilai korup tidak boleh melebihi kuota — diperlakukan free.
        assertEquals(Constants.Limits.FREE_MAX_MEMBERS,
            MembershipManager.memberLimitFor("enterprise"))
    }

    @Test
    fun `approve boleh saat jumlah member di bawah limit`() {
        assertTrue(MembershipManager.canApproveMember(1, Constants.Plans.FREE))
        assertTrue(MembershipManager.canApproveMember(5, Constants.Plans.PRO))
    }

    @Test
    fun `approve ditolak saat member sudah mencapai limit`() {
        assertFalse(MembershipManager.canApproveMember(2, Constants.Plans.FREE))
        assertFalse(MembershipManager.canApproveMember(7, Constants.Plans.PRO))
    }

    @Test
    fun `kapasitas dihitung termasuk owner`() {
        // Satu owner di workspace kosong = 1 dari 2 slot free — masih muat 1 lagi.
        assertTrue(MembershipManager.canApproveMember(1, Constants.Plans.FREE))
        // Owner + 1 member = penuh di free.
        assertFalse(MembershipManager.canApproveMember(2, Constants.Plans.FREE))
    }
}
