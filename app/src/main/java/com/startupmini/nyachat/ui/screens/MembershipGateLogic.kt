package com.startupmini.nyachat.ui.screens

import com.startupmini.nyachat.data.remote.MembershipStatus

/** Penyebab gate berhenti dengan error (ditampilkan ke pengguna). */
enum class GateError { PIN_OWNED, OWNED_ELSEWHERE, NOT_FOUND, FAILED, REJECTED, TIMEOUT }

/**
 * Langkah berikutnya untuk alur gate anggota. Logika murni — dipisah dari
 * Composable agar bisa di-unit-test tanpa Firestore/Compose.
 */
sealed interface GateStep {
    /** Anggota sudah jadi member → lanjut ke chat. */
    data object Ready : GateStep

    /** Belum pernah minta / belum dicek → kirim permintaan bergabung sekali. */
    data object SendJoinRequest : GateStep

    /** Permintaan menunggu persetujuan owner → tunggu lalu cek lagi. */
    data object WaitForApproval : GateStep

    /** Gate berhenti karena error ([GateError]). */
    data class Fail(val reason: GateError) : GateStep
}

/**
 * Mesin status alur keanggotaan. Kunci perilaku (juga menjaga fix bug):
 * permintaan bergabung hanya dikirim SATU KALI per sesi gate. Kalau status
 * kembali NOT_REQUESTED setelah pernah mengirim, artinya owner MENOLAK —
 * jangan mengirim ulang berulang sampai bot.
 */
object MembershipGateLogic {

    /**
     * Tentukan langkah berikutnya dari [status] keanggotaan saat ini.
     *
     * @param requestedOnce true jika permintaan bergabung sudah dikirim pada
     *   sesi gate ini (tapi status belum jadi MEMBER).
     */
    fun nextStep(status: MembershipStatus, requestedOnce: Boolean): GateStep = when (status) {
        MembershipStatus.MEMBER -> GateStep.Ready
        MembershipStatus.FAILED -> GateStep.Fail(GateError.FAILED)
        MembershipStatus.TIMED_OUT -> GateStep.Fail(GateError.TIMEOUT)
        MembershipStatus.NOT_REQUESTED ->
            if (requestedOnce) GateStep.Fail(GateError.REJECTED)
            else GateStep.SendJoinRequest
        MembershipStatus.PENDING -> GateStep.WaitForApproval
    }
}
