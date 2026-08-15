package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aturan "1 akun = 1 workspace" (audit workspace 2026-08-14): akun yang sudah
 * jadi OWNER di workspace lain TIDAK boleh membuat workspace baru — harus hapus
 * workspace lama atau wariskan kepemilikan (promote anggota lain jadi owner)
 * dulu. Ini mencegah penumpukan workspace sampah di database (semua akun tes
 * punya 2–7 workspace karena dulu guard ini tidak ada).
 *
 * Logika murni [MembershipManager.ownsWorkspaceElsewhere]; query Firestore-nya
 * ada di [MembershipManager.ensureOwnerWorkspace] (tidak diuji di sini).
 */
class WorkspaceOwnershipTest {

    private val docs = listOf(
        "11111111" to Constants.Roles.OWNER,
        "22222222" to Constants.Roles.MEMBER,
        "33333333" to null // doc lama tanpa role
    )

    @Test
    fun `blokir jika sudah owner di workspace lain`() {
        // Sedang membuat PIN 99999999, tapi sudah owner di 11111111 → blokir.
        assertTrue(
            MembershipManager.ownsWorkspaceElsewhere(docs, currentPin = "99999999")
        )
    }

    @Test
    fun `tidak blokir jika kepemilikan hanya di pin yang sama`() {
        // Re-connect ke workspace sendiri (pin sama) → bukan "workspace lain".
        assertFalse(
            MembershipManager.ownsWorkspaceElsewhere(docs, currentPin = "11111111")
        )
    }

    @Test
    fun `tidak blokir jika hanya jadi member di workspace lain`() {
        // Anggota (bukan owner) bebas membuat workspace sendiri.
        val memberOnly = listOf("22222222" to Constants.Roles.MEMBER)
        assertFalse(MembershipManager.ownsWorkspaceElsewhere(memberOnly, currentPin = "99999999"))
    }

    @Test
    fun `tidak blokir jika tidak punya workspace sama sekali`() {
        assertFalse(MembershipManager.ownsWorkspaceElsewhere(emptyList(), currentPin = "99999999"))
    }

    @Test
    fun `tidak blokir jika doc lain tanpa role`() {
        // Doc member tanpa field role dianggap bukan owner — jangan mengunci.
        assertFalse(
            MembershipManager.ownsWorkspaceElsewhere(
                listOf("33333333" to null),
                currentPin = "99999999"
            )
        )
    }
}
