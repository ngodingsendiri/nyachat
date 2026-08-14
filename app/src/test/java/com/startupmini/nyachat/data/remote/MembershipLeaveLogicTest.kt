package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard keluar dari workspace (r1.4.0 — auto-connect & self-leave):
 * owner TIDAK boleh keluar kalau tidak ada owner lain — workspace yatim.
 * Member bebas keluar kapan pun. Logika murni [MembershipManager.canLeaveWorkspace].
 */
class MembershipLeaveLogicTest {

    @Test
    fun `member biasa boleh keluar tanpa owner lain`() {
        // Member tidak pernah membuat workspace yatim — bebas keluar.
        assertTrue(MembershipManager.canLeaveWorkspace(Constants.Roles.MEMBER, otherOwnerCount = 0))
    }

    @Test
    fun `owner dengan owner lain boleh keluar`() {
        // Ada co-owner yang masih mengelola — aman keluar.
        assertTrue(MembershipManager.canLeaveWorkspace(Constants.Roles.OWNER, otherOwnerCount = 1))
        assertTrue(MembershipManager.canLeaveWorkspace(Constants.Roles.OWNER, otherOwnerCount = 3))
    }

    @Test
    fun `owner satu-satunya tidak boleh keluar`() {
        // Owner terakhir → workspace akan yatim (tak ada yang kelola).
        assertFalse(MembershipManager.canLeaveWorkspace(Constants.Roles.OWNER, otherOwnerCount = 0))
    }

    @Test
    fun `role tidak dikenal diperlakukan konservatif`() {
        // Role aneh diperlakukan seperti member (bukan owner) — boleh keluar,
        // tidak pernah mengunci user karena data korup.
        assertTrue(MembershipManager.canLeaveWorkspace("boss", otherOwnerCount = 0))
    }
}
