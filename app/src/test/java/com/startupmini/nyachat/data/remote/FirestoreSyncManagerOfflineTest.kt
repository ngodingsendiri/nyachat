package com.startupmini.nyachat.data.remote

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BUG-06 (audit UX): kegagalan sinkronisasi harus diklasifikasikan jujur —
 * koneksi putus (UNAVAILABLE, IOException, "Failed to resolve", timeout) → OFFLINE,
 * sedangkan error nyata (PERMISSION_DENIED, kuota, dll.) → ERROR. Error offline
 * yang salah diklasifikasikan sebagai ERROR membuat user offline melihat
 * indikator merah "Gagal sinkron" yang menakutkan padahal kondisi normal.
 *
 * Test memakai overload murni `classifySyncFailure(networkCode, hasExplicitCode,
 * message, cause)` — referensi enum Firebase `FirebaseFirestoreException.Code`
 * memicu static-init yang memakai android.util.SparseArray (tidak di-mock di
 * unit test JVM → ExceptionInInitializerError).
 */
class FirestoreSyncManagerOfflineTest {

    private fun classify(
        networkCode: Boolean = false,
        hasExplicitCode: Boolean = false,
        message: String?,
        cause: Throwable? = null
    ) = FirestoreSyncManager.classifySyncFailure(networkCode, hasExplicitCode, message, cause)

    // ---------- Kode Firestore eksplisit ----------

    @Test
    fun unavailable_DianggapOffline() {
        assertEquals(SyncStatus.OFFLINE, classify(networkCode = true, hasExplicitCode = true, message = "Connection lost"))
    }

    @Test
    fun deadlineExceeded_DianggapOffline() {
        assertEquals(SyncStatus.OFFLINE, classify(networkCode = true, hasExplicitCode = true, message = "Timed out"))
    }

    @Test
    fun permissionDenied_TetapError() {
        // Kode non-jaringan harus tetap ERROR walau isi pesan mirip masalah koneksi.
        assertEquals(
            SyncStatus.ERROR,
            classify(
                networkCode = false,
                hasExplicitCode = true,
                message = "Missing or insufficient permissions. Network policy failed to connect"
            )
        )
    }

    @Test
    fun quotaExhausted_TetapError() {
        assertEquals(
            SyncStatus.ERROR,
            classify(networkCode = false, hasExplicitCode = true, message = "exceeds quota")
        )
    }

    @Test
    fun unauthenticated_TetapError() {
        assertEquals(
            SyncStatus.ERROR,
            classify(networkCode = false, hasExplicitCode = true, message = "caller does not have permission")
        )
    }

    // ---------- Lapisan bawah tanpa kode Firestore ----------

    @Test
    fun ioException_DianggapOffline() {
        assertEquals(SyncStatus.OFFLINE, classify(message = "Socket closed", cause = IOException("Socket closed")))
    }

    @Test
    fun ioExceptionBersarangDiCause_DianggapOffline() {
        // Firestore membungkus kegagalan socket sebagai cause — telusuri rantainya.
        val wrapped = IllegalStateException("write failed", IOException("Connection reset by peer"))
        assertEquals(SyncStatus.OFFLINE, classify(message = null, cause = wrapped))
    }

    @Test
    fun teksFailedToResolve_DianggapOffline() {
        assertEquals(
            SyncStatus.OFFLINE,
            classify(message = "Failed to resolve target GoogleApiBase; status: unavailable")
        )
    }

    @Test
    fun teksTimeout_DianggapOffline() {
        assertEquals(SyncStatus.OFFLINE, classify(message = "Request timed out after 10s"))
    }

    @Test
    fun teksOffline_DianggapOffline() {
        assertEquals(SyncStatus.OFFLINE, classify(message = "Client is offline"))
    }

    // ---------- Error nyata tanpa kode ----------

    @Test
    fun teksUmum_TetapError() {
        assertEquals(SyncStatus.ERROR, classify(message = "Invalid document structure"))
    }

    @Test
    fun teksNull_TetapError() {
        // Null-safe: message null tidak boleh NPE, dan tidak boleh dikira offline.
        assertEquals(SyncStatus.ERROR, classify(message = null))
    }
}
