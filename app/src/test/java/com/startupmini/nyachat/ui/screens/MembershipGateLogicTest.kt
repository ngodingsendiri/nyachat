package com.startupmini.nyachat.ui.screens

import com.startupmini.nyachat.data.remote.MembershipStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MembershipGateLogicTest {

    @Test
    fun `MEMBER langsung siap masuk`() {
        assertEquals(GateStep.Ready, MembershipGateLogic.nextStep(MembershipStatus.MEMBER, requestedOnce = false))
        assertEquals(GateStep.Ready, MembershipGateLogic.nextStep(MembershipStatus.MEMBER, requestedOnce = true))
    }

    @Test
    fun `PENDING menunggu persetujuan owner`() {
        assertEquals(GateStep.WaitForApproval, MembershipGateLogic.nextStep(MembershipStatus.PENDING, requestedOnce = true))
    }

    @Test
    fun `NOT_REQUESTED sebelum pernah minta mengirim permintaan bergabung`() {
        assertEquals(
            GateStep.SendJoinRequest,
            MembershipGateLogic.nextStep(MembershipStatus.NOT_REQUESTED, requestedOnce = false)
        )
    }

    @Test
    fun `klien ditolak - NOT_REQUESTED setelah pernah minta bukan mengirim ulang`() {
        // Fix bug: permintaan pernah dikirim lalu hilang = ditolak owner.
        // Jangan request ulang berulang (bot); berhenti dengan error REJECTED.
        assertEquals(
            GateStep.Fail(GateError.REJECTED),
            MembershipGateLogic.nextStep(MembershipStatus.NOT_REQUESTED, requestedOnce = true)
        )
    }

    @Test
    fun `FAILED menghasilkan error failed saat cek keanggotaan`() {
        assertEquals(
            GateStep.Fail(GateError.FAILED),
            MembershipGateLogic.nextStep(MembershipStatus.FAILED, requestedOnce = false)
        )
    }

    @Test
    fun `transisi simulasi anggota yang disetujui usai menunggu`() {
        // Alur normal: PENDING -> tunggu -> MEMBER.
        var step = MembershipGateLogic.nextStep(MembershipStatus.PENDING, requestedOnce = true)
        assertEquals(GateStep.WaitForApproval, step)
        step = MembershipGateLogic.nextStep(MembershipStatus.MEMBER, requestedOnce = true)
        assertEquals(GateStep.Ready, step)
    }
}