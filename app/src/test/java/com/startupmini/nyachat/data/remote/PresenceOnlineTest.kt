package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logika presence (r1.6.0): jendela "online" dari [MembershipManager.isOnlineNow]
 * dan filter [MembershipManager.onlineMembers]. Murni (tanpa Firestore) —
 * mementukan siapa yang tampil sebagai avatar di topbar.
 */
class PresenceOnlineTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `aktivitas baru dianggap online`() {
        assertTrue(MembershipManager.isOnlineNow(now - 1_000, now))
        assertTrue(MembershipManager.isOnlineNow(now - 60_000, now))
    }

    @Test
    fun `tepat di batas jendela masih online`() {
        assertTrue(
            MembershipManager.isOnlineNow(
                now - Constants.Presence.ONLINE_WINDOW_MS,
                now
            )
        )
    }

    @Test
    fun `melewati jendela dianggap offline`() {
        assertFalse(
            MembershipManager.isOnlineNow(
                now - Constants.Presence.ONLINE_WINDOW_MS - 1,
                now
            )
        )
    }

    @Test
    fun `aktivitas nol atau negatif selalu offline`() {
        assertFalse(MembershipManager.isOnlineNow(0, now))
        assertFalse(MembershipManager.isOnlineNow(-1, now))
    }

    @Test
    fun `aktivitas di masa depan dianggap online`() {
        // Heartbeat memakai serverTimestamp — nilai di masa depan hanya mungkin
        // karena clock skew kecil; perlakukan sebagai online.
        assertTrue(MembershipManager.isOnlineNow(now + 5_000, now))
    }

    @Test
    fun `onlineMembers hanya menyertakan yang aktif`() {
        val active = FamilyMember(uid = "a", name = "A", lastActiveAt = now - 1_000)
        val stale = FamilyMember(uid = "b", name = "B", lastActiveAt = now - 60 * 60_000L)
        val never = FamilyMember(uid = "c", name = "C", lastActiveAt = 0)

        val online = MembershipManager.onlineMembers(listOf(active, stale, never), now)

        assertEquals(listOf("A"), online.map { it.name })
    }

    @Test
    fun `onlineMembers mengembalikan list kosong bila tak ada yang aktif`() {
        val result = MembershipManager.onlineMembers(
            listOf(FamilyMember(uid = "b", name = "B", lastActiveAt = 0)),
            now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `jendela kustom dihormati`() {
        // window 1 menit — aktivitas 90 detik lalu berarti offline.
        assertFalse(MembershipManager.isOnlineNow(now - 90_000, now, 60_000L))
        // window 5 menit — aktivitas 90 detik lalu masih online.
        assertTrue(MembershipManager.isOnlineNow(now - 90_000, now, 300_000L))
    }
}
